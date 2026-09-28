package com.example.monitor.util;

/**
 * 利用者が自由に決められる文字列（ログインの利用者名など）を、ログの 1 行に収まる形にしてから書くための部品。
 *
 * <p>ログの書式（{@code logback-spring.xml} の {@code %d [%level] %logger - %msg%n}）は本文の改行をそのまま出す。
 * 外から来た値に改行が入っていると、{@code ChannelLogReader} が本物と区別できない偽の行をログに書き込まれ、
 * 管理者のログ画面に本物として並ぶ（ログインの利用者名で書けることが分かった）。書式とパーサーは対になっていて
 * 変えられない（docs/pitfalls.md「ログ書式を変えるならパーサーも直す」）ので、値の側で潰す。
 *
 * <p>改行などは捨てずに {@code \n} のような見える形に置き換える。捨てると、何を送られたのかが
 * ログから分からなくなり、偽の行を書こうとした試みに気づけないため。
 *
 * <p>長さも切り詰める。ログインのフォームには長さの制限が無く、1 回の試行で MB 単位の利用者名を
 * ログへ書かせることができるため。
 */
public final class LogValueSanitizer {

    /** ログに書く最大文字数。利用者名の上限（64 文字）より長くしてあり、正規の値は切られない。 */
    public static final int MAX_LENGTH = 100;

    /** ユーティリティクラスのためインスタンス化させない。 */
    private LogValueSanitizer() {
    }

    /**
     * 改行・制御文字を見える形（{@code \n}・{@code \r}・{@code \t}・{@code \}{@code uXXXX}）に置き換え、
     * {@value #MAX_LENGTH} 文字を超える分を捨てる。
     *
     * @param value ログに書きたい値。{@code null} 可
     * @return ログに書いてよい 1 行の文字列。{@code null} なら {@code null}
     */
    public static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        int end = Math.min(value.length(), MAX_LENGTH);
        // サロゲートペアの途中で切ると、ログに化けた 1 文字が残るため
        if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        StringBuilder out = new StringBuilder(end + 16);
        for (int i = 0; i < end; i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    // U+2028（Zl）・U+2029（Zp）は行区切りとして扱う表示先があるので、制御文字と同じく見える形にする。
                    // Zl・Zp に属するのはこの 2 文字だけ。文字そのものと比べると、ソースに見えない文字か
                    // （コンパイル前に展開される）Unicode エスケープを書くことになるため、種類で判定する
                    int type = Character.getType(c);
                    if (type == Character.CONTROL || type == Character.LINE_SEPARATOR
                            || type == Character.PARAGRAPH_SEPARATOR) {
                        out.append(String.format("\\u%04X", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        if (end < value.length()) {
            out.append("…（全").append(value.length()).append("文字）");
        }
        return out.toString();
    }
}
