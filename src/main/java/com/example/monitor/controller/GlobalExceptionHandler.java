package com.example.monitor.controller;

import com.example.monitor.exception.ChannelAlreadyRegisteredException;
import com.example.monitor.exception.ChannelNotFoundException;
import com.example.monitor.exception.LiveStreamDownloadRejectedException;
import com.example.monitor.exception.MonitoringInProgressException;
import com.example.monitor.exception.RecordingInProgressException;
import com.example.monitor.exception.RecordingNotFoundException;
import com.example.monitor.exception.VideoAlreadyDownloadedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * 各コントローラーで発生した例外を、対応する HTTP ステータスと JSON に変換する。
 *
 * <p>個々のコントローラーで {@code try-catch} を書かずに済ませるための共通処理。
 * レスポンスの形式は {@code {"error": "理由"}} に統一している。
 *
 * <h2>すべての失敗を必ずログに残す</h2>
 * <b>以前はここで例外を握って利用者へ返すだけで、ログを出していたのは
 * {@link IllegalStateException} の 1 つだけだった。</b>そのため
 * 「ハンドルに該当するチャンネルが見つかりません」のような<b>登録の失敗が、
 * 画面にエラーとして出るのにサーバー側には痕跡が一切残らない</b>状態になっていた。
 * 後から「登録に失敗した形跡はあるか」を調べる手段が無い。
 *
 * <p>そこで失敗の性質で 2 段階に分けて必ず記録する。
 * <ul>
 *   <li>利用者の操作で起こりうる失敗（重複登録・未存在・入力誤り）… {@code WARN}。
 *       想定内なのでスタックトレースは残さず、理由だけを 1 行で残す</li>
 *   <li>サーバー側の異常 … {@code ERROR}。原因調査が要るのでスタックトレースごと残す</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** エラー理由を格納する JSON のキー。 */
    private static final String ERROR_KEY = "error";

    /**
     * 例外がメッセージを持たない場合に返す文言。
     *
     * <p>{@code Map.of} は値に {@code null} を許さないため、メッセージの無い例外を
     * そのまま詰めると<b>エラー応答を組み立てる最中に例外ハンドラ自身が落ちる</b>。
     * 元の例外が何だったのか分からなくなるので、必ず代替の文言を入れる。
     */
    private static final String FALLBACK_MESSAGE = "予期しないエラーが発生しました";

    /**
     * 登録済みチャンネルの重複登録を 409 Conflict として返す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(ChannelAlreadyRegisteredException.class)
    public ResponseEntity<Map<String, String>> handleChannelAlreadyRegistered(ChannelAlreadyRegisteredException e) {
        return clientError(HttpStatus.CONFLICT, e);
    }

    /**
     * 監視サイクルの実行中に重ねて手動チェックを要求した場合を 409 Conflict として返す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(MonitoringInProgressException.class)
    public ResponseEntity<Map<String, String>> handleMonitoringInProgress(MonitoringInProgressException e) {
        return clientError(HttpStatus.CONFLICT, e);
    }

    /**
     * 存在しないチャンネルの指定を 404 Not Found として返す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(ChannelNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleChannelNotFound(ChannelNotFoundException e) {
        return clientError(HttpStatus.NOT_FOUND, e);
    }

    /**
     * 存在しない録画履歴の指定を 404 Not Found として返す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(RecordingNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleRecordingNotFound(RecordingNotFoundException e) {
        return clientError(HttpStatus.NOT_FOUND, e);
    }

    /**
     * 録画中の録画履歴を削除しようとした場合を 409 Conflict として返す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(RecordingInProgressException.class)
    public ResponseEntity<Map<String, String>> handleRecordingInProgress(RecordingInProgressException e) {
        return clientError(HttpStatus.CONFLICT, e);
    }

    /**
     * 既に録画履歴がある動画を重ねてダウンロードしようとした場合を 409 Conflict として返す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(VideoAlreadyDownloadedException.class)
    public ResponseEntity<Map<String, String>> handleVideoAlreadyDownloaded(VideoAlreadyDownloadedException e) {
        return clientError(HttpStatus.CONFLICT, e);
    }

    /**
     * 配信中・配信開始前の URL の手動ダウンロードを 400 Bad Request として返す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(LiveStreamDownloadRejectedException.class)
    public ResponseEntity<Map<String, String>> handleLiveStreamDownloadRejected(
            LiveStreamDownloadRejectedException e) {
        return clientError(HttpStatus.BAD_REQUEST, e);
    }

    /**
     * 入力値の誤りを 400 Bad Request として返す。
     *
     * <p>チャンネル登録で「ハンドルに該当するチャンネルが見つかりません」となる経路もここを通る。
     * 利用者は画面で気づけるが、管理者が後から追えるようログにも必ず残す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return clientError(HttpStatus.BAD_REQUEST, e);
    }

    /**
     * サーバー側の想定外の状態を 500 Internal Server Error として返す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return serverError(e);
    }

    /**
     * 存在しないパスへのアクセスを 404 Not Found として返す。
     *
     * <p><b>汎用の {@link Exception} ハンドラを足したことで、これが 500 になっていた（実際に発生した）。</b>
     * 静的リソースが見つからないのは Spring が既定で 404 にしていたが、汎用ハンドラが先に捕まえて
     * サーバー異常として扱い、<b>存在しない URL を叩かれるたびに ERROR ログが出る</b>状態になっていた。
     * 自動巡回や打ち間違いでも起きるため、放置すると本当の異常がログに埋もれる。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResourceFound(NoResourceFoundException e) {
        // 利用者側の誤りなので WARN 止まり。スタックトレースは残さない
        return clientError(HttpStatus.NOT_FOUND, e);
    }

    /**
     * 上記のいずれにも当てはまらない例外を 500 Internal Server Error として返す。
     *
     * <p><b>この受け口が無いと、想定していなかった例外はこのクラスを素通りする。</b>
     * 応答の形式が {@code {"error": ...}} から外れて画面のエラー表示が働かなくなるうえ、
     * ログもフレームワーク任せになり、アプリのログとして追えなくなる。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        return serverError(e);
    }

    /**
     * 利用者の操作で起こりうる失敗を記録して返す。
     *
     * <p>想定内の失敗なのでスタックトレースは残さない（出すと本当に異常なログが埋もれる）。
     * ただし<b>記録そのものは必ず残す</b>——どの操作がなぜ弾かれたのかを、
     * 後から管理者が追えるようにするため。
     *
     * @param status 返す HTTP ステータス
     * @param e      発生した例外
     * @return エラー内容を含むレスポンス
     */
    private ResponseEntity<Map<String, String>> clientError(HttpStatus status, Exception e) {
        log.warn("リクエストを処理できませんでした: status={}, type={}, reason={}",
                status.value(), e.getClass().getSimpleName(), e.getMessage());
        return ResponseEntity.status(status).body(Map.of(ERROR_KEY, messageOf(e)));
    }

    /**
     * サーバー側の異常を記録して返す。原因調査が要るのでスタックトレースごと残す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    private ResponseEntity<Map<String, String>> serverError(Exception e) {
        log.error("サーバー内部でエラーが発生しました", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(ERROR_KEY, messageOf(e)));
    }

    /**
     * 応答に載せるメッセージを決める。メッセージを持たない例外でも {@code null} を返さない。
     *
     * @param e 発生した例外
     * @return エラー理由の文言
     */
    private String messageOf(Exception e) {
        return e.getMessage() == null ? FALLBACK_MESSAGE : e.getMessage();
    }
}
