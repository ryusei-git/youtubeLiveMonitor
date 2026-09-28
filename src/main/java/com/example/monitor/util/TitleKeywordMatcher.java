package com.example.monitor.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 *
 * <h2>全角・半角をそろえてから比べる</h2>
 * 日本の配信タイトルには「【ＡＳＭＲ】」のような全角英数字や「ｶﾗｵｹ」のような半角カナが混ざる。
 * 大文字小文字をそろえるだけでは、キーワード「ASMR」がこれらに一致せず、通知・録画から漏れる。
 * そのため、キーワードと調べる文字列の両方を {@link Normalizer.Form#NFKC} で正規化してから
 * 小文字にそろえる。小文字化に {@link Locale#ROOT} を渡すのは、実行環境のロケール
 * （トルコ語の「I」など）で結果が変わらないようにするため。
 * 区切りのカンマも正規化の後に探すので、全角の「，」で区切っても分かれる。
 *
 * <h2>先頭に「-」を付けた語は除外</h2>
 * 「歌枠だけ欲しいが切り抜きの告知は要らない」のように、含む語だけでは表せない好みがある。
 * DB の列を増やさずにチャンネル単位と購読ごとの両方へ効かせるため、同じ欄の書き方で表す
 * （検索サイトの「-語」と同じ書き方）。除外の語も、含む語と同じくタイトルとカテゴリの両方を見る。
 * 全角の「－」も正規化で「-」になるので除外として扱う。
 * <b>先頭の長音の「ー」（半角の「ｰ」を含む）とマイナス記号の「−」も除外の印として扱う。</b>
 * 日本語入力のまま「-」のキーを押すと「ー」が入り、「まいなす」を変換すると「−」が入る。
 * 印と気づかれずに「ー切り抜き」が<b>含む</b>語として保存されると、その語を含む配信しか対象にならず、
 * 通知・録画が黙って止まる。日本語の語は長音から始まらないので、含む語と取り違えることはない。
 */
public final class TitleKeywordMatcher {

    /** キーワードの区切り文字。 */
    private static final String SEPARATOR = ",";

    /**
     * 除外の語であることを表す先頭の印（正規化の後の形）。「-」、長音の「ー」（U+30FC）、マイナス記号の「−」（U+2212）。
     * 見分けにくい文字なので 16 進の番号で書いている。
     */
    private static final String EXCLUDE_MARKS = "-\u30FC\u2212";

    /** インスタンス化させない。 */
    private TitleKeywordMatcher() {
    }

    /**
     * 配信が条件に当てはまるかを調べる。
     *
     * <p><b>有効なキーワードが 1 つも無ければ常に一致とみなす</b>（絞り込みなし＝全部対象）。
     * 除外の語のどれかがタイトルかカテゴリに含まれれば不一致。そうでなければ、含む語が無いときは一致、
     * 含む語があるときはどれかがタイトルかカテゴリに含まれれば一致。
     *
     * <p><b>キーワードがあるのにタイトルもカテゴリも無いときは不一致</b>（除外の語だけのときも同じ）。
     * 「当てはまる」と確かめられない配信を対象にしないため
     * （呼び出し側の {@code LiveStreamPollingScheduler} は、この場合を警告としてログに残す）。
     *
     * @param keywords カンマ区切りのキーワード。先頭に {@code -}（{@code ー}・{@code −} も同じ）を付けた語は除外。
     *                 空・{@code null} なら絞り込みなし
     * @param title    配信タイトル。取得できていなければ {@code null}
     * @param category 配信カテゴリ。プラットフォームに無ければ {@code null}
     * @return 対象とすべきなら {@code true}
     */
    public static boolean matches(String keywords, String title, String category) {
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        for (String keyword : parse(keywords)) {
            String excluded = excludedWord(keyword);
            if (excluded == null) {
                includes.add(keyword);
            } else if (!excluded.isEmpty()) {
                excludes.add(excluded);
            }
        }
        if (includes.isEmpty() && excludes.isEmpty()) {
            return true;
        }
        List<String> texts = normalizedTexts(title, category);
        if (texts.isEmpty()) {
            return false;
        }
        if (containsAnyOf(texts, excludes)) {
            return false;
        }
        return includes.isEmpty() || containsAnyOf(texts, includes);
    }

    /**
     * キーワードのどれかがタイトルかカテゴリに含まれるかを調べる。先頭の除外の印（{@code -} など）は外して同じ語として扱う。
     *
     * <p>検索の「タイトルに含まない」欄のように、<b>欄そのものが除外を意味する</b>ところで使う。
     * {@link #matches} に渡すと、利用者が「-切り抜き」と書いたときに除外の除外になり、
     * 切り抜きを<b>含まない</b>動画を落としてしまう。
     *
     * @param keywords カンマ区切りのキーワード。空・{@code null} なら {@code false}
     * @param title    調べるタイトル。{@code null} 可
     * @param category 調べるカテゴリ。{@code null} 可
     * @return どれかを含むなら {@code true}
     */
    public static boolean containsAny(String keywords, String title, String category) {
        List<String> words = parse(keywords).stream()
                .map(keyword -> Objects.requireNonNullElse(excludedWord(keyword), keyword))
                .filter(word -> !word.isEmpty())
                .toList();
        return containsAnyOf(normalizedTexts(title, category), words);
    }

    /**
     * 除外の印で始まる語から、印を外す。
     *
     * @param keyword 正規化した空でない語
     * @return 印を外して前後の空白を除いた語（空のこともある）。印で始まらなければ {@code null}
     */
    private static String excludedWord(String keyword) {
        if (EXCLUDE_MARKS.indexOf(keyword.charAt(0)) < 0) {
            return null;
        }
        return keyword.substring(1).trim();
    }

    /**
     * カンマ区切りのキーワードを、正規化したうえで空でない語の一覧にする。
     *
     * @param keywords カンマ区切りのキーワード。{@code null} 可
     * @return 正規化した語の一覧（除外の印は付いたまま）
     */
    private static List<String> parse(String keywords) {
        if (keywords == null) {
            return List.of();
        }
        return Stream.of(normalize(keywords).split(SEPARATOR))
                .map(String::trim)
                .filter(keyword -> !keyword.isEmpty())
                .toList();
    }

    /**
     * タイトルとカテゴリのうち {@code null} でないものを正規化して並べる。
     *
     * @param title    配信タイトル。{@code null} 可
     * @param category 配信カテゴリ。{@code null} 可
     * @return 正規化した文字列の一覧
     */
    private static List<String> normalizedTexts(String title, String category) {
        return Stream.of(title, category)
                .filter(Objects::nonNull)
                .map(TitleKeywordMatcher::normalize)
                .toList();
    }

    /**
     * どれかの文字列に、どれかの語が含まれるかを調べる。
     *
     * @param texts 正規化した調べる文字列
     * @param words 正規化した語
     * @return 含まれるなら {@code true}
     */
    private static boolean containsAnyOf(List<String> texts, List<String> words) {
        return texts.stream().anyMatch(text -> words.stream().anyMatch(text::contains));
    }

    /**
     * 全角・半角と大文字・小文字の違いをなくす。
     *
     * @param text 対象の文字列
     * @return NFKC で正規化して小文字にした文字列
     */
    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
