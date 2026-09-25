#!/usr/bin/env bash
#
# リーダー（Claude）のセッションを始めるときに、続きの作業に必要な状態をまとめて出す。
# 使い方: bin/lead-status.sh
#
# リーダーのセッションはいつ捨ててもよい前提で運用している。状態の正本は GitHub と
# 作業ツリーにしか無いので、新しいセッションはこれを 1 回実行すれば続きから始められる。

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
gh project item-list 4 --owner ryusei-git --format json --limit 200 2>/dev/null | python3 -c '
import json, sys
items = json.load(sys.stdin)["items"]
rows = [i for i in items
        if i.get("status") in ("Backlog", "Ready") and i.get("content", {}).get("type") == "Issue"]
rows.sort(key=lambda i: (i.get("priority") or "P9", i["content"]["number"]))
for i in rows[:10]:
    print(i.get("priority") or "-", "#%d" % i["content"]["number"], i["title"])
'

echo
echo "== エージェントのターミナル =="
if out=$(orca-ide terminal list --json 2>/dev/null); then
    echo "$out" | python3 -c '
import json, sys
for t in json.load(sys.stdin)["result"]["terminals"]:
    print(t.get("agentIdentity") or "-", t.get("handle") or "-", (t.get("branch") or "").removeprefix("refs/heads/"), "|", (t.get("title") or "")[:50])
'
else
    echo "(orca に接続できません)"
fi
