package com.example.monitor.dto;

import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.RecordingMark;
import com.example.monitor.util.StreamLinkUtils;

import java.time.LocalDateTime;

/**
 * 録画履歴 1 件を API のレスポンスとして返す形。
 *
 * <p>エンティティは遅延読み込みのチャンネル参照を持つため、そのまま返すと
 * JSON 変換時に問題が起きやすい（{@link NotificationHistoryResponse}と同じ理由）。
 *
 * @param id             録画履歴の主キー
 * @param channelId      録画対象チャンネルの主キー。画面が「同じチャンネルの録画」を
 *                       引き直すときの絞り込みに使う（{@code youtubeChannelId} ではこの API を引けない）。
 *                       監視対象に登録されていないチャンネルの動画をダウンロードした場合は {@code null}
 * @param youtubeChannelId 録画対象チャンネルの YouTube チャンネル ID。未登録なら {@code null}
 * @param channelName    録画対象チャンネルの表示名。未登録の場合は
 *                       {@link #UNLINKED_CHANNEL_NAME}（画面に「-」とだけ出ると理由が分からないため）
 * @param channelUrl     チャンネルページの URL。配信元ごとに形が違う（Twitch はログイン名から作る）ため
 *                       サーバーで組み立てる。未登録、または Twitch でログイン名が無ければ {@code null}
 * @param videoId        配信の動画 ID
 * @param videoUrl       配信の視聴ページの URL（再生画面の「元の配信」のリンク先）。YouTube の録画だけ動画 ID から組み立てる。
 *                       Twitch の録画の動画 ID は配信 ID で視聴ページを作れず、未登録チャンネルの録画は配信元が分からないため、
 *                       どちらも {@code null}（画面は動画 ID を文字で出す）。ID の形から配信元を推測しないのは、
 *                       Twitch の配信 ID（数字だけ）や VOD ID（v＋数字）が 11 文字だと YouTube の動画 ID と見分けられないため
 * @param videoTitle     録画開始時点での配信タイトル
 * @param genre          タイトルの最初の {@code 【】} の中身。無ければ {@code null}
 * @param filePath       録画ファイルの保存先パス（{@code monitor.recording.directory}からの相対パス）。
 *                       画面はこれを {@code /recordings/} と連結して再生用 URL を組み立てる
 * @param fileSizeBytes  ファイルサイズ（バイト）。録画中・失敗時は {@code null}
 * @param durationSeconds 再生時間（秒）。読み取れていなければ {@code null}
 * @param playCount      このサービスの画面で再生された回数（全員の合計。YouTube 上の再生数ではない）
 * @param thumbnailPath  サムネイル画像のパス（{@code monitor.recording.directory}からの相対パス）。
 *                       未生成なら {@code null}。画面はこれを {@code /recordings/} と連結して表示する
 * @param status         録画の状態（{@code RECORDING} / {@code COMPLETED} / {@code PARTIAL} / {@code FAILED}）
 * @param startedAt      録画を開始した時刻
 * @param completedAt    録画が完了・失敗した時刻。録画中は {@code null}
 * @param watched        ログイン中の利用者が視聴済みにしているか。印を見ない API では常に {@code false}
 * @param favorite       ログイン中の利用者がお気に入りにしているか。印を見ない API では常に {@code false}
 */
public record RecordingResponse(
        Long id,
        Long channelId,
        String youtubeChannelId,
        String channelName,
        String channelUrl,
        String videoId,
        String videoUrl,
        String videoTitle,
        String genre,
        String filePath,
        Long fileSizeBytes,
        Integer durationSeconds,
        int playCount,
        String thumbnailPath,
        String status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        boolean watched,
        boolean favorite
) {

    /**
     * 監視対象に登録されていないチャンネルの録画に使う表示名。
     *
     * <p>URL 指定のダウンロードでは、監視していないチャンネルの動画が対象になることがあり、
     * その録画は {@link Recording#channel} が {@code null} になる。
     * 「削除済みチャンネル」（登録されていたが消された）とは意味が違うため、別の言葉にしている。
     *
     * <p>ディスク使用量の集計（{@link com.example.monitor.service.RecordingFileService}）でも
     * 同じ状態を指すのに使う。画面ごとに違う言葉が出ると同じものだと分からなくなるため、
     * ここ 1 か所で定義している。
     */
    public static final String UNLINKED_CHANNEL_NAME = "(未登録チャンネル)";

    /**
     * エンティティからレスポンスを組み立てる。
     *
     * <p><b>チャンネルが {@code null} でも落ちないこと。</b>URL 指定でダウンロードした動画は
     * チャンネルに紐づかないことがある（{@link Recording#channel} の JavaDoc 参照）。
     * 一覧・再生画面の両方がこのメソッドを通るため、ここで吸収しておけば
     * 画面側が個別に未設定の判定を書かずに済む。
     *
     * <p>印（視聴済み・お気に入り）は付けない。利用者向けの一覧など、印を見ない経路で使う。
     *
     * @param recording 変換元のエンティティ
     * @return 変換後のレスポンス
     */
    public static RecordingResponse from(Recording recording) {
        return from(recording, null);
    }

    /**
     * エンティティと利用者の印からレスポンスを組み立てる。
     *
     * @param recording 変換元のエンティティ
     * @param mark      ログイン中の利用者の印。まだ付けていなければ {@code null}
     * @return 変換後のレスポンス
     */
    public static RecordingResponse from(Recording recording, RecordingMark mark) {
        MonitoredChannel channel = recording.getChannel();
        return new RecordingResponse(
                recording.getId(),
                channel == null ? null : channel.getId(),
                channel == null ? null : channel.getYoutubeChannelId(),
                channel == null ? UNLINKED_CHANNEL_NAME : channel.getChannelName(),
                channel == null ? null : StreamLinkUtils.channelUrl(
                        channel.getPlatform(), channel.getYoutubeChannelId(), channel.getChannelLogin()),
                recording.getVideoId(),
                channel == null ? null : StreamLinkUtils.videoUrl(channel.getPlatform(), recording.getVideoId(), null),
                recording.getVideoTitle(),
                recording.getGenre(),
                recording.getFilePath(),
                recording.getFileSizeBytes(),
                recording.getDurationSeconds(),
                recording.getPlayCount(),
                recording.getThumbnailPath(),
                recording.getStatus().name(),
                recording.getStartedAt(),
                recording.getCompletedAt(),
                mark != null && mark.getWatchedAt() != null,
                mark != null && mark.isFavorite()
        );
    }
}
