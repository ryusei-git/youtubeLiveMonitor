# syntax=docker/dockerfile:1
# 本番のイメージ（運用リポジトリ server-stacks の stacks/youtube-live-monitor が使う。#856）。
#
# ビルド → 実行の 2 段。CI は「テストを通った jar」で build 段を差し替える（ci.yml の build-contexts。build 段の Gradle は走らない）。
# 手元の `docker build .` は build 段で bootJar を作る（テストは CI で済んでいるので飛ばす）。
#
# 実行段の作業ディレクトリは /app。アプリの既定の相対パス（./data/monitor・logs/・.env）がそのまま合う。
# 運用側が /app/data（SSD）・/app/logs（SSD）・/app/storage（HDD。録画は /app/storage/recordings）をマウントする。

FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
# 依存だけ先に解決して層に残す（src を変えても依存の取り直しにならない）
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q bootJar \
 && mkdir -p /out && cp build/libs/*.jar /out/app.jar

# テストした版（ci.yml の temurin 21）と同じ JRE。Ubuntu 24.04 系なので UID 1000 の ubuntu ユーザーがいる
# （1 コアを使い切るプロセスの注意（ResourceMonitorService）はプロセスの持ち主をユーザー名で比べるので、
#   名前の無い UID だと対象が空になり、注意が出なくなる）
FROM eclipse-temurin:21-jre-noble

# deno は yt-dlp-ejs が YouTube の JS を解くのに使う（yt-dlp が既定で探すのは deno だけ。docs/pitfalls.md）。版と sha256 を固定する
ARG DENO_VERSION=2.9.7
ARG DENO_SHA256=c6527f24f4b16031d3ae4fa9f658d5f11534c8d84ce7dc8502420280919c3490

# ffmpeg は ffprobe も入る。curl は HEALTHCHECK と運用側の確認用。python3-venv は yt-dlp を入れる venv 用。
# nice（coreutils）と tzdata はベースにある。zenity（設定画面の「参照...」）は入れない（コンテナに画面は無い）
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg python3 python3-venv curl unzip \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL -o /tmp/deno.zip "https://github.com/denoland/deno/releases/download/v${DENO_VERSION}/deno-x86_64-unknown-linux-gnu.zip" \
 && echo "${DENO_SHA256}  /tmp/deno.zip" | sha256sum -c - \
 && unzip -q /tmp/deno.zip -d /usr/local/bin \
 && rm /tmp/deno.zip

# yt-dlp は yt-dlp-ejs 込みの "yt-dlp[default]" を venv に入れる（ejs が無いと YouTube の形式が欠け、ログインしても取れない）。
# 版は固定しない: YouTube 側の変更に追従するのが目的で、CI が週 1 回イメージを作り直す（ci.yml の schedule）。
# PATH に置くのは名前が yt-dlp のリンク（アプリは名前で起動し、DefaultProcessLauncher も名前で見分ける）
RUN python3 -m venv /opt/yt-dlp \
 && /opt/yt-dlp/bin/pip install --no-cache-dir --upgrade pip \
 && /opt/yt-dlp/bin/pip install --no-cache-dir "yt-dlp[default]" \
 && ln -s /opt/yt-dlp/bin/yt-dlp /usr/local/bin/yt-dlp \
 && yt-dlp --version && deno --version > /dev/null

# - TZ: 日時は LocalDateTime.now()（JVM の既定のタイムゾーン）で DB に入る。旧端末の行・CI のテストと同じ日本時間にする
# - LANG: CLI の日本語の出力とファイル名の文字コード
# - MALLOC_ARENA_MAX・JAVA_OPTS: bin/service.sh の既定と同じ（理由はそちらのコメント）。hs_err は書ける logs/ に出す
# - XDG_CACHE_HOME・DENO_DIR: yt-dlp・deno・JNA（OSHI）のキャッシュ。運用側は read_only で、書けるのは tmpfs の /tmp。
#   JNA は取り出した .so を読み込むので、運用側の tmpfs は exec を付ける
ENV TZ=Asia/Tokyo \
    LANG=C.UTF-8 \
    MALLOC_ARENA_MAX=2 \
    XDG_CACHE_HOME=/tmp/.cache \
    DENO_DIR=/tmp/.cache/deno \
    JAVA_OPTS="-Xmx1g -XX:G1PeriodicGCInterval=300000 -XX:TrimNativeHeapInterval=300000 -XX:+ExitOnOutOfMemoryError -XX:ErrorFile=/app/logs/hs_err_%p.log"

WORKDIR /app
COPY --from=build /out/app.jar /app/app.jar
# 設定画面は .env が無いとき .env.example を土台にする
COPY .env.example /app/.env.example
COPY docker/entrypoint.sh /usr/local/bin/ylm-entrypoint

# - h2.jar: H2 の控え（BACKUP TO）と検査を、サービスと同じ版の H2 で行うため（bin/backup.sh と同じ理由）。jar の中から取り出す
# - /app/.env → data/.env: 設定画面の保存は実体を書き換えるので、read_only でも data/（マウント）に書ける。
#   秘密を compose の env_file にしないのは、コンテナの環境変数になると yt-dlp・ffmpeg に引き継がれるため
RUN mkdir -p /app/tools /app/data /app/logs /app/storage \
 && python3 -c "import re, zipfile; z = zipfile.ZipFile('/app/app.jar'); n = [x for x in z.namelist() if re.fullmatch(r'BOOT-INF/lib/h2-[0-9.]+\.jar', x)]; assert len(n) == 1, n; open('/app/tools/h2.jar', 'wb').write(z.read(n[0]))" \
 && ln -s data/.env /app/.env \
 && chmod 755 /usr/local/bin/ylm-entrypoint \
 && chown 1000:1000 /app/data /app/logs /app/storage

USER 1000:1000
EXPOSE 8080
# /api/health はログイン不要。巡回が固まった STALE は 503 なので unhealthy になる
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/api/health > /dev/null || exit 1
ENTRYPOINT ["ylm-entrypoint"]
