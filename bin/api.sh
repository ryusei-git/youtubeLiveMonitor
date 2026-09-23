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

# トークンを載せた Cookie を発行させてからログインする
curl -sc "$JAR" -o /dev/null "$BASE/login.html"
curl -sc "$JAR" -b "$JAR" -o /dev/null -X POST "$BASE/api/auth/login" \
    --data-urlencode "username=$ADMIN_USERNAME" \
    --data-urlencode "password=$ADMIN_PASSWORD" \
    --data-urlencode "_csrf=$(csrf)"

# ログイン後はトークンが変わる
if [ -n "$BODY" ]; then
    curl -sc "$JAR" -b "$JAR" -X "$METHOD" "$BASE$API_PATH" \
        -H "X-XSRF-TOKEN: $(csrf)" -H "Content-Type: application/json" -d "$BODY"
else
    curl -sc "$JAR" -b "$JAR" -X "$METHOD" "$BASE$API_PATH" -H "X-XSRF-TOKEN: $(csrf)"
fi
