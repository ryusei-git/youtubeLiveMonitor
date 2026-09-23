#!/usr/bin/env bash
#
# APIお試しタブ（playground）を跡形なく削除する。
#
# お試し機能は「不要になったらすぐ消せること」を前提に作ってあり、
# 削除で既存機能に影響が出ないよう次の形で隔離してある。
#   - Java は playground パッケージに全部入っている（既存クラスを一切変更していない）
#   - 画面は専用の3ファイルだけ（style.css や common.js には何も追記していない）
#   - 既存HTMLへの変更はナビの1行のみで、マーカーで囲ってある
#
# このスクリプトはそれらをまとめて消すだけ。実行後は ./gradlew build で確認すること。

set -euo pipefail
cd "$(dirname "$0")/.."

STATIC_DIR="src/main/resources/static"

echo "APIお試しタブを削除します。"

# 1. Java のパッケージごと削除
if [[ -d src/main/java/com/example/monitor/playground ]]; then
    rm -rf src/main/java/com/example/monitor/playground
    echo "  削除: src/main/java/com/example/monitor/playground/"
fi

if [[ -d src/test/java/com/example/monitor/playground ]]; then
    rm -rf src/test/java/com/example/monitor/playground
    echo "  削除: src/test/java/com/example/monitor/playground/"
fi

# 2. 画面ファイル
for file in "$STATIC_DIR/playground.html" "$STATIC_DIR/js/playground.js" "$STATIC_DIR/css/playground.css"; do
    if [[ -f "$file" ]]; then
        rm "$file"
        echo "  削除: $file"
    fi
done

# 3. 各HTMLのナビからマーカーで囲まれた行を取り除く
#    sed の範囲指定で start〜end の行をまとめて消す
for html in "$STATIC_DIR"/*.html; do
    if grep -q "playground:start" "$html" 2>/dev/null; then
        sed -i '/playground:start/,/playground:end/d' "$html"
        echo "  ナビから除去: $html"
    fi
done

# 4. このスクリプト自身も残す理由が無いので消す
rm -- "$0"
echo "  削除: bin/remove-playground.sh（このスクリプト自身）"

echo
echo "削除しました。次のコマンドで既存機能に影響が無いことを確認してください。"
echo "  ./gradlew clean build && bin/service.sh restart"
