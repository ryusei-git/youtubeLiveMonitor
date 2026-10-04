#!/usr/bin/env bash
#
# 利用者の画面（/my 以下とログイン・登録・パスワード再設定）を PC と iPhone の大きさで撮り、PC の画像の差を数える。
# 使い方:
#   bin/ui-snapshot.sh shoot <出力先>        # 起動中の確認用インスタンス（bin/sandbox.sh start）を撮る
#   bin/ui-snapshot.sh diff <前> <後>        # PC の画像に 1 ピクセルでも差があれば終了コード 1
#
# 変更が PC の画面を変えていないことを確かめる手順（親 Issue #785 の方針 3）:
#   1. PR のベースブランチの作業ツリーで ./gradlew clean build → bin/sandbox.sh start → bin/ui-snapshot.sh shoot /tmp/before
#      → bin/sandbox.sh stop
#   2. 変更した作業ツリーで同じく build → start → bin/ui-snapshot.sh shoot /tmp/after → stop
#   3. bin/ui-snapshot.sh diff /tmp/before /tmp/after
#
# 【この形にした理由】
#   - 撮る相手は bin/sandbox.sh の確認用インスタンスだけにする。DB が毎回新しく監視も Discord も動かないので、
#     表示用のデータ（seed.sql）を入れても本番に触れず、変更の前後で同じデータの画面を撮れる
#     （起動・停止は bin/sandbox.sh に任せ、ここでは起動中かを確かめるだけ）。ポートは SANDBOX_PORT（bin/sandbox.sh と同じ）
#   - Playwright と pngjs は git に載せず、作業ツリーの外（既定 ~/.cache/youtubeLiveMonitor/ui-snapshot、UI_SNAPSHOT_DEPS で変更可）
#     に入れる。Claude の作業は Issue ごとに worktree を作っては消すので、作業ツリーの中に入れると毎回入れ直しになる。
#     ブラウザ本体は Playwright の既定の置き場（端末で 1 つ）に入る
#   - 利用者（snapshot）は管理者の API（招待の発行）と登録の API で作る。録画・配信中の動画など作る API の無いものだけ
#     seed.sql を H2 に流す（RunSeed.java。DB のパスワードを引数に出さないため）
#   - 撮るたびに変わるもの（時計・相対時間・アニメーション・外部の画像）は shoot.cjs が止めてから撮る
#
# 途中で失敗したら、その段階（画面なら画面名）を出して終了コード 1。撮れた画像は残す。

set -Eeuo pipefail
# set -e で止まったとき、何もいわずに終わると、どの段階で失敗したか分からない
trap 'echo "エラー: 行 $LINENO の「$BASH_COMMAND」で失敗しました" >&2' ERR
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
SANDBOX="$ROOT/.sandbox"
PORT="${SANDBOX_PORT:-18180}"
BASE="http://localhost:$PORT"
TOOL_DIR="$ROOT/bin/ui-snapshot"
DEPS="${UI_SNAPSHOT_DEPS:-$HOME/.cache/youtubeLiveMonitor/ui-snapshot}"
# 版を固定する。版が変わるとブラウザの描画が変わり、変更の前後で同じ版を使っていても次に撮った画像と比べられなくなる。
# 上げるときは before / after を同じ版で撮り直す
PLAYWRIGHT_VERSION=1.63.0
PNGJS_VERSION=7.0.0
# 撮るための利用者。確認用インスタンスにだけ作る（DB は start のたびに作り直される）
SNAPSHOT_USER=snapshot
SNAPSHOT_PASSWORD=ui-snapshot-password

usage() {
    echo "使い方: bin/ui-snapshot.sh shoot <出力先> | diff <前> <後>" >&2
    exit 1
}

fail() {
    echo "エラー: $*" >&2
    exit 1
}

# Windows（Git Bash）の java と node は /c/... の形のパスを読めないので C:/... に直す。Linux ではそのまま
native_path() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi
}

ensure_deps() {
    command -v node >/dev/null 2>&1 || fail "node がありません（Node.js 18 以降を入れてください）"
    local marker="$DEPS/.installed-playwright-$PLAYWRIGHT_VERSION-pngjs-$PNGJS_VERSION"
    if [[ ! -f "$marker" ]]; then
        echo "Playwright $PLAYWRIGHT_VERSION と pngjs $PNGJS_VERSION を $DEPS に入れます（初回だけ）"
        mkdir -p "$DEPS"
        [[ -f "$DEPS/package.json" ]] || echo '{"private": true}' > "$DEPS/package.json"
        (cd "$DEPS" && npm install --no-audit --no-fund --save-exact \
            "playwright@$PLAYWRIGHT_VERSION" "pngjs@$PNGJS_VERSION") || fail "npm install に失敗しました"
        rm -f "$DEPS"/.installed-*
        touch "$marker"
    fi
    export NODE_PATH
    NODE_PATH="$(native_path "$DEPS/node_modules")"
}

ensure_browsers() {
    # 入っていれば何もせずに終わる（版ごとのブラウザが Playwright の既定の置き場にあるかを見るだけ）。
    # iPhone も Chromium で撮るとき（UI_SNAPSHOT_IPHONE_ENGINE=chromium。理由は shoot.cjs の IPHONE_ENGINE）は WebKit を入れない
    local engines=(chromium)
    [[ "${UI_SNAPSHOT_IPHONE_ENGINE:-webkit}" == "chromium" ]] || engines+=(webkit)
    (cd "$DEPS" && node node_modules/playwright/cli.js install "${engines[@]}") \
        || fail "Playwright のブラウザ（${engines[*]}）を入れられませんでした"
}

# 確認用インスタンスの API を管理者として叩く（bin/api.sh に任せる。ログインと CSRF の手順を複製しない）
admin_api() {
    ENV_FILE=.sandbox/.env SERVER_PORT="$PORT" bin/api.sh "$@"
}

# 標準入力の JSON から値を取り出す（jq は Git Bash に無いので node で読む）。$1 は値を返す JS の式（入力は d）
json() {
    node -e 'let s="";process.stdin.on("data",c=>s+=c).on("end",()=>{const d=JSON.parse(s);const v=('"$1"');process.stdout.write(v==null?"":String(v));});'
}

# 覚え書き（label）は英数字だけにする。Windows（Git Bash）では curl の引数に日本語を渡すと文字コードが崩れ、
# サーバーが JSON を読めずに 400 を返す（bin/api.sh 経由のすべての本文で同じ）
issue_invitation() {
    admin_api POST /api/admin/invitations "{\"label\":\"ui-snapshot: $1\",\"validDays\":1}" | json 'd.token'
}

snapshot_user_id() {
    admin_api GET /api/admin/users | json "(d.find(u => u.username === '$SNAPSHOT_USER') || {}).id"
}

# 招待を発行して、登録の API（画面の register.html が使うものと同じ）で利用者を作る
register_snapshot_user() {
    local token cookie csrf
    token="$(issue_invitation "create user")"
    [[ -n "$token" ]] || fail "招待を発行できませんでした"
    cookie="$(mktemp)"
    curl -sc "$cookie" -o /dev/null "$BASE/userLogin.html"
    csrf="$(awk '/XSRF-TOKEN/ {print $7}' "$cookie")"
    if ! curl -sb "$cookie" -c "$cookie" --fail-with-body -o /dev/null -X POST "$BASE/api/registration" \
            -H "X-XSRF-TOKEN: $csrf" -H "Content-Type: application/json" \
            -d "{\"token\":\"$token\",\"username\":\"$SNAPSHOT_USER\",\"password\":\"$SNAPSHOT_PASSWORD\"}"; then
        rm -f "$cookie"
        fail "利用者 $SNAPSHOT_USER を登録できませんでした"
    fi
    rm -f "$cookie"
    echo "利用者 $SNAPSHOT_USER を作りました"
}

seed_database() {
    local h2="$SANDBOX/ui-snapshot/h2.jar"
    # 起動中の jar から H2 を取り出す（アプリが DB を作った H2 と同じ版で書くため）。jar が新しくなったら取り直す
    if [[ ! -f "$h2" || "$SANDBOX/youtubeLiveMonitor.jar" -nt "$h2" ]]; then
        mkdir -p "$SANDBOX/ui-snapshot"
        local entry
        entry="$(jar tf "$SANDBOX/youtubeLiveMonitor.jar" | grep -E '^BOOT-INF/lib/h2-[0-9.]+\.jar$' | head -n 1)" \
            || fail "jar の中に H2 が見つかりません"
        (cd "$SANDBOX/ui-snapshot" && jar xf "$SANDBOX/youtubeLiveMonitor.jar" "$entry" && mv -f "$entry" h2.jar \
            && rm -rf BOOT-INF) || fail "H2 を取り出せませんでした"
    fi
    # AUTO_SERVER=TRUE なので、起動中のアプリが開いている DB にも別のプロセスからつなげる（CLI と同じ）
    local url="jdbc:h2:file:$(native_path "$SANDBOX/data/monitor");AUTO_SERVER=TRUE"
    (set -a; . "$SANDBOX/.env"; set +a
     java -cp "$(native_path "$h2")" "$(native_path "$TOOL_DIR/RunSeed.java")" "$url" \
        "$(native_path "$TOOL_DIR/seed.sql")") || fail "表示用のデータ（seed.sql）を DB に入れられませんでした"
}

cmd_shoot() {
    local out="${1:-}"
    [[ -n "$out" ]] || usage
    [[ -f "$SANDBOX/.env" && -f "$SANDBOX/youtubeLiveMonitor.jar" ]] \
        || fail "確認用インスタンスがありません。先に ./gradlew clean build と bin/sandbox.sh start を実行してください"
    curl -sf -o /dev/null --max-time 5 "$BASE/adminLogin.html" \
        || fail "$BASE に確認用インスタンスが応答しません（bin/sandbox.sh start。ポートを変えたなら SANDBOX_PORT も付ける）"
    ensure_deps
    ensure_browsers

    echo "表示用のデータを用意しています"
    local user_id
    user_id="$(snapshot_user_id)"
    if [[ -z "$user_id" ]]; then
        register_snapshot_user
        user_id="$(snapshot_user_id)"
        [[ -n "$user_id" ]] || fail "作った利用者 $SNAPSHOT_USER が一覧に見つかりません"
    fi
    seed_database
    # 登録・パスワード再設定の画面は、使える token が無いと「このリンクからは〜できません」しか出ないので、撮るたびに発行する
    # （画面を開くだけでは token は使われない。パスワードの再設定を発行しても今のパスワードは変わらない）
    local register_token reset_token
    register_token="$(issue_invitation "register screen")"
    reset_token="$(admin_api POST "/api/admin/users/$user_id/password-reset" | json 'd.token')"
    [[ -n "$register_token" && -n "$reset_token" ]] || fail "登録・再設定の画面に使う token を発行できませんでした"

    mkdir -p "$out"
    UI_SNAPSHOT_BASE="$BASE" UI_SNAPSHOT_USER="$SNAPSHOT_USER" UI_SNAPSHOT_PASSWORD="$SNAPSHOT_PASSWORD" \
        UI_SNAPSHOT_REGISTER_TOKEN="$register_token" UI_SNAPSHOT_RESET_TOKEN="$reset_token" \
        exec node "$(native_path "$TOOL_DIR/shoot.cjs")" "$(native_path "$out")"
}

cmd_diff() {
    local before="${1:-}" after="${2:-}"
    [[ -n "$before" && -n "$after" ]] || usage
    [[ -d "$before" ]] || fail "$before がありません"
    [[ -d "$after" ]] || fail "$after がありません"
    ensure_deps
    exec node "$(native_path "$TOOL_DIR/diff.cjs")" "$(native_path "$before")" "$(native_path "$after")"
}

case "${1:-}" in
    shoot) shift; cmd_shoot "$@" ;;
    diff)  shift; cmd_diff "$@" ;;
    *)     usage ;;
esac
