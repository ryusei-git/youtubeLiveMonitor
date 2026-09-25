#!/usr/bin/env bash
#
# H2 の DB（data/monitor.mv.db）を data/backups/monitor-<日時>.zip へ控え、古いものを消す。
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

set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
KEEP=14
BACKUP_DIR="$ROOT/data/backups"
OUT="$BACKUP_DIR/monitor-$(date +%Y%m%d-%H%M%S).zip"

H2_JAR="$(find ~/.gradle/caches -name 'h2-*.jar' ! -name '*sources*' | sort | tail -1)"
# DB のパスワードは .env から読む（#314）。ほかの値まで環境変数に流さないようサブシェルで読み、
# このキーだけを取り出す。.env やキーが無ければ空（パスワード未設定の DB）
DB_PASSWORD="$(if [[ -f .env ]]; then set -a; . ./.env; set +a; fi; echo "${SPRING_DATASOURCE_PASSWORD:-}")"

mkdir -p "$BACKUP_DIR"
java -cp "$H2_JAR" org.h2.tools.Shell \
    -url "jdbc:h2:file:$ROOT/data/monitor;AUTO_SERVER=TRUE" -user sa -password "$DB_PASSWORD" \
    -sql "BACKUP TO '$OUT'" >/dev/null
# Shell は SQL の失敗でも終了コードが 0 のことがあるので、できたファイルで成否を決める
if [[ ! -s "$OUT" ]]; then
    echo "バックアップに失敗しました: $OUT ができていません" >&2
    exit 1
fi
chmod 600 "$OUT"

# 名前に日時が入っているので、名前の降順が新しい順になる
ls -1 "$BACKUP_DIR"/monitor-*.zip | sort -r | tail -n +$((KEEP + 1)) | xargs -r rm -f

echo "バックアップしました: ${OUT#"$ROOT"/}"
