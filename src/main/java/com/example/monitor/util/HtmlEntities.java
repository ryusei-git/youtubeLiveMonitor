package com.example.monitor.util;

import org.jsoup.parser.Parser;

/**
 * YouTube Data API の {@code search.list} が返す文字列の、HTML の文字参照を戻す。
 *
 * <p>{@code search.list} の {@code snippet.title} は、{@code '} を {@code &#39;}、{@code &} を {@code &amp;} のように
 * HTML の文字参照に直した形で返る（{@code videos.list}・{@code channels.list} は生の文字列を返す）。
 * そのまま保存すると、画面が表示の直前に {@code escapeHtml} するため {@code &#39;} が文字として見え、
 * チャンネル名検索から登録したチャンネル名は {@code A&amp;B} のまま通知にも出る。受け取った場所で 1 回だけ戻す。
 *
 * <p>{@code unescapeEntities} の第 2 引数を {@code false}（本文の規則）にするのは、この値が HTML の属性値ではなく
 * JSON の文字列だから。属性値を読む {@code LiveStreamDetector.readAttribute} とは意図して違えている。
 * 生の文字列を返す API の値には使わない。タイトルに本当に含まれる {@code &amp;} まで {@code &} に変わるため。
 */
public final class HtmlEntities {

    private HtmlEntities() {
    }

    /**
     * HTML の文字参照（{@code &amp;}・{@code &#39;}・{@code &quot;} など）を元の文字に戻す。
     *
     * @param text 文字参照を含みうる文字列。{@code null} 可
     * @return 戻した文字列。{@code null} なら {@code null}
     */
    public static String unescape(String text) {
        return text == null ? null : Parser.unescapeEntities(text, false);
    }
}
