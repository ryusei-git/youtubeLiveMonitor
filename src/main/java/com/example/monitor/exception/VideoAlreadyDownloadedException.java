package com.example.monitor.exception;

/**
 * 同じ動画をサービスに重ねて保存しようとしたときに投げられる。
 *
 * <p>「入力が不正」ではなく「状態が競合している」ことを表すため、
 * REST API では 409 Conflict に対応付けている
 * （{@link ChannelAlreadyRegisteredException} と同じ考え方）。
 *
 * <p>断る理由は 2 つあり、文言を分けている。利用者の画面（{@code my-app.js}・{@code recordings.js}）は
 * サーバーの文言をそのまま出すので、文言が次にすることを伝える。
 * <ul>
 *   <li>取得中（{@link #VideoAlreadyDownloadedException(String)}）… 同じ動画の録画・保存・後始末が進んでいる。
 *       同じ動画 ID のファイルを同じ場所に二重に書き込むと、一方が他方の出力を壊すため。
 *       「端末に保存」（{@code DeviceDownloadService}）も、サービスが同じ動画を取得中のときにこれを投げる。</li>
 *   <li>保存済み（{@link #alreadySaved(String)}）… 再生できる録画（完了・途中まで）が既にある。
 *       アーカイブは全員に全録画を見せるので、取り直さずにそれを見てもらう（#449 の決定）。</li>
 * </ul>
 *
 * <p>失敗（{@code FAILED}）の履歴だけが残っている動画では投げない。{@code VideoDownloadService} が
 * その履歴とファイルを消して取り直す。以前は状態を問わず断り、録画履歴を先に消すよう求めていたが、
 * 録画を消せるのは管理者だけ（{@code /api/recordings/**}）なので、#450 で一般利用者に開いた後は、
 * 一時的な失敗で二度と保存できない動画ができていた。
 */
public class VideoAlreadyDownloadedException extends RuntimeException {

    /**
     * 同じ動画を今サービスが取得している（録画・保存・後始末の最中）ときの例外を作る。
     *
     * @param videoId 動画 ID
     */
    public VideoAlreadyDownloadedException(String videoId) {
        this("この動画は今サービスが取得しています。終わるとアーカイブに出ます"
                + "（出てこなければ、しばらくしてからもう一度お試しください）: ", videoId);
    }

    private VideoAlreadyDownloadedException(String message, String videoId) {
        super(message + videoId);
    }

    /**
     * 再生できる録画（完了・途中まで）が既にあるときの例外を作る。
     *
     * @param videoId 動画 ID
     * @return 例外
     */
    public static VideoAlreadyDownloadedException alreadySaved(String videoId) {
        return new VideoAlreadyDownloadedException(
                "この動画は既にサービスに保存されています。アーカイブから見られます: ", videoId);
    }
}
