package com.example.monitor.playground;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 画面から渡された入力値を、YouTube Data API へ渡す形に整えるための共通処理。
 *
 * <p>どのハンドラでも「カンマ区切りをリストにする」「空欄なら既定値を使う」という
 * 同じ処理が必要になるため、複製せずここへ集めている
 * （{@code util} パッケージではなくこのパッケージに置くのは、
 * お試し機能を削除するときに一緒に消えるようにするため）。
 */
final class PlaygroundParameters {

    private PlaygroundParameters() {
    }

    /**
     * 入力値を取り出す。未入力なら既定値を返す。
     *
     * @param parameters   入力値
     * @param name         パラメータ名
     * @param defaultValue 未入力のときに使う値
     * @return 取り出した値
     */
    static String text(Map<String, String> parameters, String name, String defaultValue) {
        String value = parameters.get(name);
        return (value == null || value.isBlank()) ? defaultValue : value.strip();
    }

    /**
     * カンマ区切りの入力値をリストにする。
     *
     * <p>YouTube Data API の {@code part} や {@code id} は複数指定できるため、
     * 画面では「snippet,statistics」のようにカンマ区切りで入力してもらう。
     *
     * @param parameters   入力値
     * @param name         パラメータ名
     * @param defaultValue 未入力のときに使う値（同じくカンマ区切り）
     * @return 分割した結果。空要素は取り除く
     */
    static List<String> list(Map<String, String> parameters, String name, String defaultValue) {
        return Arrays.stream(text(parameters, name, defaultValue).split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /**
     * 件数の入力値を数値にする。
     *
     * <p>数値として読めない入力は既定値へ倒す。お試し用の画面なので、
     * 入力ミスでエラーにするより「既定値で動かして結果を見せる」方が使いやすい。
     *
     * @param parameters   入力値
     * @param name         パラメータ名
     * @param defaultValue 未入力・数値でない場合に使う値
     * @return 件数
     */
    static long count(Map<String, String> parameters, String name, long defaultValue) {
        try {
            return Long.parseLong(text(parameters, name, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
