package com.example.monitor.util;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * URL に載せる秘密の token（招待リンク・パスワードの再設定リンク）を作る。
 *
 * <p>招待と再設定で作り方がずれないよう 1 か所に置く。どちらも「知っていること自体が権限になる」ので、
 * 片方だけ弱い作り方になると、そこが抜け道になる。
 */
public final class SecureTokens {

    /**
     * 乱数のバイト数。
     *
     * <p>32 バイト＝256 ビット。総当たりで当てることは現実的に不可能な長さで、
     * かつ Base64 にしても URL に収まる程度に収まる（43 文字）。
     */
    private static final int TOKEN_BYTES = 32;

    /**
     * token 生成用の乱数。
     *
     * <p><b>{@code Math.random()} や {@code Random} を使ってはならない。</b>
     * それらは次の値を予測できるため、1 つのリンクから他のリンクを割り出せてしまう。
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** URL に載せるため、記号を含まない URL セーフな Base64 を使う（末尾の詰め物も付けない）。 */
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private SecureTokens() {
    }

    /**
     * 推測できない token を作る。
     *
     * @return URL に載せられる形式の token（43 文字）
     */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }
}
