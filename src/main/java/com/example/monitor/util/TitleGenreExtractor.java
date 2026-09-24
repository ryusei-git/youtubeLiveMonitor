package com.example.monitor.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配信タイトルからジャンルを取り出す。
 *
 * <p>配信者はタイトルの {@code 【】} にジャンル（ゲーム名や企画名）を書くことが多いため、
 * その中身をジャンルとみなす。<b>最初の {@code 【】} だけ</b>を使うのは、末尾の {@code 【】} には
 * {@code 【尾丸ポルカ/ホロライブ】} のように配信者名が入ることが多く、ジャンルではないため。
 *
 * <p>YouTube の動画カテゴリ（Gaming など）は使わない。どのゲームの配信か区別できないほど粗く、
 * ダッシュボードで配信予定を見分ける役に立たないため。タイトルから毎回求められるので DB にも保存しない。
 */
public final class TitleGenreExtractor {

    /** 最初の {@code 【…】} の中身を取り出す形。 */
    private static final Pattern BRACKET = Pattern.compile("【([^】]*)】");

    /** ユーティリティクラスのためインスタンス化させない。 */
    private TitleGenreExtractor() {
    }

    /**
     * タイトルの最初の {@code 【】} の中身をジャンルとして返す。
     *
     * @param title 配信タイトル。{@code null} も受け付ける
     * @return 前後の空白を除いたジャンル。{@code 【】} が無い・中身が空白だけ・タイトルが {@code null} なら {@code null}
     */
    public static String extract(String title) {
        if (title == null) {
            return null;
        }
        Matcher matcher = BRACKET.matcher(title);
        if (!matcher.find()) {
            return null;
        }
        String genre = matcher.group(1).strip();
        return genre.isEmpty() ? null : genre;
    }
}
