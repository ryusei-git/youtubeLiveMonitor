package com.example.monitor.service;

import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.exception.ChannelAlreadyRegisteredException;
import com.example.monitor.exception.ChannelNotFoundException;
import com.example.monitor.platform.Platform;
import com.example.monitor.platform.StreamPlatform;
import com.example.monitor.platform.StreamPlatformRegistry;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.repository.UserSubscriptionRepository;
import com.example.monitor.util.DatabaseUpdateVerifier;
import com.example.monitor.util.FileNameUtils;
import com.example.monitor.util.ProcessTermination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 監視対象チャンネルの登録・削除・一覧取得をまとめて扱う。
 *
 * <p>REST API と CLI の両方から呼ばれる。両者が個別にリポジトリを操作すると
 * 重複チェックのような判断が二重に実装され、片方だけ直し忘れる原因になるため、
 * 判断はすべてこのクラスに集約している。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonitoredChannelService {

    private final MonitoredChannelRepository monitoredChannelRepository;
    private final StreamPlatformRegistry streamPlatformRegistry;
    private final ChannelLogReader channelLogReader;
    private final AuditLogger auditLogger;
    private final RecordingRepository recordingRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final ProcessLauncher processLauncher;

    /**
     * 登録済みの監視対象を全件返す。
     *
     * @return 監視対象の一覧
     */
    public List<MonitoredChannel> findAll() {
        return monitoredChannelRepository.findAll();
    }

    /**
     * チャンネルごとの再生できる録画の件数を返す。
     *
     * <p>数えるのは {@code COMPLETED} と {@code PARTIAL}（途中で止まったが再生できるよう直したもの）。
     * {@code RECORDING} は完成しておらず、{@code FAILED} は中身が無いため、利用者が見られる録画に含めない。
     *
     * @return チャンネルの主キーから件数への対応。録画が無いチャンネルは含まない
     */
    public Map<Long, Long> countPlayableRecordingsByChannel() {
        return recordingRepository.countByChannel(List.of(RecordingStatus.COMPLETED, RecordingStatus.PARTIAL))
                .stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }

    /**
     * チャンネルごとの録画中（{@code RECORDING}）の録画の件数を返す。
     *
     * <p>DB の状態で数えるので、再起動で完了の記録が失われた行（{@code RecordingReconciler} が補正するまで
     * {@code RECORDING} のまま残る）も録画中に数える。補正は巡回と同じ間隔で走るため、食い違いは一時的。
     *
     * @return チャンネルの主キーから件数への対応。録画中の録画が無いチャンネルは含まない
     */
    public Map<Long, Long> countRecordingNowByChannel() {
        return recordingRepository.countByChannel(List.of(RecordingStatus.RECORDING))
                .stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }

    /**
     * チャンネルごとの購読者数を返す。
     *
     * <p>チャンネルを削除すると購読も連鎖で消えるため、管理者が削除する前に
     * 誰かが購読しているかを見分けられるよう一覧に添える。
     *
     * @return チャンネルの主キーから購読者数への対応。購読されていないチャンネルは含まない
     */
    public Map<Long, Long> countSubscribersByChannel() {
        return userSubscriptionRepository.countByChannel()
                .stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }

    /**
     * チャンネルごとの購読者名を返す。
     *
     * <p>人数だけでは誰の購読が消えるのか分からないため、管理者の一覧に購読者の名前を添える。
     * 並び順はクエリの利用者名順を保つ（{@code groupingBy} の下流の {@code toList} は出現順を保つ）。
     *
     * @return チャンネルの主キーから購読者名の一覧への対応。購読されていないチャンネルは含まない
     */
    public Map<Long, List<String>> subscriberNamesByChannel() {
        return userSubscriptionRepository.findSubscriberNamesByChannel()
                .stream()
                .collect(Collectors.groupingBy(row -> (Long) row[0],
                        Collectors.mapping(row -> (String) row[1], Collectors.toList())));
    }

    /**
     * チャンネルを監視対象に登録する。
     *
     * <p>入力の形（URL・ハンドル・ログイン名・ID）はプラットフォームごとに違うため、
     * <b>整形と識別子への解決は {@link StreamPlatform#normalizeChannelInput} に任せる</b>。
     * ここで各プラットフォームの事情を分岐させると、プラットフォームが増えるたびに
     * このメソッドが膨らんでいくため。
     *
     * <p>保存される識別子が「監視に使える不変の ID」であることは、この解決処理が保証している。
     * 迂回して直接リポジトリへ保存しないこと（YouTube はハンドルのまま保存すると 404 になり、
     * Twitch はログイン名のまま保存すると改名で監視が静かに止まる）。
     *
     * @param platform            どのプラットフォームのチャンネルか
     * @param channelInput        チャンネルの指定（URL・ハンドル・ログイン名・ID のいずれか）
     * @param channelName         表示用のチャンネル名
     * @param recordEnabled       配信を検知した際に自動録画するか
     * @param recordTitleKeywords 録画・通知の対象を絞り込むタイトルキーワード（カンマ区切り）。
     *                            {@code null} や空文字なら絞り込みなし
     * @return 登録された監視対象
     * @throws ChannelAlreadyRegisteredException 同じチャンネルが既に登録されている場合
     * @throws IllegalArgumentException          入力に該当するチャンネルが見つからない場合
     */
    public MonitoredChannel register(Platform platform, String channelInput, String channelName,
                                     boolean recordEnabled, String recordTitleKeywords) {
        String resolvedChannelId = streamPlatformRegistry.get(platform).normalizeChannelInput(channelInput);

        if (monitoredChannelRepository.existsByYoutubeChannelId(resolvedChannelId)) {
            throw new ChannelAlreadyRegisteredException(resolvedChannelId);
        }
        MonitoredChannel saved = monitoredChannelRepository.save(
                new MonitoredChannel(platform, resolvedChannelId, channelName, recordEnabled, recordTitleKeywords));
        log.info("監視対象に追加しました: platform={}, name={}, channel={}, recordEnabled={}, titleKeywords={}",
                platform, channelName, resolvedChannelId, recordEnabled, recordTitleKeywords);
        recordChannelAction(AuditAction.CHANNEL_REGISTER, saved.getId(),
                "platform=" + platform + ", channel=" + resolvedChannelId + ", name=" + channelName);
        return saved;
    }

    /**
     * 監視対象に登録済みならそれを返し、無ければ登録する。
     *
     * <p><b>{@link #register} と分けているのは、購読では「既にある」が異常ではないため。</b>
     * 同じ配信者を複数の利用者が購読でき、そのとき巡回は 1 回で済ませたいので、
     * {@code channels} は増やさず購読だけを増やす。{@code register} は
     * 管理者が明示的に新規登録する操作なので、重複を
     * {@link ChannelAlreadyRegisteredException} で弾くままにしてある。
     *
     * <p>新規に作る場合、<b>録画は無効で登録する</b>。録画はチャンネル単位の設定で
     * ディスクも消費するため、利用者の購読操作で勝手に有効にはしない（管理者が決める）。
     *
     * <p>入力の正規化は {@code StreamPlatform.normalizeChannelInput()} に任せる。
     * ここで分岐を書くと、プラットフォームが増えるたびに膨らむ。
     *
     * <p><b>表示名が空なら、プラットフォームの公式な名前を取って使う</b>（{@link StreamPlatform#fetchChannelTitle}）。
     * 以前は識別子をそのまま名前にしていたため、URL を貼って名前を空のまま追加すると、マイチャンネル・アーカイブ・
     * 通知履歴・管理画面に {@code UC...} が並び、利用者には直す手段が無かった。取れなければ識別子で代用する
     * （名前のために購読を失敗させない）。
     *
     * <p><b>新しく作るときだけ、巡回と同じ問い合わせ（{@link StreamPlatform#detectLiveStream(String)}）で
     * 確かめ、判定できなければ作らない。</b>確かめないと、一般利用者が購読と解除を繰り返すだけで、
     * 存在しないチャンネルを監視対象に際限なく足せた（解除で消えるのは購読の行だけで、
     * 購読の上限は今の購読数しか見ないため）。YouTube の {@code /channel/{存在しない ID}/live} は
     * 404 ではなく 200 を返し、canonical が無いので「判定できなかった」になる（2026-09 に確認）。
     * ID の形（UC＋22 文字）だけでは見分けられない。巡回と同じ問い合わせを使うのは、ここで判定できない
     * チャンネルは登録しても毎巡回「判定失敗」になるだけで、クォータも使わないため。
     * 一時的な通信の失敗でも断るが、検知が壊れている間は登録しても監視できないので、断る側に倒している。
     *
     * <p><b>検知の結果は保存しない。</b>ここで配信中の印を書くと、次の巡回が配信開始として扱わず
     * 通知が飛ばない。既にあるチャンネルは確かめない（巡回が見ているうえ、一時的な失敗で
     * 既存のチャンネルの購読まで断らないため）。
     *
     * <p>新しく作ったときは {@code CHANNEL_REGISTER} を監査ログに残す（detail の末尾に {@code via=subscribe}）。
     * {@link #register} と同じく、誰が監視対象を増やしたかを管理者が追えるようにするため。
     * 購読と解除を繰り返して<b>実在する</b>チャンネルを増やすことは、まだ防いでいない（監査ログで追える）。
     *
     * @param platform     プラットフォーム
     * @param channelInput 利用者の入力（チャンネル ID・ハンドル・ログイン名・URL）
     * @param channelName  新規登録時に使う表示名。空なら公式な名前を取り、それも取れなければ解決後の識別子を使う
     * @return 既存または新規の監視対象
     * @throws IllegalArgumentException 入力に該当するチャンネルが見つからない場合、または新しく作るチャンネルの配信状態を判定できなかった場合
     */
    public MonitoredChannel findOrRegister(Platform platform, String channelInput, String channelName) {
        StreamPlatform streamPlatform = streamPlatformRegistry.get(platform);
        String resolvedChannelId = streamPlatform.normalizeChannelInput(channelInput);

        return monitoredChannelRepository.findByYoutubeChannelId(resolvedChannelId)
                .orElseGet(() -> {
                    // 巡回と同じ問い合わせで確かめる。結果は保存しない（理由はこのメソッドの JavaDoc）
                    if (streamPlatform.detectLiveStream(resolvedChannelId).isDetectionFailed()) {
                        throw new IllegalArgumentException("チャンネルが見つからないか、今は "
                                + platform.displayName() + " に確かめられませんでした（" + resolvedChannelId
                                + "）。URL を確かめて、時間をおいてもう一度試してください");
                    }
                    // 公式名を取るのは新しく作るときだけ（既存のチャンネルなら名前をそもそも使わない）。
                    // 存在の確認の後に取るのは、存在しないチャンネルのためにクォータを使わないため
                    String name = channelName == null || channelName.isBlank()
                            ? streamPlatform.fetchChannelTitle(resolvedChannelId).orElse(resolvedChannelId)
                            : channelName;
                    MonitoredChannel saved = monitoredChannelRepository.save(
                            new MonitoredChannel(platform, resolvedChannelId, name, false, null));
                    log.info("購読により監視対象へ追加しました: platform={}, name={}, channel={}",
                            platform, name, resolvedChannelId);
                    recordChannelAction(AuditAction.CHANNEL_REGISTER, saved.getId(),
                            "platform=" + platform + ", channel=" + resolvedChannelId + ", name=" + name
                                    + ", via=subscribe");
                    return saved;
                });
    }

    /**
     * 監視対象を削除する。紐づく通知履歴は DB の連鎖削除で、チャンネル別ログファイルは
     * このメソッドが明示的に削除する。DB は連鎖削除で消えるのにログファイルだけ
     * ディスクに残り続けるのは片手落ちなため（実際に指摘を受けた）。
     *
     * <p><b>録画中の yt-dlp も子孫ごと止める。</b>止めないと、録画履歴の行が消えて画面から見えないまま
     * 録り続ける（実際に発生した：削除したチャンネルの {@code --live-from-start} が約 1 時間で 6.9GB を書いた。
     * {@link RecordingReconciler} は「プロセスが動いている」ので触らず、出力が増え続けるので
     * {@link StreamRecorder} の固まりの見張りも止めない）。止める録画は削除の<b>前</b>に集める
     * （削除すると録画履歴も連鎖削除で消える）。プロセスは OS から探すので、再起動で追跡を失った録画も止まる。
     * 探す文字列は動画 ID ではなく出力先（{@code <チャンネルID>/<動画ID>.%(ext)s}）にする。動画 ID だけだと、
     * 同じ動画を「端末に保存」している利用者の yt-dlp（{@link DeviceDownloadService}。出力先は一時フォルダー）
     * まで止めてしまう（{@link StreamRecorder#stopRecording(Long)} と同じ理由）。
     * 録画ファイルは消さない（削除済みチャンネルの録画ファイルは孤立ファイルの削除で片付ける設計のまま）。
     *
     * <p>止める処理は、SIGKILL へ切り替えるまで最大 30 秒待つため、仮想スレッドで行い HTTP の応答を待たせない。
     * 追跡中の録画スレッドは、プロセスが終わると行が無いことを見て記録も録り直しもしない
     * （{@code StreamRecorder.awaitCompletion} 参照）。
     *
     * <p><b>止め終わったかは戻り値で返す。</b>Web は待たない（HTTP の応答を待たせない）が、CLI は待つ必要がある。
     * CLI はコマンドが終わるとすぐ {@code System.exit} し、デーモンである仮想スレッドはそこで打ち切られるため、
     * 待たないと SIGTERM を送る前や SIGKILL へ切り替える前に止める処理が消え、削除したチャンネルの録画が続く
     * （{@link com.example.monitor.cli.ChannelRemoveCommand} 参照）。
     *
     * @param channelRecordId 監視対象の主キー（YouTube のチャンネル ID ではない）
     * @return 録画中の yt-dlp を止め終わると、止めた数で完了する。止める録画が無ければ {@code 0} で完了済み。
     *         止める処理が例外で終わった場合はその例外で完了する（WARN を出し済み）
     * @throws ChannelNotFoundException 指定 ID の監視対象が存在しない場合
     */
    public CompletableFuture<Integer> remove(Long channelRecordId) {
        MonitoredChannel channel = monitoredChannelRepository.findById(channelRecordId)
                .orElseThrow(() -> new ChannelNotFoundException(channelRecordId));

        List<String> recordingFilePaths = recordingRepository.findRecordingFilePathsByChannelId(channelRecordId);

        monitoredChannelRepository.deleteById(channelRecordId);
        channelLogReader.deleteChannelLogs(channel.getYoutubeChannelId());
        CompletableFuture<Integer> recordingsStopped;
        if (recordingFilePaths.isEmpty()) {
            recordingsStopped = CompletableFuture.completedFuture(0);
        } else {
            Executor stopThread = task -> Thread.ofVirtual().name("stop-recordings-" + channelRecordId).start(task);
            recordingsStopped = CompletableFuture
                    .supplyAsync(() -> stopRecordings(channel.getYoutubeChannelId(), recordingFilePaths), stopThread)
                    .whenComplete((stopped, failure) -> {
                        if (failure != null) {
                            log.warn("削除したチャンネルの録画プロセスを止められませんでした: channel={}",
                                    channel.getYoutubeChannelId(), failure);
                        }
                    });
        }

        log.info("監視対象から削除しました: id={}, channel={}", channelRecordId, channel.getYoutubeChannelId());
        recordChannelAction(AuditAction.CHANNEL_DELETE, channelRecordId, "channel=" + channel.getYoutubeChannelId());
        return recordingsStopped;
    }

    /**
     * 削除したチャンネルの録画の yt-dlp を子孫ごと止める。理由は {@link #remove(Long)} を参照。
     *
     * <p>探す文字列は {@link StreamRecorder#stopRecording(Long)} と同じ形にする。片方だけ変えると、
     * 管理画面からの停止とチャンネルの削除とで、止める yt-dlp がずれる。
     *
     * @param youtubeChannelId 削除したチャンネルの識別子（ログ用）
     * @param filePaths        止める録画の保存先（{@code <チャンネルID>/<動画ID>.mp4}）
     * @return 止めた yt-dlp の数（子孫は数えない）
     */
    private int stopRecordings(String youtubeChannelId, List<String> filePaths) {
        int stopped = 0;
        for (String filePath : filePaths) {
            String outputFragment = FileNameUtils.stripExtension(filePath, ".mp4") + ".%(ext)s";
            for (ProcessHandle handle : processLauncher.findYtDlpProcessesWithCommandLineContaining(outputFragment)) {
                log.info("削除したチャンネルの録画プロセスを止めます: channel={}, file={}, pid={}",
                        youtubeChannelId, filePath, handle.pid());
                if (ProcessTermination.terminateTreeAndAwait(handle, Duration.ofSeconds(30))) {
                    return stopped;
                }
                stopped++;
                log.info("削除したチャンネルの録画プロセスを止めました: channel={}, file={}, pid={}",
                        youtubeChannelId, filePath, handle.pid());
            }
        }
        return stopped;
    }

    /**
     * 自動録画の有効・無効を切り替える。
     *
     * @param channelRecordId 監視対象の主キー
     * @param recordEnabled   録画を有効にするか
     * @throws ChannelNotFoundException 指定 ID の監視対象が存在しない場合
     */
    public void setRecordEnabled(Long channelRecordId, boolean recordEnabled) {
        if (!monitoredChannelRepository.existsById(channelRecordId)) {
            throw new ChannelNotFoundException(channelRecordId);
        }
        DatabaseUpdateVerifier.verify(
                monitoredChannelRepository.updateRecordEnabled(channelRecordId, recordEnabled),
                "録画設定の変更", channelRecordId);
        log.info("録画設定を変更しました: id={}, recordEnabled={}", channelRecordId, recordEnabled);
        recordChannelAction(AuditAction.CHANNEL_SETTING_CHANGE, channelRecordId, "recordEnabled=" + recordEnabled);
    }

    /**
     * 録画対象を絞り込むタイトルキーワードを設定する。
     *
     * @param channelRecordId 監視対象の主キー
     * @param titleKeywords   絞り込みキーワード（カンマ区切り）。空またはnullで絞り込み解除
     * @throws ChannelNotFoundException 指定 ID の監視対象が存在しない場合
     */
    public void setRecordTitleKeywords(Long channelRecordId, String titleKeywords) {
        if (!monitoredChannelRepository.existsById(channelRecordId)) {
            throw new ChannelNotFoundException(channelRecordId);
        }
        DatabaseUpdateVerifier.verify(
                monitoredChannelRepository.updateRecordTitleKeywords(channelRecordId, titleKeywords),
                "タイトルフィルターの変更", channelRecordId);
        log.info("録画タイトルフィルターを変更しました: id={}, titleKeywords={}", channelRecordId, titleKeywords);
        recordChannelAction(AuditAction.CHANNEL_SETTING_CHANGE, channelRecordId, "titleKeywords=" + titleKeywords);
    }

    /**
     * チャンネル関連の操作を監査ログへ記録する。
     *
     * <p>操作者と操作元の IP は {@link AuditLogger#recordByCurrentUser} が埋める。このクラスは CLI からも
     * 呼ばれる。CLI にはログインも HTTP の要求も無いので、そのときは利用者と IP が空欄のまま記録される
     * （{@link InvitationService} の招待受け入れ記録と同じ考え方）。
     *
     * @param action          操作の種別
     * @param channelRecordId 対象チャンネルの主キー
     * @param detail          補足情報
     */
    private void recordChannelAction(AuditAction action, Long channelRecordId, String detail) {
        auditLogger.recordByCurrentUser(action, AuditOutcome.SUCCESS,
                "CHANNEL", String.valueOf(channelRecordId), detail);
    }

}
