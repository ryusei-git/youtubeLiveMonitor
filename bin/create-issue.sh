#!/usr/bin/env bash
#
# チケット（Issue）を作成し、Project への登録まで確認してから完了とする。
# 使い方: bin/create-issue.sh <title> <body-file> [親issue番号] [P1|P2]
#
# 【この形にした理由】
# gh issue create → gh project item-add → item-edit(Status/Priority) の
# 一連の手順を手で叩くと、出力を /dev/null に捨てて失敗に気づかない
# （実際に発生した：item-add 自体は最初から成功していたのに、確認せず
# 「登録されていない」と誤診し、調査に時間を溶かした）。
# ここでは各段階の直後に「実際にそうなっているか」を別経路で問い合わせて確認する。
#
# 【GitHub 側の遅延に注意】 Project の一覧（item-list、items(first:N)の
# totalCount）は書き込み直後、反映が遅れることがある（実際に確認した：
# 書き込み済みの36件があるのに一覧が14件のまま数分続いた）。
# そのため一覧では確認せず、Issue 自身に「どの Project に属しているか」を
# 問い合わせる（issue.projectItems はこの遅延の影響を受けなかった）。

set -euo pipefail
cd "$(dirname "$0")/.."

REPO="ryusei-git/youtubeLiveMonitor"
PROJECT_NUM=4
PROJECT_ID="PVT_kwHOBB07r84BkZNB"
STATUS_FIELD="PVTSSF_lAHOBB07r84BkZNBzhjJ6nM"
STATUS_BACKLOG="f75ad846"
PRIORITY_FIELD="PVTSSF_lAHOBB07r84BkZNBzhjJ6zg"

TITLE="${1:?使い方: bin/create-issue.sh <title> <body-file> [親issue番号] [P1|P2]}"
BODY_FILE="${2:?本文はファイルで渡す（インラインだとバッククォート等でクォート崩れが起きやすい）}"
PARENT="${3:-}"
PRIORITY="${4:-}"

echo "1) Issue を作成..."
URL=$(gh issue create --repo "$REPO" --title "$TITLE" --body-file "$BODY_FILE")
NUM=$(echo "$URL" | grep -oE '[0-9]+$')
echo "   #$NUM  $URL"

if [ -n "$PARENT" ]; then
    echo "2) #$PARENT の子として登録..."
    CHILD_ID=$(gh api "repos/$REPO/issues/$NUM" --jq '.id')
    if ! gh api --method POST "repos/$REPO/issues/$PARENT/sub_issues" -F sub_issue_id="$CHILD_ID" >/dev/null; then
        echo "   ★失敗：#$PARENT への登録リクエストが失敗しました" \
             "（親が存在しないか、既に別の親に紐づいている可能性）" >&2
        echo "   ★ #$NUM は作成済み。不要なら 'gh issue delete $NUM' で削除する" >&2
        exit 1
    fi
    # 検証：親の子一覧に実際に現れるか（応答の成否だけでなく実体を見る）
    if ! gh api "repos/$REPO/issues/$PARENT/sub_issues" --jq '.[].number' | grep -qx "$NUM"; then
        echo "   ★失敗：#$PARENT の子として登録されていません" >&2
        exit 1
    fi
    echo "   確認OK：#$PARENT の子"
fi

echo "3) Project #$PROJECT_NUM へ登録..."
ITEM_ID=$(gh project item-add "$PROJECT_NUM" --owner ryusei-git --url "$URL")
echo "   item id: $ITEM_ID"

# 検証：Project の一覧ではなく、Issue 自身から「所属しているか」を問い合わせる
# （一覧側は反映が遅れることがあるため、これで確認しないと誤診する）
BELONGS=$(gh api graphql -f query="{ repository(owner:\"ryusei-git\", name:\"youtubeLiveMonitor\") { issue(number:$NUM) { projectItems(first:5) { nodes { project { number } } } } } }" --jq '.data.repository.issue.projectItems.nodes[].project.number')
if ! echo "$BELONGS" | grep -qx "$PROJECT_NUM"; then
    echo "   ★失敗：Project #$PROJECT_NUM に登録されていません（実際の所属: ${BELONGS:-なし}）" >&2
    exit 1
fi
echo "   確認OK：Project #$PROJECT_NUM に所属"

echo "4) Status=Backlog を設定..."
gh project item-edit --id "$ITEM_ID" --project-id "$PROJECT_ID" \
    --field-id "$STATUS_FIELD" --single-select-option-id "$STATUS_BACKLOG" >/dev/null

if [ -n "$PRIORITY" ]; then
    case "$PRIORITY" in
        P1) OPT="0a877460" ;;
        P2) OPT="da944a9c" ;;
        *)  echo "   ★警告：Priority '$PRIORITY' に対応する選択肢が無い（P1/P2のみ。P3は選択肢自体が無い）" >&2
            OPT="" ;;
    esac
    if [ -n "$OPT" ]; then
        gh project item-edit --id "$ITEM_ID" --project-id "$PROJECT_ID" \
            --field-id "$PRIORITY_FIELD" --single-select-option-id "$OPT" >/dev/null
    fi
fi

# 最終検証：設定したフィールドを読み直す（書き込みの戻り値ではなく実体を見る）
ACTUAL=$(gh api graphql -f query="{ node(id: \"$ITEM_ID\") { ... on ProjectV2Item { fieldValues(first: 10) { nodes { ... on ProjectV2ItemFieldSingleSelectValue { name field { ... on ProjectV2SingleSelectField { name } } } } } } } }" --jq '.data.node.fieldValues.nodes[] | select(.field) | "\(.field.name)=\(.name)"' | tr '\n' ' ')

echo ""
echo "完了: #$NUM  $URL"
echo "  フィールド: $ACTUAL"
