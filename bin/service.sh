#!/usr/bin/env bash
#
# YouTube Live Monitor サービス管理コマンド
# 使い方: bin/service.sh {start|stop|restart|status}
#
# stop は「プロセスが完全に終了してポートが解放されるまで待つ」ことを保証する。
# これをせずに次の start を試みると、JVMのシャットダウン処理（Tomcat/Hikari等の
# クローズ）が終わる前にポートを奪い合い、BindException で起動失敗する。

set -euo pipefail
cd "$(dirname "$0")/.."

PID_DIR="run"
PID_FILE="$PID_DIR/app.pid"
LOG_DIR="logs"
LOG_FILE="$LOG_DIR/service.log"
PORT="${SERVER_PORT:-8080}"
START_TIMEOUT=30
STOP_TIMEOUT=30

mkdir -p "$PID_DIR" "$LOG_DIR"

# 起動のたびにログを退避する。
#
# 以前は起動時に "> $LOG_FILE" で上書きしていたため、再起動した瞬間に前回までの
# ログが丸ごと消えていた。障害の調査で再起動してしまうと原因の手がかりが失われる
# （実際にチャンネル登録ミスによる 404 の警告を、再起動後に追えなくなった）。
# 世代数を絞っているのは、長時間稼働でディスクを食い潰さないため。
LOG_GENERATIONS=5

rotate_log() {
    [[ -f "$LOG_FILE" ]] || return 0

    rm -f "$LOG_FILE.$LOG_GENERATIONS"
    # set -e の下では "[[ ... ]] && mv" が偽のときスクリプトごと止まるので if で書く
    for (( generation = LOG_GENERATIONS - 1; generation >= 1; generation-- )); do
        if [[ -f "$LOG_FILE.$generation" ]]; then
            mv "$LOG_FILE.$generation" "$LOG_FILE.$(( generation + 1 ))"
        fi
    done
    mv "$LOG_FILE" "$LOG_FILE.1"
}

port_in_use() {
    (echo > "/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1
}

is_running() {
    [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

cmd_start() {
    if is_running; then
        echo "既に起動しています (PID: $(cat "$PID_FILE"))"
        return 0
    fi

    if port_in_use "$PORT"; then
        echo "エラー: ポート $PORT は本スクリプト管理外の別プロセスに使用されています。"
        echo "以下で確認してください:"
        echo "  bin/service.sh status"
        echo "  pgrep -fla youtubeLiveMonitor"
        return 1
    fi

    local jar
    jar="$(ls build/libs/*.jar 2>/dev/null | head -1)"
    if [[ -z "$jar" ]]; then
        echo "エラー: build/libs/*.jar が見つかりません。先に ./gradlew build を実行してください。"
        return 1
    fi

    echo "起動しています... ($jar)"
    rotate_log
    nohup java -jar "$jar" > "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"

    for _ in $(seq 1 "$START_TIMEOUT"); do
        if port_in_use "$PORT"; then
            echo "起動完了 (PID: $(cat "$PID_FILE"), ポート: $PORT)"
            return 0
        fi
        if ! is_running; then
            echo "起動に失敗しました。ログを確認してください: $LOG_FILE"
            rm -f "$PID_FILE"
            return 1
        fi
        sleep 1
    done

    echo "警告: ${START_TIMEOUT}秒待ってもポート $PORT が開きませんでした。ログを確認してください: $LOG_FILE"
    return 1
}

cmd_stop() {
    if ! is_running; then
        echo "起動していません"
        rm -f "$PID_FILE"
        return 0
    fi

    local pid
    pid="$(cat "$PID_FILE")"
    echo "停止しています (PID: $pid)..."
    kill "$pid"

    for _ in $(seq 1 "$STOP_TIMEOUT"); do
        if ! kill -0 "$pid" 2>/dev/null; then
            rm -f "$PID_FILE"
            echo "停止完了（ポート $PORT も解放されました）"
            return 0
        fi
        sleep 1
    done

    echo "正常終了しなかったため強制終了します (kill -9)"
    kill -9 "$pid" 2>/dev/null || true
    rm -f "$PID_FILE"
    echo "強制終了しました"
}

cmd_status() {
    if is_running; then
        echo "起動中 (PID: $(cat "$PID_FILE"), ポート: $PORT)"
    else
        echo "停止中"
        if port_in_use "$PORT"; then
            echo "注意: ポート $PORT は本スクリプト管理外の別プロセスに使用されています"
        fi
    fi
}

case "${1:-}" in
    start)   cmd_start ;;
    stop)    cmd_stop ;;
    restart) cmd_stop; cmd_start ;;
    status)  cmd_status ;;
    *)
        echo "使い方: $0 {start|stop|restart|status}"
        exit 1
        ;;
esac
