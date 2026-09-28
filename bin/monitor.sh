#!/usr/bin/env bash
#
# CLI（チャンネルの登録・一覧など）を、どこから呼んでもこのリポジトリの DB に対して実行する。
# 使い方: bin/monitor.sh <コマンド> [引数...]
#   例: bin/monitor.sh channel list
#       bin/monitor.sh channel add -i "https://www.youtube.com/@foo" -n "配信者名"
#       bin/monitor.sh              # 引数なしはコマンドの一覧（--help と同じ）
#
# 【この形にした理由】
# DB の URL（jdbc:h2:file:./data/monitor）と .env は、java を起動した作業ディレクトリを基準に解決される。
# リポジトリ直下以外で java -jar を実行すると、その場所に空の DB を新しく作り、channel add が
# 「登録しました」と成功してしまう（サービスはその登録を知らない）。そのためリポジトリ直下へ移ってから起動する。
#
# jar は稼働中のサービスと同じ run/youtubeLiveMonitor.jar を優先する。CLI も起動時に ddl-auto: update で
# テーブルを自分の版の形へ変えるので、ビルドしただけで再起動していない新しい版で動かすと、
# 稼働中の古い版のサービスと DB の形が食い違いうる。run/ に無い（まだ 1 度も起動していない・worktree）ときだけ
# build/libs の jar を使う。build/libs に前の名前の jar が残っていても取り違えないよう、いちばん新しいものを選ぶ。
#
# 引数が無いときは --help を渡す。jar は引数が 1 つも無いと CLI ではなくサービス（Web・監視・録画）として
# 起動する（YouTubeLiveMonitorApplication.main）ので、使い方を見るつもりで本番の DB に対して監視を始めてしまう。
# stdout.encoding・stderr.encoding は、Windows（Git Bash）で日本語の出力が化けないようにするため
# （指定しないと Shift_JIS で書き出す。Linux は元から UTF-8 なので変わらない。bin/sandbox.sh の cli と同じ）。

set -euo pipefail
cd "$(dirname "$0")/.."

JAR="run/youtubeLiveMonitor.jar"
if [[ ! -f "$JAR" ]]; then
    JAR="$(ls -t build/libs/*.jar 2>/dev/null | head -1 || true)"
    if [[ -z "$JAR" ]]; then
        echo "エラー: run/youtubeLiveMonitor.jar も build/libs/*.jar も見つかりません。先に ./gradlew build を実行してください。" >&2
        exit 1
    fi
fi

if [[ $# -eq 0 ]]; then
    set -- --help
fi

exec java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$JAR" "$@"
