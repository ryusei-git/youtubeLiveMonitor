package com.example.monitor.dto;

/**
 * {@link com.example.monitor.service.LiveStreamDetector} が {@code /live} ページを調べた結果。
 *
 * <p><b>「配信していない」と「調べられなかった」を必ず区別するための型。</b>
 * 以前はどちらも {@code Optional.empty()} で表していたため、YouTube 側の HTML 構造変更や
 * 通信障害でアプリが実質停止していても、画面上は「どのチャンネルも配信していない」という
 * 平常運転と同じ見た目になってしまっていた（このアプリは公式に保証されていない
 * HTML 解析に依存しているので、この取り違えは最大のサイレント故障リスクだった）。
 *
 * <h2>視聴 URL をここに持たせている理由</h2>
 * URL の組み立て方はプラットフォームによって根本的に異なり、<b>配信の識別子だけからは
 * 導けない場合がある</b>。YouTube は {@code watch?v={動画ID}} で完結するが、Twitch の
 * 視聴 URL は {@code twitch.tv/{ログイン名}} の形で、<b>配信 ID からは作れない</b>
 * （配信 ID を使った {@code twitch.tv/videos/{配信ID}} は、Twitch が SPA のため
 * HTTP 200 を返すものの中身は存在しない、という紛らわしい挙動をする）。
 *
 * <p>ログイン名は検知時の API 応答には含まれているが DB には保存していない。
 * そのため<b>「検知した者が URL も組み立てて持たせる」</b>のが、追加の通信も
 * スキーマ変更も伴わない唯一の形になる。ログイン名を保存する案は、配信者が改名した
 * 瞬間に「永久にオフライン」と言い続けるサイレント故障になるため採らなかった。
 *
 * @param status   判定の結果
 * @param videoId  配信中の動画 ID。{@link DetectionStatus#LIVE} 以外では {@code null}
 * @param title    配信タイトル。{@link DetectionStatus#LIVE} 以外、または取得できなかった場合は {@code null}
 * @param category 配信のカテゴリ。<b>カテゴリという項目を持つプラットフォームだけが入れる</b>
 *                 （Twitch のゲーム・カテゴリ欄がこれにあたる）。YouTube には配信ごとの
 *                 相当する項目が無いため常に {@code null}
 * @param watchUrl 配信の視聴 URL。録画時に {@code yt-dlp} へ渡す URL でもある。
 *                 {@link DetectionStatus#LIVE} 以外では {@code null}
 */
public record LiveStreamDetection(
        DetectionStatus status, String videoId, String title, String category, String watchUrl) {

    /** 判定の結果。 */
    public enum DetectionStatus {
        /** 配信中と判定できた。 */
        LIVE,
        /** 正常に調べられた結果、配信していなかった（配信開始前の待機所を含む）。 */
        NOT_LIVE,
        /** 通信エラーや HTML 構造の変化により、そもそも判定できなかった。 */
        DETECTION_FAILED
    }

    /**
     * 配信中として結果を組み立てる。
     *
     * @param videoId  配信の動画 ID
     * @param title    配信タイトル。取得できなかった場合は {@code null}
     * @param category 配信のカテゴリ。この項目を持たないプラットフォームでは {@code null}
     * @param watchUrl 配信の視聴 URL
     * @return 判定結果
     */
    public static LiveStreamDetection live(String videoId, String title, String category, String watchUrl) {
        return new LiveStreamDetection(DetectionStatus.LIVE, videoId, title, category, watchUrl);
    }

    /**
     * 配信していないとして結果を組み立てる。
     *
     * @return 判定結果
     */
    public static LiveStreamDetection notLive() {
        return new LiveStreamDetection(DetectionStatus.NOT_LIVE, null, null, null, null);
    }

    /**
     * 判定できなかったとして結果を組み立てる。
     *
     * @return 判定結果
     */
    public static LiveStreamDetection failed() {
        return new LiveStreamDetection(DetectionStatus.DETECTION_FAILED, null, null, null, null);
    }

    /**
     * 配信中と判定できたかどうか。
     *
     * @return 配信中なら {@code true}
     */
    public boolean isLive() {
        return status == DetectionStatus.LIVE;
    }

    /**
     * 判定そのものに失敗したかどうか。
     *
     * @return 判定できなかった場合は {@code true}
     */
    public boolean isDetectionFailed() {
        return status == DetectionStatus.DETECTION_FAILED;
    }
}
