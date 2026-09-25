#!/usr/bin/env bash
#
# YouTube Live Monitor サービス管理コマンド
# 使い方: bin/service.sh {start|stop|restart|rollback|status}
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
RUN_JAR="$PID_DIR/youtubeLiveMonitor.jar"
PREV_JAR="$PID_DIR/youtubeLiveMonitor.prev.jar"
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
    jar="$(find_jar)" || return 1
    install_jar "$jar"
    launch_jar
}

# 見つからないときのエラー文は標準エラーへ出す。restart は jar の有無だけを確かめるために
# 標準出力を捨てて呼ぶので、標準出力に出すと理由が表示されないまま終わってしまう。
find_jar() {
    local jar
    jar="$(ls build/libs/*.jar 2>/dev/null | head -1)"
    if [[ -z "$jar" ]]; then
        echo "エラー: build/libs/*.jar が見つかりません。先に ./gradlew build を実行してください。" >&2
        return 1
    fi
    echo "$jar"
}

install_jar() {
    local jar="$1"
    # build/libs の jar を直接動かさず、コピーを動かす。
    #
    # 実行中の JVM は jar から必要になった時点でクラスを読む。build/libs の jar を動かしていると、
    # 稼働中の ./gradlew build に上書きされて、まだ読んでいないクラスを読めなくなる。反映（build → restart）の
    # たびに、止める途中で GracefulShutdownCallback や H2 の終了処理のクラスを読めず（NoClassDefFoundError）、
    # 正常終了の待ちを使い切って kill -9 になっていた（2026-09-25、docs/pitfalls.md）。
    # コピーなら build は触れない。restart は stop → start なので、上書きは必ず前のプロセスが止まった後になる。
    # 名前に youtubeLiveMonitor を残すのは、cmd_start の案内の pgrep -fla youtubeLiveMonitor で見つかるようにするため。
    #
    # 上書きする前に、動かしていた版を prev.jar へ退避する（rollback で戻す先）。新しい版が起動しないときに
    # 前の版へ戻せないと、直すまでサービスが止まったままになる。中身が同じときに退避しないのは、
    # 同じビルドのまま 2 回 restart したときに prev.jar が今の版で上書きされ、戻す先が失われるため。
    if [[ -f "$RUN_JAR" ]] && ! cmp -s "$jar" "$RUN_JAR"; then
        mv "$RUN_JAR" "$PREV_JAR"
    fi
    echo "$jar を $RUN_JAR にコピーします"
    cp "$jar" "$RUN_JAR"
}

launch_jar() {
    # ヒープの上限。指定しないと物理メモリの 1/4（この端末で 3.98GB）まで広がる。
    # 実測（2026-09-25、起動 31 分後）は使用 271MB・確保 692MB で、1GB は使用量のおよそ 4 倍の余裕がある。
    # G1PeriodicGCInterval（5 分）は、しばらく GC が無いときにも回して、使っていない確保分を OS へ返させるため
    # （待機中の常駐メモリを減らす）。
    # TrimNativeHeapInterval（5 分）は、ヒープの外の malloc の領域のうち使っていない分を OS へ返させるため。
    # 本番の実測（2026-09-25）ではヒープの外に 250〜290MB あり、G1 がヒープを縮めた後はヒープ（251MB）より
    # 大きかった（#184）。この端末の JDK 21・25 のどちらにもあるフラグ（無い JDK では起動しなくなる）。
    # 端末を載せ替えたときに変えられるよう、JAVA_OPTS があればそちらを使う。
    local java_opts="${JAVA_OPTS:--Xmx1g -XX:G1PeriodicGCInterval=300000 -XX:TrimNativeHeapInterval=300000}"

    echo "起動しています... ($RUN_JAR)"
    rotate_log
    # MALLOC_ARENA_MAX=2 は、glibc の malloc のアリーナ（スレッドが取り合わないよう分けた確保領域）の数を絞るため。
    # 既定の上限は 8 × コア数（この端末で 64）で、本番では 64MB 境界の匿名領域（アリーナ）が 66 個・252MB
    # あった（#184）。java の起動にだけ付ける（JAVA_OPTS を指定しても付く）。
    # 子プロセスの yt-dlp・ffmpeg にも引き継がれるが、アリーナが減るだけで困ることは無い。
    # 複数のオプションを空白で区切って渡せるよう、java_opts はクォートせずに展開する
    MALLOC_ARENA_MAX=2 nohup java $java_opts -jar "$RUN_JAR" > "$LOG_FILE" 2>&1 &
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

# 前の版へ戻す。install_jar を通さないのは、build/libs の jar（戻したい新しい版）で上書きしないため。
# 戻した後は prev.jar が無くなるので、続けて rollback しても 2 つ前の版には戻らない。
cmd_rollback() {
    if [[ ! -f "$PREV_JAR" ]]; then
        echo "戻す版がありません（$PREV_JAR が無い）"
        return 1
    fi
    cmd_stop
    mv "$PREV_JAR" "$RUN_JAR"
    echo "前の版に戻しました"
    launch_jar
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
    # jar が無いまま止めると、start が失敗してサービスが落ちたままになる。止める前に確かめる
    restart) find_jar >/dev/null && { cmd_stop; cmd_start; } ;;
    rollback) cmd_rollback ;;
    status)  cmd_status ;;
    *)
        echo "使い方: $0 {start|stop|restart|rollback|status}"
        exit 1
        ;;
esac
