package com.example.monitor.exception;

/**
 * 配信中、または配信開始前の待機所の URL を手動ダウンロードしようとしたときに投げられる。
 *
 * <p>手動ダウンロードは VOD（録画済み動画）向けの機能で、ライブ配信の取得は自動録画
 * （{@link com.example.monitor.service.StreamRecorder}）の担当と責務を分けている。
 * 判定は {@code yt-dlp} が返す {@code live_status} が {@code is_live} または
 * {@code is_upcoming} のときだけ行う（{@link com.example.monitor.dto.VideoSource#isLiveOrUpcoming()}）。
 *
 * <p>配信直後でまだ処理中の {@code post_live} や、既に終わった配信の {@code was_live} は
 * 拒否しない。これらは自動録画と処理が重なる可能性があるが、その重複の排除は
 * 動画IDの予約機構（{@link com.example.monitor.service.ActiveVideoJobs}）が担う層であり、
 * この例外の役割ではない（判定時は配信していなかったが、直後に配信が始まって
 * 自動録画が動く、という経路が残るため。両経路で同じ予約機構を共有することで対処している）。
 *
 * <p>REST API では 400 Bad Request に対応付けている。
 */
public class LiveStreamDownloadRejectedException extends RuntimeException {

    /**
     * @param videoId 拒否した動画 ID
     */
    public LiveStreamDownloadRejectedException(String videoId) {
        super("配信中の動画はダウンロードできません。自動録画をご利用ください: " + videoId);
    }
}
