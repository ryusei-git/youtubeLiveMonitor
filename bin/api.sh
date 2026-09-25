#!/usr/bin/env bash
#
# 管理者として認証して API を叩く。
# 使い方: bin/api.sh <METHOD> <PATH> [JSON本文]
#   例: bin/api.sh GET /api/channels
#       bin/api.sh POST /api/monitor/check
#       bin/api.sh POST /api/my/channels '{"platform":"YOUTUBE","channelInput":"..."}'
#
# ログインはフォーム形式（JSON ではない）で、CSRF トークンは Cookie から取って
# 送り返す必要がある。毎回手で組み立てると必ずどこかを間違えるのでここにまとめた。
# .env のパスワードは一切出力しない。

set -euo pipefail
cd "$(dirname "$0")/.."

BASE="http://localhost:${SERVER_PORT:-8080}"
METHOD="${1:?使い方: bin/api.sh <METHOD> <PATH> [JSON本文]}"
API_PATH="${2:?使い方: bin/api.sh <METHOD> <PATH> [JSON本文]}"
BODY="${3:-}"

set -a; . ./.env; set +a

JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

csrf() { awk '/XSRF-TOKEN/ {print $7}' "$JAR"; }

# トークンを載せた Cookie を発行させてからログインする。
# 管理者は portal=admin（管理者用のログイン画面から来た印）が無いとログインを拒まれる（#62）
curl -sc "$JAR" -o /dev/null "$BASE/adminLogin.html"
# ログインは成功も失敗も 302 を返す（失敗は /adminLogin.html?error へ）。応答コードだけでは
# 見分けられないので、3xx 以外（429 の試行制限など）か転送先に error を含むなら失敗とする。
# 失敗のまま本 API を叩くとログイン画面の HTML が返り「API が壊れた」と誤診しやすい。
login_result="$(curl -sc "$JAR" -b "$JAR" -o /dev/null -w '%{http_code} %{redirect_url}' -X POST "$BASE/api/auth/login" \
    --data-urlencode "username=$ADMIN_USERNAME" \
    --data-urlencode "password=$ADMIN_PASSWORD" \
    --data-urlencode "portal=admin" \
    --data-urlencode "_csrf=$(csrf)")"
case "$login_result" in
    3??\ *error*|[!3]*)
        echo "ログインに失敗しました（.env の ADMIN_USERNAME / ADMIN_PASSWORD を確認）: $login_result" >&2
        exit 1 ;;
esac

# ログイン後はトークンが変わる
if [ -n "$BODY" ]; then
    curl -sc "$JAR" -b "$JAR" --fail-with-body -X "$METHOD" "$BASE$API_PATH" \
        -H "X-XSRF-TOKEN: $(csrf)" -H "Content-Type: application/json" -d "$BODY"
else
    curl -sc "$JAR" -b "$JAR" --fail-with-body -X "$METHOD" "$BASE$API_PATH" -H "X-XSRF-TOKEN: $(csrf)"
fi
