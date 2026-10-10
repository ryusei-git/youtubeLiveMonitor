#!/bin/sh
# イメージの入口（Dockerfile の ENTRYPOINT）。bin/service.sh の run と同じことを、コンテナの中で行う。
#
# - umask 077: DB・ログ・録画・Cookie の写しを本人だけが読めるように作る（bin/service.sh と同じ理由）
# - exec: java を PID 1 の子（compose の init: true なら tini の子）に置き換え、停止の SIGTERM を java に届ける。
#   届かないと Spring の終了処理（H2 を閉じる・検出の印を書く）が走らないまま猶予切れで SIGKILL される
# - "$@": 引数があれば CLI として動く（YouTubeLiveMonitorApplication の main が引数の有無で決める）。
#   稼働中のサービスの DB に CLI を当てるときは、新しいコンテナではなく docker exec で同じコンテナの中から動かす
#   （H2 の AUTO_SERVER はコンテナの中のループバックで待ち受ける）
set -e
umask 077
# JAVA_OPTS は空白で区切って渡す（引用符で囲むと 1 つの引数になって JVM が起動しない）
# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar /app/app.jar "$@"
