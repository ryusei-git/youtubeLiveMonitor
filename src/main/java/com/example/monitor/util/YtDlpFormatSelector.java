package com.example.monitor.util;

/**
 * {@code yt-dlp} の {@code -f}（画質・音質の選択）に渡す指定を組み立てる。
 *
 * <p>ライブ配信の録画（{@link com.example.monitor.service.StreamRecorder}）と
 * URL 指定のダウンロード（{@link com.example.monitor.service.VideoDownloadService}）の
 * <b>両方で同じ指定を使う必要がある</b>ため、独立クラスに切り出している。
 * 片方にだけ書いておくと、設定（{@code monitor.recording.max-height}）を変えたときに
 * 「録画では効くのにダウンロードでは効かない」という分かりにくい食い違いになる。
 */
public final class YtDlpFormatSelector {

    /** 上限を設けない場合の指定。映像と音声をそれぞれ最高品質で取り、取れなければ単一ファイルにする。 */
    private static final String BEST = "bestvideo+bestaudio/best";

    private YtDlpFormatSelector() {
    }

    /**
     * 最大の高さ（ピクセル）から {@code -f} に渡す指定を組み立てる。
     *
     * <p>画質を落としたくないという要望から、{@code 0} 以下は「上限なし」を意味する
     * （{@link com.example.monitor.config.MonitorProperties.RecordingProperties} の既定値）。
     *
     * @param maxHeight 最大の高さ（ピクセル）。{@code 0} 以下なら上限を設けない
     * @return {@code -f} に渡す指定
     */
    public static String of(int maxHeight) {
        if (maxHeight <= 0) {
            return BEST;
        }
        return "bestvideo[height<=" + maxHeight + "]+bestaudio/best[height<=" + maxHeight + "]";
    }
}
