package com.example.monitor.playground;

/**
 * お試し実行画面の入力欄1つ分の定義。画面はこの定義から入力欄を組み立てる。
 *
 * @param name        パラメータ名（実行時に値を引くキー）
 * @param label       画面に出す項目名
 * @param required    入力必須なら {@code true}
 * @param placeholder 入力欄に薄く出す例。書式を伝えるために使う
 */
public record ApiParameter(
        String name,
        String label,
        boolean required,
        String placeholder
) {

    /**
     * 必須の入力欄を作る。
     *
     * @param name        パラメータ名
     * @param label       画面に出す項目名
     * @param placeholder 入力例
     * @return 定義
     */
    public static ApiParameter required(String name, String label, String placeholder) {
        return new ApiParameter(name, label, true, placeholder);
    }

    /**
     * 任意の入力欄を作る。
     *
     * @param name        パラメータ名
     * @param label       画面に出す項目名
     * @param placeholder 入力例
     * @return 定義
     */
    public static ApiParameter optional(String name, String label, String placeholder) {
        return new ApiParameter(name, label, false, placeholder);
    }
}
