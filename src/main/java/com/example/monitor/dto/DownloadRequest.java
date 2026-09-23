package com.example.monitor.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * URL を指定して動画のダウンロードを依頼するときのリクエスト。
 *
 * <p>受け取るのは URL 1 つだけ。<b>プラットフォームは利用者に選ばせない。</b>
 * URL を見ればどのプラットフォームかは分かる（{@link com.example.monitor.platform.StreamPlatform#supportsUrl}）ので、
 * 選択を求めると「YouTube の URL なのに Twitch を選んでしまった」という無意味な失敗を作るだけになる。
 *
 * @param url ダウンロードしたい動画の URL
 */
public record DownloadRequest(
        @NotBlank String url
) {
}
