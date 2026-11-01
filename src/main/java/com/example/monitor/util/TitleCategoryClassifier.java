package com.example.monitor.util;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 配信タイトルのキーワードから、録画の大まかな分類を決める。
 *
 * <p><b>{@link TitleGenreExtractor} のジャンルと分けている理由。</b>ジャンルは最初の {@code 【】}
 * の中身をそのまま使うので、ゲーム名や企画名まで細かく見分けられる代わりに、同じ種類でも書き方が
 * 違えば別のジャンルになり（{@code ASMR/3Dio} と {@code 🔴ASMR} など）、{@code 【】} の無いタイトルは
 * どこにも入らない。分類はタイトル全体を見て決まった数個の枠へまとめるためのもので、役割が違うので
 * 置き換えずに並べている。
 *
 * <p><b>1 本が複数の分類に入る。</b>耳かきの ASMR を探す人は、どちらのボタンからでも見つけたいため。
 *
 * <p><b>定義をコードの定数に持つ。</b>利用者が画面で編集する機能は作らないため、DB に置く理由が無い。
 * 定義を変えたら、保存済みの行は {@link com.example.monitor.service.RecordingCategoryBackfiller} が
 * 次の起動で直す。{@link #CATEGORIES} の順はそのまま画面の並び順になる。
 */
public final class TitleCategoryClassifier {

    /**
     * 分類 1 つの定義。
     *
     * @param key     URL と API の値。{@code ,} を含めない（保存の形は {@link #classify}）
     * @param label   画面に出す名前
     * @param pattern 正規化した題名に対する判定。{@code find} で 1 か所でも当たれば入る
     */
    public record Category(String key, String label, Pattern pattern) {
    }

    /** どの分類にも当たらないときの key。 */
    private static final String OTHER_KEY = "other";

    /** 耳かきのキーワード。ASMR にも入るので定数に分けている。 */
    private static final String EAR_CLEANING = "耳かき|耳掃除|耳のお掃除|ear cleaning|earcleaning|ear-cleaning|掏耳";

    /**
     * 耳舐めのキーワード。ASMR にも入るので定数に分けている。
     * {@code click} に当てないよう、{@code lick} は語の先頭だけを見る。
     */
    private static final String EAR_LICKING = "耳舐め|耳なめ|耳はむ|はむはむ|舔耳|(?<![a-z])lick|earlick";

    /** 囁きのキーワード。ASMR にも入るので定数に分けている。 */
    private static final String WHISPER = "囁き|ささやき|ひそひそ|whisper";

    /**
     * 分類の定義。この順が画面の並び順になる。
     *
     * <p>末尾の「その他」は {@link #classify} がほかのどれにも当たらないときに入れる枠で、キーワードを
     * 持たない（{@code (?!)} は何にも当たらない）。{@code null} にしないのは、使う側で分けずに同じ形で
     * 扱えるようにするため。
     */
    public static final List<Category> CATEGORIES = List.of(
            category("asmr", "ASMR", String.join("|",
                    "asmr|音フェチ|ku100|3dio|バイノーラル|binaural", EAR_CLEANING, EAR_LICKING, WHISPER)),
            category("ear-cleaning", "耳かき", EAR_CLEANING),
            category("ear-licking", "耳舐め", EAR_LICKING),
            category("whisper", "囁き", WHISPER),
            category("sleep", "睡眠導入", "睡眠導入|寝落ち|添い寝|入眠|sleep"),
            category("massage", "マッサージ", "マッサージ|オイル|massage"),
            // harp・sharp・RPG に当てないよう、rp は前後が英字でない単独の語だけを見る
            category("role-play", "シチュエーション", "ロールプレイ|シチュ|(?<![a-z])rp(?![a-z])"),
            category("chat", "雑談", "雑談|talking|フリートーク"),
            category("singing", "歌枠", "歌枠|歌回|karaoke|singing"),
            category("game", "ゲーム", "ゲーム|ホラゲ|初見|実況|switch"),
            category(OTHER_KEY, "その他", "(?!)"));

    /** ユーティリティクラスのためインスタンス化させない。 */
    private TitleCategoryClassifier() {
    }

    /**
     * タイトルが入る分類を求める。
     *
     * <p>{@code NFKC} で正規化して小文字にしてから判定するのは、全角の {@code ＡＳＭＲ}・大文字の
     * {@code ASMR}・半角カナを同じ語として扱うため。結果を DB に保存しているのも、この正規化を SQL では
     * できないから（{@link com.example.monitor.entity.Recording#categories}）。
     *
     * @param title 配信タイトル。{@code null} も受け付ける
     * @return 当たった key を {@link #CATEGORIES} の順に {@code ,} で挟んだ文字列
     *         （例 {@code ,asmr,whisper,}）。どれにも当たらなければ {@code ,other,}
     */
    public static String classify(String title) {
        String normalized = title == null ? ""
                : Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String keys = CATEGORIES.stream()
                .filter(category -> category.pattern().matcher(normalized).find())
                .map(Category::key)
                .collect(Collectors.joining(","));
        return "," + (keys.isEmpty() ? OTHER_KEY : keys) + ",";
    }

    /**
     * 分類の key として知っている値かを返す。
     *
     * @param key 調べる値。{@code null} も受け付ける
     * @return {@link #CATEGORIES} のどれかの key なら {@code true}
     */
    public static boolean isKnownKey(String key) {
        return CATEGORIES.stream().anyMatch(category -> category.key().equals(key));
    }

    /**
     * 分類の定義を作る。
     *
     * @param key      URL と API の値
     * @param label    画面に出す名前
     * @param keywords 正規表現。正規化した題名に合わせて、全角の英数字・大文字を含めずに書く
     * @return 分類の定義
     */
    private static Category category(String key, String label, String keywords) {
        return new Category(key, label, Pattern.compile(keywords));
    }
}
