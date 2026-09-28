#!/usr/bin/env bash
#
# 本番を止めずに、最新の main を確認用として別の場所で起動する。
# 使い方: bin/preview.sh start | stop
#
# 【この形にした理由】
# 録画中に本番をビルド・再起動すると録画の追跡が失われる（docs/pitfalls.md「録画中にアプリを
# 再起動すると…」）。その間も画面の変更を確かめられるよう、本番と完全に分けて動かす。
#   - ../ylm-preview に main の最新を detached で取り出してビルドする（本番の jar に触らない）
#   - DB は稼働中の H2 から BACKUP TO で複製する（オンラインで安全に取れる。書き込みは複製にだけ届く）
#   - 監視・動画収集を止め（monitor.scheduling.enabled=false）、Discord 通知の宛先を空にする
#     （本番と並べても録画・通知が二重に起きない）
#   - 録画フォルダは本物を指す（容量・リソース・再生を本物で確かめるため）
#
# 【注意】確認用の画面から録画の「削除」「URL を指定してダウンロード」「孤立ファイルの掃除」を
# 行うと本物の録画フォルダに効く。確認用では使わないこと。

set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
PREVIEW="$(cd .. && pwd)/ylm-preview"
PORT="${PREVIEW_PORT:-18080}"
PID_FILE="$PREVIEW/preview.pid"

stop_preview() {
    if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        kill "$(cat "$PID_FILE")"
        while kill -0 "$(cat "$PID_FILE")" 2>/dev/null; do sleep 1; done
        echo "確認用を停止しました"
    fi
    rm -f "$PID_FILE"
}

case "${1:-start}" in
    stop)
        stop_preview
        ;;
    start)
        stop_preview
        git fetch -q origin
        if [[ -d "$PREVIEW" ]]; then
            git -C "$PREVIEW" checkout -q --detach origin/main
        else
            git worktree add -q --detach "$PREVIEW" origin/main
        fi
        echo "ビルドしています（$PREVIEW）..."
        (cd "$PREVIEW" && ./gradlew build -x test -q)

        echo "DB を複製しています..."
        H2_JAR="$(find ~/.gradle/caches -name 'h2-*.jar' ! -name '*sources*' | sort | tail -1)"
        mkdir -p "$PREVIEW/data" "$PREVIEW/logs"
        rm -f "$PREVIEW/data/backup.zip" "$PREVIEW"/data/monitor.*.db
        # DB のパスワードは本番と同じく .env から読む（#314）。ほかの値まで環境変数に流さないよう
        # サブシェルで読み、このキーだけを取り出す。キーが無ければ空（パスワード未設定の DB）
        DB_PASSWORD="$(set -a; . ./.env; set +a; echo "${SPRING_DATASOURCE_PASSWORD:-}")"
        # サービスが止まっていると Shell 自身が AUTO_SERVER の待ち受けを開くので、この端末だけに限る（#312 と同じ理由）
        java -Dh2.bindAddress=127.0.0.1 -cp "$H2_JAR" org.h2.tools.Shell \
            -url "jdbc:h2:file:$ROOT/data/monitor;AUTO_SERVER=TRUE" -user sa -password "$DB_PASSWORD" \
            -sql "BACKUP TO '$PREVIEW/data/backup.zip'" >/dev/null
        (cd "$PREVIEW/data" && unzip -oq backup.zip && rm backup.zip)

        cp "$ROOT/.env" "$PREVIEW/.env"
        JAR="$(ls "$PREVIEW"/build/libs/*.jar | grep -v plain | head -1)"
        # 引数を 1 つでも付けると CLI モードで起動してしまう（YouTubeLiveMonitorApplication.main）ため、
        # 設定は -D のシステムプロパティで渡す
        # cd と java を 1 つのかたまりで裏に回すと、記録されるのが途中の bash の PID になり、
        # stop で java を止められず、その bash が呼び出し元の出力を握って終わらなくなる
        # メモリの設定は bin/service.sh の既定と同じにする。付けないとヒープの上限が物理メモリの 1/4
        # （この端末で 3.98GB）になり、本番と並べて動かす確認用が本番より多くのメモリを抱えうる（#188）。
        #   -Xmx1g: 本番の実測（2026-09-25）は使用 271MB・確保 692MB で、使用量のおよそ 4 倍の余裕がある
        #   G1PeriodicGCInterval・TrimNativeHeapInterval（5 分）: 待機中も、使っていないヒープと malloc の
        #     領域を OS へ返させる（本番ではヒープの外に 250〜290MB あり、縮んだ後のヒープより大きかった）
        #   MALLOC_ARENA_MAX=2: malloc のアリーナを既定の 8 × コア数（64）から絞る（本番では 66 個あった）
        cd "$PREVIEW"
        MALLOC_ARENA_MAX=2 nohup java \
            -Xmx1g -XX:G1PeriodicGCInterval=300000 -XX:TrimNativeHeapInterval=300000 \
            -Dserver.port="$PORT" \
            -Dmonitor.scheduling.enabled=false \
            -Dmonitor.discord.webhook-url= \
            -Dmonitor.recording.directory="$ROOT/recordings" \
            -jar "$JAR" \
            > "$PREVIEW/logs/preview.log" 2>&1 < /dev/null &
        echo $! > "$PID_FILE"
        cd "$ROOT"

        echo -n "起動を待っています"
        for _ in $(seq 1 60); do
            if curl -s -o /dev/null --max-time 5 "http://localhost:$PORT/adminLogin.html"; then
                echo
                echo "確認用を起動しました: http://localhost:$PORT/adminLogin.html"
                echo "（監視・収集・通知は止めてあります。録画の削除・ダウンロード・掃除は本物に効くので使わないこと）"
                exit 0
            fi
            echo -n "."; sleep 2
        done
        echo; echo "起動を確認できませんでした。$PREVIEW/logs/preview.log を見てください" >&2
        exit 1
        ;;
    *)
        echo "使い方: bin/preview.sh start | stop" >&2; exit 1
        ;;
esac
