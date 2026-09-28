#!/usr/bin/env bash
#
# リーダー（Claude）のセッションを始めるときに、続きの作業に必要な状態をまとめて出す。
# 使い方: bin/lead-status.sh
#
# リーダーのセッションはいつ捨ててもよい前提で運用している。状態の正本は GitHub と
# 作業ツリーにしか無いので、新しいセッションはこれを 1 回実行すれば続きから始められる。
#
# 必要なもの: gh（トークンに project スコープ。AGENTS.md「コマンド」）、node。
# python3 は使わない（Windows の作業端末では Microsoft Store のスタブで動かなかった）。
# node は完了条件の型検査（npx tsc）で既に必須なので、新しい依存にはならない。

set -uo pipefail
cd "$(dirname "$0")/.."
REPO=ryusei-git/youtubeLiveMonitor

echo "== 作業ツリー =="
git status --short --branch
git worktree list

echo
echo "== 担当中の Issue =="
for label in claude codex; do
    gh issue list --repo "$REPO" --label "$label" --state open \
        --json number,title --jq ".[] | \"[$label] #\(.number) \(.title)\""
done

echo
echo "== 未マージの PR =="
gh pr list --repo "$REPO" --state open \
    --json number,title,headRefName --jq '.[] | "PR #\(.number) \(.title) (\(.headRefName))"'

echo
echo "== 次の候補（Backlog / Ready、優先度順） =="
# Project の一覧は書き込み直後に古いことがあるため、ここは目安として使う
# 一覧は Issue が増えると 200 件を超えるので、上限を大きく取ったうえで、読み切れていなければ注意を出す。
# gh の失敗は捨てない（スコープ不足で失敗していても、以前は空の一覧に見えていた）。
CANDIDATES_JQ='
  (if (.totalCount // 0) > (.items | length)
   then "(注意: Project の \(.totalCount) 件のうち \(.items | length) 件しか読めていません。bin/lead-status.sh の --limit を増やしてください)"
   else empty end),
  ([.items[] | select((.status == "Backlog" or .status == "Ready") and .content.type == "Issue")]
   | sort_by(.priority // "P9", .content.number)
   | .[:10][]
   | "\(.priority // "-") #\(.content.number) \(.title)")'
if candidates=$(gh project item-list 4 --owner ryusei-git --format json --limit 1000 --jq "$CANDIDATES_JQ" 2>&1); then
    echo "${candidates:-(Backlog / Ready の Issue はありません)}"
else
    echo "(取得できませんでした)"
    echo "$candidates" | sed 's/^/  /'
    echo "  スコープ不足なら gh auth refresh -s project を実行する（AGENTS.md「コマンド」）"
fi

echo
echo "== エージェントのターミナル =="
if out=$(orca-ide terminal list --json 2>/dev/null); then
    echo "$out" | node -e '
try {
    const terminals = JSON.parse(require("fs").readFileSync(0, "utf8")).result.terminals || [];
    for (const t of terminals) {
        const branch = (t.branch || "").replace(/^refs\/heads\//, "");
        const title = Array.from(t.title || "").slice(0, 50).join("");
        console.log([t.agentIdentity || "-", t.handle || "-", branch, "|", title].join(" "));
    }
} catch (e) {
    console.log("(端末一覧を読めませんでした: " + e.message + ")");
}
'
else
    echo "(orca に接続できません)"
fi
