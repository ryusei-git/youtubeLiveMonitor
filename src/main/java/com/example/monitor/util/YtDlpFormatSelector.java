package com.example.monitor.util;

import com.example.monitor.platform.Platform;

/**
 * {@code yt-dlp} の {@code -f}（画質・音質の選択）に渡す指定を組み立てる。
 *
 * <p>ライブ配信の録画（{@link com.example.monitor.service.StreamRecorder}）と
 * URL 指定のダウンロード（{@link com.example.monitor.service.VideoDownloadService}）の
 * <b>両方で同じ指定を使う必要がある</b>ため、独立クラスに切り出している。
 * 片方にだけ書いておくと、設定（{@code monitor.recording.max-height}）を変えたときに
 * 「録画では効くのにダウンロードでは効かない」という分かりにくい食い違いになる。
 *
 * <p><b>上限を付けた指定の最後には、必ず最高画質（{@link #BEST}）へ戻る指定を足す。</b>
 * {@code [height<=N]} は高さの分からない形式（音声だけの形式など）や上限より大きい形式を選ばないため、
 * 上限以下の映像が 1 本も無い配信では何も選ばれず、yt-dlp が失敗して録画そのものが残らない。
 * 容量を抑えるための上限で録画を失うのは本末転倒なので、そのときは最高画質で保存する。
 */
public final class YtDlpFormatSelector {

    /** 上限を設けない場合の指定。映像と音声をそれぞれ最高品質で取り、取れなければ単一ファイルにする。 */
    private static final String BEST = "bestvideo+bestaudio/best";

    private YtDlpFormatSelector() {
    }

    /**
     * 最大の高さ（ピクセル）から {@code -f} に渡す指定を組み立てる（YouTube 向けの並び）。
     *
     * <p>画質を落としたくないという要望から、{@code 0} 以下は「上限なし」を意味する
     * （{@link com.example.monitor.config.MonitorProperties.RecordingProperties} の既定値）。
     *
     * <p>映像と音声を別々に取る指定を先にしているのは、YouTube では映像と音声が 1 本になった形式が
     * 低画質（360p など）しか無く、{@code best[height<=N]} を先にするとそちらが選ばれてしまうため。
     *
     * @param maxHeight 最大の高さ（ピクセル）。{@code 0} 以下なら上限を設けない
     * @return {@code -f} に渡す指定
     */
    public static String of(int maxHeight) {
        if (maxHeight <= 0) {
            return BEST;
        }
        return "bestvideo[height<=" + maxHeight + "]+bestaudio/best[height<=" + maxHeight + "]/" + BEST;
    }

    /**
     * プラットフォームに応じた上限で {@code -f} に渡す指定を組み立てる。
     *
     * <p><b>Twitch だけ別の上限（{@code monitor.recording.twitch-max-height}、既定 720）を使う。</b>
     * Twitch は配信者が送った映像のまま（ソース画質）で配るため容量が大きく、ディスクが先に尽きる。
     * YouTube は画質を落としたくないという方針のまま {@code max-height}（既定は上限なし）に従う。
     *
     * <p><b>Twitch では {@code best[height<=N]} を先に試す。</b>Twitch の形式は HLS の
     * 1080p60・720p60 のように映像と音声が 1 本になっており、映像だけの形式は通常無いため。
     *
     * @param platform        対象のプラットフォーム
     * @param maxHeight       YouTube の最大の高さ（{@link #of(int)} に渡す）
     * @param twitchMaxHeight Twitch の最大の高さ。{@code 0} 以下なら上限を設けない
     * @return {@code -f} に渡す指定
     */
    public static String of(Platform platform, int maxHeight, int twitchMaxHeight) {
        if (platform != Platform.TWITCH) {
            return of(maxHeight);
        }
        if (twitchMaxHeight <= 0) {
            return BEST;
        }
        return "best[height<=" + twitchMaxHeight + "]/bestvideo[height<=" + twitchMaxHeight + "]+bestaudio/" + BEST;
    }
}
