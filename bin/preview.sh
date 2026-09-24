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
        java -cp "$H2_JAR" org.h2.tools.Shell \
            -url "jdbc:h2:file:$ROOT/data/monitor;AUTO_SERVER=TRUE" -user sa -password "" \
            -sql "BACKUP TO '$PREVIEW/data/backup.zip'" >/dev/null
        (cd "$PREVIEW/data" && unzip -oq backup.zip && rm backup.zip)

        cp "$ROOT/.env" "$PREVIEW/.env"
        JAR="$(ls "$PREVIEW"/build/libs/*.jar | grep -v plain | head -1)"
        (cd "$PREVIEW" && nohup java -jar "$JAR" \
            --server.port="$PORT" \
            --monitor.scheduling.enabled=false \
            --monitor.discord.webhook-url= \
            --monitor.recording.directory="$ROOT/recordings" \
            > "$PREVIEW/logs/preview.log" 2>&1 & echo $! > "$PID_FILE")

        echo -n "起動を待っています"
        for _ in $(seq 1 60); do
            if curl -s -o /dev/null "http://localhost:$PORT/adminLogin.html"; then
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
