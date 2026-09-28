package com.example.monitor.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 利用者が入力したチャンネルの指定を、登録処理が扱える形（チャンネル ID かハンドル）に整える共通処理。
 *
 * <p>ブラウザからコピーしてくる文字列は次のように何通りもある。これを利用者に
 * 「正しい形に直してから貼ってください」と要求するのは不親切なので、ここで吸収する。
 * <ul>
 *   <li>{@code https://www.youtube.com/channel/UCxxxx}（チャンネル ID 形式の URL）</li>
 *   <li>{@code https://www.youtube.com/@foo}（ハンドル形式の URL）</li>
 *   <li>{@code https://www.youtube.com/@foo/streams}（末尾にタブが付いた URL）</li>
 *   <li>{@code @foo} / {@code UCxxxx}（URL ではなく ID・ハンドルだけ）</li>
 * </ul>
 *
 * <p><b>この処理が無いと実際に事故が起きた。</b>利用者が URL から {@code @} を落とした
 * {@code seldea} のような文字列で登録してしまい、{@code /channel/seldea/live} が 404 になって
 * 全チャンネルの監視が静かに機能しなくなっていた。
 *
 * <p>複数の入口（REST API・CLI）から同じ整形が必要になるため、
 * {@code MonitoredChannelService} の private メソッドではなく独立クラスに切り出している。
 */
public final class YouTubeChannelInputParser {

    /** {@code /channel/UCxxxx} 形式の URL からチャンネル ID を抜き出す。 */
    private static final Pattern CHANNEL_ID_IN_URL = Pattern.compile("youtube\\.com/channel/([^/?#]+)");

    /** {@code /@foo} 形式の URL からハンドルを抜き出す。 */
    private static final Pattern HANDLE_IN_URL = Pattern.compile("youtube\\.com/@([^/?#]+)");

    /** ハンドルの接頭辞。 */
    private static final String HANDLE_PREFIX = "@";

    /**
     * YouTube のチャンネル ID の形。{@code UC} で始まる 24 文字で、使える文字は
     * base64url と同じ集合（英数字・{@code _}・{@code -}）。
     *
     * <p><b>「ID に見えないものはハンドルとみなす」ための判定に使う。</b>
     * これが無いと、{@code @} を付け忘れた入力（{@code ShiroganeNoel} など）を
     * チャンネル ID と解釈してそのまま保存してしまい、
     * {@code /channel/{入力}/live} が 404 になって監視が永久に機能しなくなる。
     *
     * <p><b>{@code /channel/} の URL から取り出した値の確認にも使う。</b>確かめないと、URL の後ろの
     * 任意の文字列がチャンネル ID として保存され、利用者が購読と解除を繰り返すだけで
     * 存在しない監視対象を際限なく増やせた。
     */
    private static final Pattern CHANNEL_ID_FORMAT = Pattern.compile("UC[A-Za-z0-9_-]{22}");

    private YouTubeChannelInputParser() {
    }

    /**
     * 入力文字列をチャンネル ID かハンドル（{@code @}始まり）に正規化する。
     *
     * <p>URL として解釈できなかった場合は、<b>チャンネル ID の形をしているかどうかで
     * ID とハンドルを見分ける</b>。{@code @} の有無で判断しないのは、利用者が
     * {@code @} を落として入力する事故が繰り返し起きたため（{@code mikenekoko}、
     * {@code ShiroganeNoel} の 2 件）。{@code @} が無いというだけでチャンネル ID と
     * みなすと、そのチャンネルは登録できてしまったうえで<b>永久に 404 を返し続ける</b>。
     *
     * <p>ハンドルと判断した場合は {@code @} を補って返す。以降の解決
     * （{@code YouTubeStreamPlatform.normalizeChannelInput}）が本来のチャンネル ID へ
     * 変換するため、DB には常に {@code UC...} 形式だけが入る。
     *
     * <p>{@code /channel/} の URL は、取り出した値がチャンネル ID の形でなければ断る。
     * ハンドルとみなして {@code @} を補わないのは、{@code /channel/} の URL は ID を表す書き方で、
     * {@code @} を落とす事故とは違うため。断る文言に入力の値を入れないのは、例外の文言が
     * そのままアプリログに書かれるため（利用者の入力をログへ流さない）。
     *
     * @param rawInput 利用者が入力した文字列
     * @return チャンネル ID、または {@code @} から始まるハンドル
     * @throws IllegalArgumentException 入力が空の場合、または {@code /channel/} の URL の ID がチャンネル ID の形でない場合
     */
    public static String normalize(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            throw new IllegalArgumentException("チャンネルIDまたはURLを入力してください");
        }

        String trimmed = rawInput.trim();

        Matcher channelIdMatcher = CHANNEL_ID_IN_URL.matcher(trimmed);
        if (channelIdMatcher.find()) {
            String channelId = channelIdMatcher.group(1);
            // URL の中の値も形を確かめる。確かめないと /channel/ の後ろの任意の文字列がそのまま
            // チャンネル ID として保存され、巡回のたびに「判定失敗」になる
            if (!CHANNEL_ID_FORMAT.matcher(channelId).matches()) {
                throw new IllegalArgumentException(
                        "チャンネルの URL（youtube.com/channel/…）の ID は UC で始まる 24 文字です。URL を確かめてください");
            }
            return channelId;
        }

        Matcher handleMatcher = HANDLE_IN_URL.matcher(trimmed);
        if (handleMatcher.find()) {
            // 日本語ハンドルは URL 上では百分率エンコードされているため元に戻す
            return HANDLE_PREFIX + URLDecoder.decode(handleMatcher.group(1), StandardCharsets.UTF_8);
        }

        if (trimmed.startsWith(HANDLE_PREFIX) || CHANNEL_ID_FORMAT.matcher(trimmed).matches()) {
            return trimmed;
        }

        // チャンネル ID の形をしていない＝ハンドルの @ を落として入力されたとみなす
        return HANDLE_PREFIX + trimmed;
    }
}
