#!/usr/bin/env bash
#
# 巡回が止まった・サービスが落ちたことを Discord へ知らせる見張り（#243）。cron から 5 分ごとに呼ぶ。
# 使い方: bin/health-watch.sh
#
# JVM の中からは JVM 自身が止まった・落ちたことを知らせられないので、外から /api/health を叩く。
# 2 回続けて失敗したら 1 度だけ知らせ、戻ったらもう 1 度知らせる。1 回で知らせないのは、
# bin/service.sh restart の数十秒に当たっただけで知らせないため。
# 宛先は .env の DISCORD_WEBHOOK_URL。環境変数 DISCORD_WEBHOOK_URL を（空でも）指定すればそちらを使う（試すとき用）。

set -euo pipefail
cd "$(dirname "$0")/.."

PORT="${SERVER_PORT:-8080}"
STATE_FILE="run/health-watch.failures"
WEBHOOK="${DISCORD_WEBHOOK_URL-$(sed -n 's/^DISCORD_WEBHOOK_URL=//p' .env 2>/dev/null | tail -1)}"
mkdir -p run

notify() {
    [[ -n "$WEBHOOK" ]] || return 0
    curl -s --max-time 10 -H 'Content-Type: application/json' \
        -d "{\"content\":\"$1\"}" "$WEBHOOK" > /dev/null || true
}

failures="$(cat "$STATE_FILE" 2>/dev/null || echo 0)"
if curl -sf --max-time 10 -o /dev/null "http://127.0.0.1:$PORT/api/health"; then
    if (( failures >= 2 )); then
        notify "YouTube Live Monitor: 巡回が戻りました"
    fi
    echo 0 > "$STATE_FILE"
else
    failures=$(( failures + 1 ))
    echo "$failures" > "$STATE_FILE"
    if (( failures == 2 )); then
        notify "YouTube Live Monitor: 巡回が止まっています。bin/service.sh status を確認してください"
    fi
fi
