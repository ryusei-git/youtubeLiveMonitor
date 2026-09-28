package com.example.monitor.util;

/**
 * 文字列を、DB の列の長さなどの上限に収まるよう切り詰める共通処理。
 *
 * <p>例外のメッセージや外部 API の応答のように長さの決まらない文字列を、長さの決まった列へ入れる箇所が
 * 複数あり、それぞれが {@code substring} を手書きしていたため切り出した（AGENTS.md「コードを書くときの約束」）。
 * 列の長さを超えた値をそのまま渡すと、その行の INSERT・UPDATE ごと失敗し、残したかった記録そのものが失われる。
 */
public final class TextTruncator {

    private TextTruncator() {
    }

    /**
     * 文字列を指定した長さ以下に切り詰める。
     *
     * <p>切る位置がサロゲートペア（絵文字など）の途中に当たるときは、その 1 文字の手前で切る。
     * 片割れだけを残すと画面で文字化けするため。そのため戻り値は {@code maxLength} より 1 短くなることがある。
     *
     * @param value     対象の文字列。{@code null} ならそのまま返す
     * @param maxLength 上限の長さ（{@link String#length()} で数える）。1 以上
     * @return {@code maxLength} 以下の長さの文字列。もともと収まっていれば {@code value} そのもの
     */
    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(maxLength - 1)) ? maxLength - 1 : maxLength;
        return value.substring(0, end);
    }
}
