#!/usr/bin/env bash
#
# 作業ツリーのビルドを「確認用インスタンス」として起動する（新しい DB・監視なし・Discord なし・ポート 18180）。
# 使い方: bin/sandbox.sh start | stop | status | cli <CLI の引数...>
#   bin/sandbox.sh start                                                   # ビルドの後に。DB は毎回新しくなる
#   ENV_FILE=.sandbox/.env SERVER_PORT=18180 bin/api.sh GET /api/channels  # 管理者として API を叩く
#   bin/sandbox.sh cli channel list                                        # 確認用の DB で CLI を動かす
#   bin/sandbox.sh stop
#
# 【この形にした理由】
# 完了条件の確認（AGENTS.md「終わったら」）は、共有ディレクトリで動く本番（bin/service.sh restart と .env）を
# 前提にしていた。worktree（Claude の実装・PR のレビュー）や本番の無い作業端末では、restart は 8080 の本番と
# ぶつかるか DB の無い作業ツリーで起動してしまい、bin/api.sh は .env が無くて止まる。
# bin/preview.sh は本番の DB を複製して origin/main を動かすもので、マージ前の作業ツリーは動かせない。
#   - 置き場所はこの作業ツリーの .sandbox/（中に「*」だけの .gitignore を置き、git に載せない）。
#     java を .sandbox/ で動かすので、アプリが作業ディレクトリからの相対で決めている場所
#     （.env・data/ の H2 と remember-me の鍵・logs/・recordings/）がすべて .sandbox/ の下になり、
#     作業ツリーの直下や本番のファイルには触れない。画面の「設定」の保存先も .sandbox/.env になる
#   - start のたびに data/・logs/・recordings/ を消して作り直す。確認の結果が前の確認の残りに左右されないため。
#     また Windows（Git Bash）の kill は java を強制終了して終了処理が走らないので、DB を持ち越すと壊れた DB を開きうる
#   - .sandbox/.env は無いときだけ作り、以後は残す（確認のために書き足した値が、起動し直しても効くように）。
#     作るときに管理者と DB のパスワードをその場で決める。API キー・Webhook は書かない
#     （本番のクォータを使わず、Discord に送らない。要る確認のときだけ手で書き足す）
#   - 監視・動画収集は -Dmonitor.scheduling.enabled=false で止める（既定で連動する耳キスの検出・新人発掘も止まる）
#   - build/libs の jar はコピーして動かす（docs/pitfalls.md「build/libs の jar を直接動かすと…」）
#
# .sandbox/.env を書き換えた・ビルドし直したときは stop → start で起動し直す。
# 呼び出したときの環境変数はそのまま java に渡る（例: MONITOR_SOUND_DETECTION_ENABLED=true bin/sandbox.sh start）。
# ポートは SANDBOX_PORT で変えられる（既定 18180。本番の 8080・bin/preview.sh の 18080 と重ならない）。
# 変えたときは bin/api.sh にも SERVER_PORT=<同じ番号> を渡す。
# 停止は 30 秒待っても止まらなければ kill -9 する（bin/service.sh と同じ）。Windows（Git Bash）の kill は待たずに強制終了になる。

set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
SANDBOX="$ROOT/.sandbox"
PORT="${SANDBOX_PORT:-18180}"
PID_FILE="$SANDBOX/sandbox.pid"
JAR="$SANDBOX/youtubeLiveMonitor.jar"
LOG_FILE="$SANDBOX/logs/service.log"

# PID ファイルの PID が、今もこの確認用インスタンスの java か。PID が生きているかだけで決めると、java が
# 落ちた後（端末の再起動など）に同じ PID を得た別のプロセス（本番の java のこともある）を stop で止めてしまう。
# /proc/<PID>/cmdline は Linux にも Git Bash にもある（Git Bash では jar のパスが C:/... の形になるので、末尾だけで比べる）
is_running() {
    [[ -f "$PID_FILE" ]] || return 1
    local pid
    pid="$(cat "$PID_FILE")"
    kill -0 "$pid" 2>/dev/null && grep -qF '.sandbox/youtubeLiveMonitor.jar' "/proc/$pid/cmdline" 2>/dev/null
}

port_in_use() {
    (echo > "/dev/tcp/127.0.0.1/$PORT") >/dev/null 2>&1
}

cmd_stop() {
    if ! is_running; then
        rm -f "$PID_FILE"
        echo "確認用インスタンスは起動していません"
        return 0
    fi
    local pid
    pid="$(cat "$PID_FILE")"
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 30); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
    done
    if kill -0 "$pid" 2>/dev/null; then
        echo "30 秒待っても止まらないため強制終了します (kill -9)"
        kill -9 "$pid" 2>/dev/null || true
        # 消えるまで待つ。Windows はプロセスが消えるまで、開いていたファイルを消せない
        for _ in $(seq 1 10); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 1
        done
    fi
    rm -f "$PID_FILE"
    echo "確認用インスタンスを停止しました"
}

write_env() {
    [[ -f "$SANDBOX/.env" ]] && return 0
    # 乱数は od で読む量を決めて取る（tr < /dev/urandom | head -c は、pipefail の下で tr の SIGPIPE が失敗扱いになる）
    local admin_password db_password
    admin_password="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
    db_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
    cat > "$SANDBOX/.env" <<EOF
# 確認用インスタンス（bin/sandbox.sh）の設定。本番の .env とは別物で、本番の値（API キー・Webhook）は写さない。
# 画面の「設定」から保存した値もここに書かれる。書き換えたら bin/sandbox.sh stop → start で起動し直す。
ADMIN_USERNAME=admin
ADMIN_PASSWORD=$admin_password
SPRING_DATASOURCE_PASSWORD=$db_password
EOF
    echo ".sandbox/.env を作りました（管理者は admin。パスワードはこのファイルにあります）"
}

cmd_start() {
    if is_running; then
        cmd_stop
    fi
    if port_in_use; then
        echo "エラー: ポート $PORT を別のプロセスが使っています。" >&2
        echo "  別の作業ツリーの確認用インスタンスなら、そのディレクトリで bin/sandbox.sh stop を実行する" >&2
        echo "  別のポートで起動するなら SANDBOX_PORT=$((PORT + 1)) bin/sandbox.sh start（bin/api.sh には SERVER_PORT=$((PORT + 1))）" >&2
        return 1
    fi
    local jars=(build/libs/*.jar)
    if [[ ! -f "${jars[0]}" ]]; then
        echo "エラー: build/libs/*.jar がありません。先に ./gradlew build を実行してください" >&2
        return 1
    fi
    # 名前の違う jar が残っていると、名前順で古いビルドを選んで確かめてしまう（jar の名前は作業ツリーの
    # ディレクトリ名や settings.gradle で変わる。./gradlew build は前の名前の jar を消さない）
    if (( ${#jars[@]} > 1 )); then
        echo "エラー: build/libs に jar が ${#jars[@]} 個あります。./gradlew clean build でビルドし直してください" >&2
        return 1
    fi

    # DB・ログ・.env を同じ端末のほかのユーザーから読めないようにする（bin/service.sh の prepare_launch と同じ理由）
    umask 077
    mkdir -p "$SANDBOX"
    # ルートの .gitignore を書き換えずに済むよう、ディレクトリ自身に「全部無視」の .gitignore を置く
    echo '*' > "$SANDBOX/.gitignore"
    rm -rf "$SANDBOX/data" "$SANDBOX/logs" "$SANDBOX/recordings"
    mkdir -p "$SANDBOX/data" "$SANDBOX/logs" "$SANDBOX/recordings"
    write_env
    cp "${jars[0]}" "$JAR"

    # 引数を 1 つでも付けると CLI モードで起動してしまう（YouTubeLiveMonitorApplication.main）ため、設定は -D で渡す。
    # cd と java を 1 つのかたまりで裏に回すと、記録されるのが途中の bash の PID になり、stop で java を
    # 止められない（bin/preview.sh と同じ）。-Xmx1g は、本番と並べて動かしてもヒープが物理メモリの 1/4 まで
    # 広がらないようにするため（bin/service.sh の既定と同じ上限）
    cd "$SANDBOX"
    nohup java -Xmx1g \
        -Dserver.port="$PORT" \
        -Dmonitor.scheduling.enabled=false \
        -jar "$JAR" > "$LOG_FILE" 2>&1 < /dev/null &
    local pid=$!
    echo "$pid" > "$PID_FILE"
    cd "$ROOT"

    echo -n "起動を待っています"
    for _ in $(seq 1 60); do
        if curl -sf -o /dev/null --max-time 5 "http://127.0.0.1:$PORT/adminLogin.html"; then
            echo
            echo "確認用インスタンスを起動しました: http://localhost:$PORT/adminLogin.html"
            echo "  管理者: .sandbox/.env の ADMIN_USERNAME / ADMIN_PASSWORD"
            echo "  API: ENV_FILE=.sandbox/.env SERVER_PORT=$PORT bin/api.sh <METHOD> <PATH>"
            echo "  CLI: bin/sandbox.sh cli <引数>   ログ: .sandbox/logs/   止める: bin/sandbox.sh stop"
            return 0
        fi
        # 起動直後は is_running を使わない（java に置き換わる前は /proc/<PID>/cmdline がまだ bash のもので、
        # 起動に失敗したと取り違える）。今起動した PID が生きているかだけを見る
        if ! kill -0 "$pid" 2>/dev/null; then
            echo
            echo "起動に失敗しました。.sandbox/logs/service.log の末尾:" >&2
            tail -n 30 "$LOG_FILE" >&2
            rm -f "$PID_FILE"
            return 1
        fi
        echo -n "."
        sleep 2
    done
    echo
    echo "120 秒待っても応答がありません。.sandbox/logs/service.log を見てください（止めるなら bin/sandbox.sh stop）" >&2
    return 1
}

cmd_status() {
    if is_running; then
        echo "起動中 (PID: $(cat "$PID_FILE"))"
    else
        echo "停止中"
    fi
}

cmd_cli() {
    if [[ ! -f "$JAR" || ! -f "$SANDBOX/.env" ]]; then
        echo "エラー: 先に bin/sandbox.sh start を実行してください" >&2
        return 1
    fi
    # .sandbox/ で動かすので、確認用インスタンスと同じ .env と DB（起動中なら AUTO_SERVER 経由）を使う。
    # stdout.encoding は、Windows（Git Bash）で日本語の出力が化けないようにするため
    # （指定しないと端末のコードページ＝Shift_JIS で書き出す。Linux は元から UTF-8 なので変わらない）
    cd "$SANDBOX"
    java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$JAR" "$@"
}

case "${1:-}" in
    start)  cmd_start ;;
    stop)   cmd_stop ;;
    status) cmd_status ;;
    cli)    shift; cmd_cli "$@" ;;
    *)
        echo "使い方: bin/sandbox.sh start | stop | status | cli <CLI の引数...>" >&2
        exit 1
        ;;
esac
