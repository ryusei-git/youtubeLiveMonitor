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
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;

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
 *
 * <p>ただし、動画を見ている途中でブラウザが接続を切ったのは失敗ではないので、
 * {@code DEBUG} を 1 行だけ残す（{@link #handleUnexpected}）。
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

    /** 存在しないパスへの応答。枠組みの文言を外に出さないために決め打ちにする。 */
    private static final String NOT_FOUND_MESSAGE = "指定されたパスは存在しません";

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
        // 利用者側の誤りなので WARN 止まり。スタックトレースは残さない。
        // 応答には枠組みの文言（"No static resource ..."）をそのまま載せない。
        // 静的リソースとして解決を試みて失敗した、という内部の挙動が外から分かるため
        return clientError(HttpStatus.NOT_FOUND, e, NOT_FOUND_MESSAGE);
    }

    /**
     * 入力の検証に失敗した場合を 400 Bad Request として返す。
     *
     * <p>これを拾わないと catch-all に落ちて<b>500 になる</b>。利用者の入力誤りを
     * サーバーの異常として扱うと、原因が利用者側にあることが伝わらないうえ、
     * ログに ERROR とスタックトレースが積み上がる。
     *
     * <p>文面は<b>項目名と制約から自分で組み立てる</b>。枠組みが付ける既定の文面は
     * 英語で、クラス名や制約の内部表現を含むため外に出さない。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationFailed(MethodArgumentNotValidException e) {
        String reason = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + "：" + error.getDefaultMessage())
                .findFirst()
                .orElse("入力内容を確認してください");
        return clientError(HttpStatus.BAD_REQUEST, e, reason);
    }

    /**
     * 対応していない HTTP メソッドでの要求を 405 Method Not Allowed として返す。
     *
     * <p>これも拾わないと catch-all に落ちて 500 になる。
     * 利用者側の誤りなので、サーバーの異常として扱わない。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e) {
        return clientError(HttpStatus.METHOD_NOT_ALLOWED, e, "この操作はこのパスでは行えません");
    }

    /**
     * 上記のいずれにも当てはまらない例外を 500 Internal Server Error として返す。
     *
     * <p><b>この受け口が無いと、想定していなかった例外はこのクラスを素通りする。</b>
     * 応答の形式が {@code {"error": ...}} から外れて画面のエラー表示が働かなくなるうえ、
     * ログもフレームワーク任せになり、アプリのログとして追えなくなる。
     *
     * <h4>ブラウザの切断はサーバーの異常として扱わない</h4>
     * 録画の動画を見ている途中でシークやページ移動をすると、ブラウザは接続を切る。
     * 送りかけの動画の書き込みが失敗してここに来るたびに、<b>スタックトレース付きの ERROR を
     * 残していた</b>（起動 35 分で 169 回、{@code service.log} 4 万行のうち 3.9 万行、#184）。
     * さらに、Content-Type が {@code video/mp4} のまま送り始めた応答へ JSON を書こうとして失敗し、
     * Spring の「Failure in @ExceptionHandler」の WARN とトレースがもう 1 回出ていた。
     * 受け取る相手はもういないので、DEBUG を 1 行だけ残し、応答には何も書かずに終える。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス。ブラウザが切断していた場合は {@code null}
     *         （Spring は応答に何も書かない）
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        if (DisconnectedClientHelper.isClientDisconnectedException(e)) {
            log.debug("ブラウザが切断したため応答を書かずに終えます: type={}, reason={}",
                    e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
        // 何が飛んでくるか分からない経路。DB のエラー文やファイルパスがそのまま
        // 応答に載りうるので、利用者へは決まった文言だけを返す（詳細はログに残る）
        return serverError(e, FALLBACK_MESSAGE);
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
        return clientError(status, e, messageOf(e));
    }

    /**
     * 応答に載せる文言を指定して、利用者側の誤りを記録して返す。
     *
     * <p><b>自分たちが書いていない例外のメッセージを返さない</b>ために使う。
     * 枠組みやライブラリが付ける文面は内部の挙動を映すので、外に出す理由がない。
     *
     * @param status        返すステータス
     * @param e             発生した例外
     * @param clientMessage 応答に載せる文言
     * @return エラー内容を含むレスポンス
     */
    private ResponseEntity<Map<String, String>> clientError(
            HttpStatus status, Exception e, String clientMessage) {
        log.warn("リクエストを処理できませんでした: status={}, type={}, reason={}",
                status.value(), e.getClass().getSimpleName(), e.getMessage());
        return ResponseEntity.status(status).body(Map.of(ERROR_KEY, clientMessage));
    }

    /**
     * サーバー側の異常を記録して返す。原因調査が要るのでスタックトレースごと残す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    private ResponseEntity<Map<String, String>> serverError(Exception e) {
        return serverError(e, messageOf(e));
    }

    /**
     * 応答に載せる文言を指定して、サーバー側の異常を記録して返す。
     *
     * @param e             発生した例外
     * @param clientMessage 応答に載せる文言
     * @return エラー内容を含むレスポンス
     */
    private ResponseEntity<Map<String, String>> serverError(Exception e, String clientMessage) {
        log.error("サーバー内部でエラーが発生しました", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(ERROR_KEY, clientMessage));
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
