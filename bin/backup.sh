#!/usr/bin/env bash
#
# H2 の DB（data/monitor.mv.db）を data/backups/monitor-<日時>.zip へ控え、戻せることを確かめてから古いものを消す。
# 使い方: bin/backup.sh    （cron の例と復元の手順は README「バックアップと復元」）
#
# 【この形にした理由】
# この DB は利用者のアカウント・購読・視聴済み・Webhook・監査ログの唯一の置き場で、起動のたびに
# ddl-auto: update がスキーマを書き換え、止まらないときは kill -9 する。壊れたら全員が登録し直しになる。
#   - ファイルを cp せず BACKUP TO を使う: 稼働中でも書き込みの途中を写さない一貫した控えになる
#     （bin/preview.sh と同じやり方）。サービスが止まっていても動く（AUTO_SERVER は自分で開く）
#   - サービスの稼働中は BACKUP TO をサービスの JVM が実行するので、相対パスはサービスの作業
#     ディレクトリを基準に解決される（今はどちらもリポジトリの直下で同じ場所になる）。どこから
#     起動されても同じ場所へ書くよう、出力先は絶対パスで渡す
#   - zip はパスワードのハッシュや Webhook の URL を含むので、本人だけが読めるようにする
#   - H2 のクライアントは、動いているサービスの jar（run/youtubeLiveMonitor.jar）の中の H2 を取り出して使う。
#     以前は ~/.gradle/caches で名前順の最後の H2 を使っていたが、キャッシュの無い端末では空になり、
#     キャッシュに複数の版があるとサービスと違う版になりうる（サービスが止まっているときは、その版で
#     ファイルを直接開く）。build/libs を後にするのは、稼働中の ./gradlew build が書き換えるため
#     （docs/pitfalls.md「build/libs の jar を直接動かすと…」）
#   - H2 Shell にも -Dh2.bindAddress=127.0.0.1 を付ける。サービスが止まっているときは Shell 自身が
#     AUTO_SERVER の待ち受けを開き、付けないと全インターフェースで待ち受ける（#312 と同じ理由）
#   - 「ファイルが空でない」だけでは、壊れた zip や開けない DB でも成功になる。zip を検査し、一時ディレクトリへ
#     展開して読み取り専用で開き、利用者（app_users）を数えられることまで確かめる。Shell は SQL の失敗でも
#     終了コードが 0 なので、数えた結果の行で判断する
#   - cron で毎晩動かすと、失敗してもログに残るだけで誰も気付かない。失敗したら bin/health-watch.sh と
#     同じ宛先（.env の DISCORD_WEBHOOK_URL）へ知らせる。確かめられなかった控えは消し、古い控えは消さない
#     （消さずに残すと、失敗が続いた後の世代の整理で、戻せる古い控えが押し出される）

set -euo pipefail
cd "$(dirname "$0")/.."
# H2（java）に渡すパスは OS の形式にする。Git Bash の pwd は /c/... を返し、java はそれを C:\c\... と読む
ROOT="$(pwd -W 2>/dev/null || pwd)"
KEEP=14
BACKUP_DIR="$ROOT/data/backups"
OUT="$BACKUP_DIR/monitor-$(date +%Y%m%d-%H%M%S).zip"
WORK=""

# DB のパスワードは .env から読む（#314）。ほかの値まで環境変数に流さないようサブシェルで読み、
# このキーだけを取り出す。.env やキーが無ければ空（パスワード未設定の DB）
DB_PASSWORD="$(if [[ -f .env ]]; then set -a; . ./.env; set +a; fi; echo "${SPRING_DATASOURCE_PASSWORD:-}")"
# 失敗の知らせ先は bin/health-watch.sh と同じ。環境変数 DISCORD_WEBHOOK_URL を（空でも）指定すればそちらを使う（試すとき用）
WEBHOOK="${DISCORD_WEBHOOK_URL-$(if [[ -f .env ]]; then set -a; . ./.env; set +a; fi; echo "${DISCORD_WEBHOOK_URL:-}")}"

# bin/health-watch.sh の notify() と同じ。文言に " や改行を入れないこと（JSON を組み立てていない）
notify() {
    if [[ -z "$WEBHOOK" ]]; then
        return 0
    fi
    curl -s --max-time 10 -H 'Content-Type: application/json' \
        -d "{\"content\":\"$1\"}" "$WEBHOOK" > /dev/null || true
}

fail() {
    echo "$1" >&2
    exit 1
}

finish() {
    local status=$?
    if [[ -n "$WORK" ]]; then
        rm -rf "$WORK"
    fi
    if (( status != 0 )); then
        rm -f "$OUT"
        echo "バックアップに失敗しました（$(date '+%Y-%m-%d %H:%M:%S')）。古い控えは残してあります" >&2
        notify "YouTube Live Monitor: バックアップに失敗しました。logs/backup.log を確認してください"
    fi
}
trap finish EXIT

h2_shell() {
    java -Dh2.bindAddress=127.0.0.1 -cp "$WORK/h2.jar" org.h2.tools.Shell "$@"
}

for cmd in java unzip; do
    if ! command -v "$cmd" > /dev/null; then
        fail "$cmd が見つかりません（cron から動かすときは、cron の PATH に $cmd があるか確かめてください）"
    fi
done

mkdir -p "$BACKUP_DIR"
WORK="$(mktemp -d "$BACKUP_DIR/.verify.XXXXXX")"

APP_JAR="run/youtubeLiveMonitor.jar"
if [[ ! -f "$APP_JAR" ]]; then
    APP_JAR="$(ls build/libs/*.jar 2>/dev/null | head -1 || true)"
fi
if [[ -z "$APP_JAR" ]]; then
    fail "H2 を取り出す jar がありません（run/youtubeLiveMonitor.jar も build/libs/*.jar も無い）"
fi
unzip -p "$APP_JAR" 'BOOT-INF/lib/h2-*.jar' > "$WORK/h2.jar" 2>/dev/null || true
if [[ ! -s "$WORK/h2.jar" ]]; then
    fail "$APP_JAR の中に H2 の jar（BOOT-INF/lib/h2-*.jar）がありません"
fi

# IFEXISTS=TRUE: DB が無いときに空の DB を作って「控えた」ことにしない
backup_log="$(h2_shell -url "jdbc:h2:file:$ROOT/data/monitor;AUTO_SERVER=TRUE;IFEXISTS=TRUE" \
    -user sa -password "$DB_PASSWORD" -sql "BACKUP TO '$OUT'" 2>&1 || true)"
if [[ ! -s "$OUT" ]]; then
    fail "BACKUP TO で $OUT ができませんでした: $(head -3 <<< "$backup_log")"
fi
chmod 600 "$OUT"

if ! unzip -tq "$OUT" > /dev/null 2>&1; then
    fail "$OUT が zip として壊れています"
fi
unzip -q "$OUT" -d "$WORK/restore"
# 数は標準出力の 2 行目（1 行目は見出しの COUNT(*)、3 行目は (1 row, N ms)）。標準エラーは混ぜない
# （JVM が標準エラーに出す「Picked up JAVA_TOOL_OPTIONS: …」などの行で行がずれ、戻せる控えを失敗扱いにする）
count_log="$(h2_shell -url "jdbc:h2:file:$WORK/restore/monitor;IFEXISTS=TRUE;ACCESS_MODE_DATA=r" \
    -user sa -password "$DB_PASSWORD" -sql "SELECT COUNT(*) FROM APP_USERS" 2> "$WORK/count.err" || true)"
users="$(sed -n 2p <<< "$count_log")"
if [[ ! "$users" =~ ^[0-9]+$ ]]; then
    fail "控えを開いて利用者を数えられませんでした: $(head -3 <<< "$count_log") $(head -3 "$WORK/count.err")"
fi

# 名前に日時が入っているので、名前の降順が新しい順になる
ls -1 "$BACKUP_DIR"/monitor-*.zip | sort -r | tail -n +$((KEEP + 1)) | xargs -r rm -f

echo "バックアップしました: ${OUT#"$ROOT"/}（戻せることを確認: 利用者 $users 人）"
