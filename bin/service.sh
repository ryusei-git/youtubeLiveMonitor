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

    # build/libs の jar を直接動かさず、コピーを動かす。
    #
    # 実行中の JVM は jar から必要になった時点でクラスを読む。build/libs の jar を動かしていると、
    # 稼働中の ./gradlew build に上書きされて、まだ読んでいないクラスを読めなくなる。反映（build → restart）の
    # たびに、止める途中で GracefulShutdownCallback や H2 の終了処理のクラスを読めず（NoClassDefFoundError）、
    # 正常終了の待ちを使い切って kill -9 になっていた（2026-09-25、docs/pitfalls.md）。
    # コピーなら build は触れない。restart は stop → start なので、上書きは必ず前のプロセスが止まった後になる。
    # 名前に youtubeLiveMonitor を残すのは、上の案内の pgrep -fla youtubeLiveMonitor で見つかるようにするため。
    local run_jar="$PID_DIR/youtubeLiveMonitor.jar"
    cp "$jar" "$run_jar"

    # ヒープの上限。指定しないと物理メモリの 1/4（この端末で 3.98GB）まで広がる。
    # 実測（2026-09-25、起動 31 分後）は使用 271MB・確保 692MB で、1GB は使用量のおよそ 4 倍の余裕がある。
    # G1PeriodicGCInterval（5 分）は、しばらく GC が無いときにも回して、使っていない確保分を OS へ返させるため
    # （待機中の常駐メモリを減らす）。端末を載せ替えたときに変えられるよう、JAVA_OPTS があればそちらを使う。
    local java_opts="${JAVA_OPTS:--Xmx1g -XX:G1PeriodicGCInterval=300000}"

    echo "起動しています... ($jar を $run_jar にコピーして起動)"
    rotate_log
    # 複数のオプションを空白で区切って渡せるよう、java_opts はクォートせずに展開する
    nohup java $java_opts -jar "$run_jar" > "$LOG_FILE" 2>&1 &
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
