#!/usr/bin/env bash
#
# YouTube Live Monitor サービス管理コマンド
# 使い方: bin/service.sh {start|stop|restart|rollback|status}
#
# systemd のユーザーユニット（bin/youtube-live-monitor.service）を導入してあれば、
# start・stop・restart・rollback・status は systemctl --user を呼ぶ（#242）。
# 導入していなければ、今までどおり nohup で起動する。どちらでも同じ手順で使えるよう、
# 呼び方（AGENTS.md・.claude/skills の手順）は変えていない。
# run はユニットの ExecStart 専用（前面で java を動かす）。手で呼ぶものではない。
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
UNIT="${SERVICE_UNIT:-youtube-live-monitor}.service"

# ユニットが読み込まれていて、その WorkingDirectory がこのディレクトリのときだけ systemd に任せる。
# 名前だけで判定すると、worktree や ../ylm-preview の bin/service.sh が本番のユニットを止めてしまう。
# SERVICE_UNIT は、本番と別の名前の一時ユニットで試すためのもの（普段は指定しない）。
USE_SYSTEMD=0
if command -v systemctl >/dev/null \
        && [[ "$(systemctl --user show -p WorkingDirectory --value "$UNIT" 2>/dev/null)" == "$(pwd)" ]]; then
    USE_SYSTEMD=1
fi

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
    if (( USE_SYSTEMD )); then
        systemctl --user is-active --quiet "$UNIT"
    else
        [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
    fi
}

main_pid() {
    if (( USE_SYSTEMD )); then
        systemctl --user show -p MainPID --value "$UNIT"
    else
        cat "$PID_FILE"
    fi
}

cmd_start() {
    if is_running; then
        echo "既に起動しています (PID: $(main_pid))"
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

java_opts() {
    # ヒープの上限。指定しないと物理メモリの 1/4（この端末で 3.98GB）まで広がる。
    # 実測（2026-09-25、起動 31 分後）は使用 271MB・確保 692MB で、1GB は使用量のおよそ 4 倍の余裕がある。
    # G1PeriodicGCInterval（5 分）は、しばらく GC が無いときにも回して、使っていない確保分を OS へ返させるため
    # （待機中の常駐メモリを減らす）。
    # TrimNativeHeapInterval（5 分）は、ヒープの外の malloc の領域のうち使っていない分を OS へ返させるため。
    # 本番の実測（2026-09-25）ではヒープの外に 250〜290MB あり、G1 がヒープを縮めた後はヒープ（251MB）より
    # 大きかった（#184）。この端末の JDK 21・25 のどちらにもあるフラグ（無い JDK では起動しなくなる）。
    # ExitOnOutOfMemoryError は、OOM の後に一部のスレッドだけ死んだ半端な状態で残らず、落ちて systemd の
    # Restart=on-failure に起こし直させるため（#242。ユニットを使わないときは落ちたままになるが、
    # 半端に動き続けて監視が止まっていることに気付けないよりよい）。
    # 端末を載せ替えたときに変えられるよう、JAVA_OPTS があればそちらを使う。
    echo "${JAVA_OPTS:--Xmx1g -XX:G1PeriodicGCInterval=300000 -XX:TrimNativeHeapInterval=300000 -XX:+ExitOnOutOfMemoryError}"
}

prepare_launch() {
    # .env（API キー・Webhook・初期管理者のパスワード）と DB（利用者ごとの Discord Webhook を平文で持つ）を
    # 同じ端末のほかのユーザーから読めないようにする（#241）。umask 077 は java と子の yt-dlp が作る
    # DB・ログ・録画を 600/700 にするため。既にあるものは作り直されないので chmod で揃える
    # （logs/ はこのスクリプトの先頭の mkdir -p が 775 で作る）。
    # cmd_start ではなくここに置くのは、rollback（cmd_start を通らない）とユニットの run で起動したときにも効かせるため。
    # 録画の保存先（MONITOR_RECORDING_DIRECTORY）の新しいファイルも 600/700 になるので、別のユーザーの
    # プログラムに録画を読ませるなら、ここを緩める必要がある。
    umask 077
    [[ -f .env ]] && chmod 600 .env
    [[ -d data ]] && chmod 700 data
    chmod 700 "$LOG_DIR"
    compgen -G "data/*.db" >/dev/null && chmod 600 data/*.db
    rotate_log
}

# ユニットの ExecStart から呼ぶ。exec で java に置き換わるので、systemd の MainPID が java になる
# （bash が残ると、停止の SIGTERM が java に届かない）。
# jar のコピー（install_jar）はここではしない。systemd が異常終了から起こし直すたびに build/libs の jar を
# 入れると、rollback で戻した版や、ビルドしただけでまだ反映していない版が勝手に動き出すため。
# コピーは start・restart（build/libs から）と rollback（prev.jar から）がユニットを起動する前に行う。
# run/ に jar がまだ無い（初めての起動）ときだけ build/libs から入れる。
cmd_run() {
    [[ -f "$RUN_JAR" ]] || install_jar "$(find_jar)"
    prepare_launch
    # 標準出力は今までどおり logs/service.log に書く（journald に寄せるかはログの保持の Issue で決める）
    MALLOC_ARENA_MAX=2 exec java $(java_opts) -jar "$RUN_JAR" > "$LOG_FILE" 2>&1
}

launch_jar() {
    if (( USE_SYSTEMD )); then
        echo "起動しています... ($RUN_JAR、$UNIT)"
        # 起こし直しの上限（StartLimitBurst）に達したユニットは、手で start しても断られる。直してから
        # 手で起動するときは必ず試させたいので、その記録を消しておく
        systemctl --user reset-failed "$UNIT" 2>/dev/null || true
        systemctl --user start "$UNIT"
    else
        prepare_launch
        echo "起動しています... ($RUN_JAR)"
        # MALLOC_ARENA_MAX=2 は、glibc の malloc のアリーナ（スレッドが取り合わないよう分けた確保領域）の数を絞るため。
        # 既定の上限は 8 × コア数（この端末で 64）で、本番では 64MB 境界の匿名領域（アリーナ）が 66 個・252MB
        # あった（#184）。java の起動にだけ付ける（JAVA_OPTS を指定しても付く。cmd_run も同じ）。
        # 子プロセスの yt-dlp・ffmpeg にも引き継がれるが、アリーナが減るだけで困ることは無い。
        # 複数のオプションを空白で区切って渡せるよう、java_opts はクォートせずに展開する
        MALLOC_ARENA_MAX=2 nohup java $(java_opts) -jar "$RUN_JAR" > "$LOG_FILE" 2>&1 &
        echo $! > "$PID_FILE"
    fi

    for _ in $(seq 1 "$START_TIMEOUT"); do
        if port_in_use "$PORT"; then
            echo "起動完了 (PID: $(main_pid), ポート: $PORT)"
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
    pid="$(main_pid)"
    echo "停止しています (PID: $pid)..."
    if (( USE_SYSTEMD )); then
        # 止まるまで戻らない。TimeoutStopSec（STOP_TIMEOUT と同じ 30 秒）を過ぎれば systemd が java だけを
        # kill -9 する（KillMode=process なので録画中の yt-dlp は残る。理由はユニットファイルのコメント）
        systemctl --user stop "$UNIT"
        echo "停止完了（ポート $PORT も解放されました）"
        return 0
    fi
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
        echo "起動中 (PID: $(main_pid), ポート: $PORT)$( (( USE_SYSTEMD )) && echo "、systemd: $UNIT")"
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
    run)     cmd_run ;;
    *)
        echo "使い方: $0 {start|stop|restart|rollback|status}"
        exit 1
        ;;
esac
