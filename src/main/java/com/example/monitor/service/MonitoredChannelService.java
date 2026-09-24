package com.example.monitor.service;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.exception.ChannelAlreadyRegisteredException;
import com.example.monitor.exception.ChannelNotFoundException;
import com.example.monitor.platform.Platform;
import com.example.monitor.platform.StreamPlatform;
import com.example.monitor.platform.StreamPlatformRegistry;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.util.DatabaseUpdateVerifier;
import com.example.monitor.util.RequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
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
    private final AppUserRepository appUserRepository;
    private final AuditLogger auditLogger;
    private final RecordingRepository recordingRepository;

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
     * @param platform     プラットフォーム
     * @param channelInput 利用者の入力（チャンネル ID・ハンドル・ログイン名・URL）
     * @param channelName  新規登録時に使う表示名。空なら解決後の識別子を使う
     * @return 既存または新規の監視対象
     * @throws IllegalArgumentException 入力に該当するチャンネルが見つからない場合
     */
    public MonitoredChannel findOrRegister(Platform platform, String channelInput, String channelName) {
        String resolvedChannelId = streamPlatformRegistry.get(platform).normalizeChannelInput(channelInput);

        return monitoredChannelRepository.findByYoutubeChannelId(resolvedChannelId)
                .orElseGet(() -> {
                    String name = channelName == null || channelName.isBlank()
                            ? resolvedChannelId : channelName;
                    MonitoredChannel saved = monitoredChannelRepository.save(
                            new MonitoredChannel(platform, resolvedChannelId, name, false, null));
                    log.info("購読により監視対象へ追加しました: platform={}, name={}, channel={}",
                            platform, name, resolvedChannelId);
                    return saved;
                });
    }

    /**
     * 監視対象を削除する。紐づく通知履歴は DB の連鎖削除で、チャンネル別ログファイルは
     * このメソッドが明示的に削除する。DB は連鎖削除で消えるのにログファイルだけ
     * ディスクに残り続けるのは片手落ちなため（実際に指摘を受けた）。
     *
     * @param channelRecordId 監視対象の主キー（YouTube のチャンネル ID ではない）
     * @throws ChannelNotFoundException 指定 ID の監視対象が存在しない場合
     */
    public void remove(Long channelRecordId) {
        MonitoredChannel channel = monitoredChannelRepository.findById(channelRecordId)
                .orElseThrow(() -> new ChannelNotFoundException(channelRecordId));

        monitoredChannelRepository.deleteById(channelRecordId);
        channelLogReader.deleteChannelLogs(channel.getYoutubeChannelId());

        log.info("監視対象から削除しました: id={}, channel={}", channelRecordId, channel.getYoutubeChannelId());
        recordChannelAction(AuditAction.CHANNEL_DELETE, channelRecordId, "channel=" + channel.getYoutubeChannelId());
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
     * 操作者を解決してチャンネル関連の監査ログへ記録する。
     *
     * <p>このクラスは CLI からも呼ばれ、CLI にはログインの概念が無いため
     * {@link RequestContext#currentUsername()} は {@code null} を返す。
     * その場合は利用者情報を空欄のまま記録する（{@link InvitationService} の
     * 招待受け入れ記録と同じ考え方）。
     *
     * @param action          操作の種別
     * @param channelRecordId 対象チャンネルの主キー
     * @param detail          補足情報
     */
    private void recordChannelAction(AuditAction action, Long channelRecordId, String detail) {
        String username = RequestContext.currentUsername();
        Long userId = username == null ? null
                : appUserRepository.findByUsername(username).map(AppUser::getId).orElse(null);
        auditLogger.record(action, AuditOutcome.SUCCESS, userId, username, null,
                "CHANNEL", String.valueOf(channelRecordId), detail);
    }

}
