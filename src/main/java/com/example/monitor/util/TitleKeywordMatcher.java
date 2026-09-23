package com.example.monitor.util;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 配信タイトル・カテゴリに対するキーワード一致を判定する。
 *
 * <h2>なぜ独立したクラスにしているか</h2>
 * 同じ判定を<b>チャンネル単位の設定</b>（{@code MonitoredChannel}）と
 * <b>購読ごとの設定</b>（{@code UserSubscription}）の両方で使う。
 * 片方に private メソッドとして置くと、もう片方へ複製されて食い違っていく。
 *
 * <h2>タイトルとカテゴリの両方を見る</h2>
 * Twitch にはカテゴリという独立した項目があり、内容の申告がそちらに寄る。
 * 実機で Twitch の「ASMR」カテゴリの配信10件を調べたところ、
 * <b>2件はタイトルに ASMR を含んでいなかった</b>。タイトルだけ見ると取りこぼす。
 * （YouTube には相当する項目が無く、カテゴリは常に {@code null}）
 */
public final class TitleKeywordMatcher {

    /** キーワードの区切り文字。 */
    private static final String SEPARATOR = ",";

    /** インスタンス化させない。 */
    private TitleKeywordMatcher() {
    }

    /**
     * キーワードのいずれかがタイトルまたはカテゴリに含まれるかを調べる。
     *
     * <p><b>キーワードが未設定なら常に一致とみなす</b>（絞り込みなし＝全部対象）。
     *
     * @param keywords カンマ区切りのキーワード。空・{@code null} なら絞り込みなし
     * @param title    配信タイトル。取得できていなければ {@code null}
     * @param category 配信カテゴリ。プラットフォームに無ければ {@code null}
     * @return 対象とすべきなら {@code true}
     */
    public static boolean matches(String keywords, String title, String category) {
        if (keywords == null || keywords.isBlank()) {
            return true;
        }
        return Stream.of(title, category)
                .filter(Objects::nonNull)
                .anyMatch(text -> containsAnyKeyword(keywords, text));
    }

    /**
     * 指定した文字列に、キーワードのいずれかが含まれるかを調べる。
     *
     * @param keywords カンマ区切りのキーワード
     * @param text     調べる文字列
     * @return いずれかのキーワードを含むなら {@code true}
     */
    private static boolean containsAnyKeyword(String keywords, String text) {
        String lowerText = text.toLowerCase();
        return Arrays.stream(keywords.split(SEPARATOR))
                .map(String::trim)
                .filter(keyword -> !keyword.isBlank())
                .anyMatch(keyword -> lowerText.contains(keyword.toLowerCase()));
    }
}
