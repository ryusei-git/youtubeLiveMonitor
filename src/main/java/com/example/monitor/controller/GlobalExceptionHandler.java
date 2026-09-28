package com.example.monitor.controller;

import com.example.monitor.exception.ChannelAlreadyRegisteredException;
import com.example.monitor.exception.ChannelNotFoundException;
import com.example.monitor.exception.DeviceDownloadInProgressException;
import com.example.monitor.exception.DeviceDownloadNotFoundException;
import com.example.monitor.exception.InsufficientDiskSpaceException;
import com.example.monitor.exception.LiveStreamDownloadRejectedException;
import com.example.monitor.exception.MonitoringDisabledException;
import com.example.monitor.exception.MonitoringInProgressException;
import com.example.monitor.exception.RecordingInProgressException;
import com.example.monitor.exception.RecordingNotFoundException;
import com.example.monitor.exception.SearchQuotaExceededException;
import com.example.monitor.exception.ServiceDownloadInProgressException;
import com.example.monitor.exception.SoundDetectionConflictException;
import com.example.monitor.exception.SoundDetectionNotFoundException;
import com.example.monitor.exception.SoundMarkNotFoundException;
import com.example.monitor.exception.TooManyPasswordAttemptsException;
import com.example.monitor.exception.VideoAlreadyDownloadedException;
import com.example.monitor.exception.YouTubeApiUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;

import java.sql.SQLException;
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
     * 一意制約（主キーを含む）の違反を表す SQLSTATE。H2 もこの値を返す。
     *
     * <p>重複だけをこれで見分け、列の長さ・型・NOT NULL の違反とは扱いを分ける
     * （{@link #handleDataIntegrityViolation} 参照）。
     */
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    /** 重複の違反（同じ要求の重なり）を利用者へ返すときの文言。 */
    private static final String DUPLICATE_MESSAGE =
            "同じ操作が重なったため保存できませんでした。画面を読み込み直してからやり直してください";

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
     * 監視を止めた起動で手動チェックを要求した場合を 409 Conflict として返す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(MonitoringDisabledException.class)
    public ResponseEntity<Map<String, String>> handleMonitoringDisabled(MonitoringDisabledException e) {
        return clientError(HttpStatus.CONFLICT, e);
    }

    /**
     * YouTube の検索が本日の上限に達した場合を 429 Too Many Requests として返す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(SearchQuotaExceededException.class)
    public ResponseEntity<Map<String, String>> handleSearchQuotaExceeded(SearchQuotaExceededException e) {
        return clientError(HttpStatus.TOO_MANY_REQUESTS, e);
    }

    /**
     * パスワード変更で今のパスワードの照合に続けて失敗した場合を 429 Too Many Requests として返す。
     *
     * <p>{@code Retry-After} を付ける。ログインの試行制限（{@code LoginAttemptFilter}）と同じく、
     * いつ再試行できるかを伝えるため。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(TooManyPasswordAttemptsException.class)
    public ResponseEntity<Map<String, String>> handleTooManyPasswordAttempts(TooManyPasswordAttemptsException e) {
        ResponseEntity<Map<String, String>> response = clientError(HttpStatus.TOO_MANY_REQUESTS, e);
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.getRetryAfterSeconds()))
                .body(response.getBody());
    }

    /**
     * YouTube Data API を使えない状態（キー未設定・API の失敗）を 503 Service Unavailable として返す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(YouTubeApiUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleYouTubeApiUnavailable(YouTubeApiUnavailableException e) {
        return clientError(HttpStatus.SERVICE_UNAVAILABLE, e);
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
     * 空き容量が足りずにダウンロードを始めない場合を 503 Service Unavailable として返す。
     *
     * <p>サーバーの異常ではなく「今は受け付けられない」状態なので、スタックトレースは残さない。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(InsufficientDiskSpaceException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientDiskSpace(InsufficientDiskSpaceException e) {
        return clientError(HttpStatus.SERVICE_UNAVAILABLE, e);
    }

    /**
     * 同じ利用者が「端末に保存」を重ねて始めようとした場合を 409 Conflict として返す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(DeviceDownloadInProgressException.class)
    public ResponseEntity<Map<String, String>> handleDeviceDownloadInProgress(DeviceDownloadInProgressException e) {
        return clientError(HttpStatus.CONFLICT, e);
    }

    /**
     * 一般利用者が「サービスに保存」を重ねて始めようとした場合を 409 Conflict として返す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(ServiceDownloadInProgressException.class)
    public ResponseEntity<Map<String, String>> handleServiceDownloadInProgress(ServiceDownloadInProgressException e) {
        return clientError(HttpStatus.CONFLICT, e);
    }

    /**
     * 見つからない（他人のものを含む）「端末に保存」の仕事の指定を 404 Not Found として返す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(DeviceDownloadNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleDeviceDownloadNotFound(DeviceDownloadNotFoundException e) {
        return clientError(HttpStatus.NOT_FOUND, e);
    }

    /**
     * 消せない（無い・別の録画のもの・他人のもの）音の印の指定を 404 Not Found として返す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(SoundMarkNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleSoundMarkNotFound(SoundMarkNotFoundException e) {
        return clientError(HttpStatus.NOT_FOUND, e);
    }

    /**
     * 答えられない（無い・別の録画のもの・今の版でない）候補と、無い実行記録の指定を 404 Not Found として返す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(SoundDetectionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleSoundDetectionNotFound(SoundDetectionNotFoundException e) {
        return clientError(HttpStatus.NOT_FOUND, e);
    }

    /**
     * 検出を今すぐ始められない（検出が走っている・今の版で検出済み・検出できない録画）場合を 409 Conflict として返す。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(SoundDetectionConflictException.class)
    public ResponseEntity<Map<String, String>> handleSoundDetectionConflict(SoundDetectionConflictException e) {
        return clientError(HttpStatus.CONFLICT, e);
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
     * 型の合わないパラメーター（数値の欄の文字・知らない選択肢・読めない日付など）を 400 Bad Request として返す。
     *
     * <p>これを拾わないと catch-all に落ちて 500 になり、URL を打ち間違えただけで
     * ERROR とスタックトレースが残る（{@code /api/logs/system?limit=abc} など）。
     * 文面はパラメーター名から自分で組み立てる。枠組みの文面は英語で、型のクラス名を含むため外に出さない。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return clientError(HttpStatus.BAD_REQUEST, e, e.getName() + "：値の形式が正しくありません");
    }

    /**
     * 必須のパラメーターが無い要求を 400 Bad Request として返す。
     *
     * <p>これも拾わないと 500 になる。{@code /api/password-reset} はログインせずに呼べるので、
     * 誰でも ERROR とスタックトレースを積み上げられる状態になっていた。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParameter(MissingServletRequestParameterException e) {
        return clientError(HttpStatus.BAD_REQUEST, e, e.getParameterName() + "：指定してください");
    }

    /**
     * 読めない本文（壊れた JSON・項目の型違い・知らない選択肢・本文なし）を 400 Bad Request として返す。
     *
     * <p>枠組みの文面は Jackson の英語の説明で、受け取った値の一部を含むことがあるため外に出さない
     * （ログには WARN で残る）。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        return clientError(HttpStatus.BAD_REQUEST, e,
                "送られた内容を読み取れませんでした（JSON の形式と項目の型を確かめてください）");
    }

    /**
     * JSON 以外の形式（{@code text/plain} など）で送られた本文を 415 Unsupported Media Type として返す。
     *
     * <p>本文を受け取る API はすべて JSON なので、文言も JSON に決め打ちにする。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return clientError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e,
                "送る内容は JSON（Content-Type: application/json）にしてください");
    }

    /**
     * DB の制約の違反のうち、<b>重複（一意制約）だけ</b>を 409 Conflict として返す。
     *
     * <p>「無ければ作る」を確かめてから保存する処理（録画の印・購読・監視対象の登録）は、
     * 同じ要求が重なると両方が「無い」と判断して保存し、片方が一意制約で失敗する。
     * 利用者の操作の重なりなので、サーバーの異常（500・ERROR）として扱わず、やり直せば通ることを伝える。
     *
     * <p><b>重複以外（列の長さ・型・NOT NULL・外部キー）は今までどおり 500 と ERROR にする。</b>
     * これらはコードや DB の不具合でも起きる。列挙子を足して H2 の ENUM 列が値を拒んだ事故では、
     * 巡回 API の 500 で気づいた（docs/pitfalls.md）。まとめて 4xx と WARN にすると、その合図が消える。
     *
     * @param e 発生した例外
     * @return エラー内容を含むレスポンス
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        if (isUniqueViolation(e)) {
            return clientError(HttpStatus.CONFLICT, e, DUPLICATE_MESSAGE);
        }
        return serverError(e, FALLBACK_MESSAGE);
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

    /**
     * 例外の原因をたどり、一意制約の違反を表す {@link SQLException} があるかを返す。
     *
     * <p>JPA 経由では Hibernate の例外に、JDBC 経由では Spring の例外に包まれて届く。
     * 包み方に依らないよう、元の {@link SQLException} の SQLSTATE で判定する。
     *
     * @param e 調べる例外
     * @return 一意制約の違反なら {@code true}
     */
    private static boolean isUniqueViolation(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && UNIQUE_VIOLATION_SQL_STATE.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
