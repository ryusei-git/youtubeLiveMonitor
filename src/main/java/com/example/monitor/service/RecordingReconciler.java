package com.example.monitor.service;

import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.service.RecordingSalvager.SalvageOutcome;
import com.example.monitor.service.RecordingSalvager.SalvageStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DB 上「録画中」のまま更新されなくなった録画履歴を、実ファイルの有無で完了・失敗に補正する。
 *
 * <p>録画完了の記録は、録画開始時に起動する仮想スレッド（{@link StreamRecorder#awaitCompletion}）が
 * 担っている。アプリを再起動するとこの仮想スレッドは失われるため、その後 {@code yt-dlp} 自身は
 * 無事に完了しても DB の状態を更新する者がいなくなる（実際に発生した：録画完了後にアプリを
 * 再起動したところ、完成したファイルが存在するのに一覧から再生できないままになった）。
 *
 * <h2>{@code StreamRecorder} とは別クラスにしている理由</h2>
 * 対象は自動録画（{@link StreamRecorder}）と手動ダウンロード（{@link VideoDownloadService}）の
 * 両方であり、どちらか一方のクラスに寄せると他方の面倒まで見る不自然な依存が生まれる。
 * 進行中かどうかの判定は両者が共有する {@link ActiveVideoJobs} への問い合わせ1つで済み、
 * {@code ActiveVideoJobs} 自身は {@link RecordingHistoryService} に依存しないため
 * 循環参照の心配もない。結果としてこのクラスは {@link RecordingHistoryService}・
 * {@link ActiveVideoJobs}・{@link ProcessLauncher} にだけ依存する形で完結している。
 *
 * <h2>いつ呼んでも安全な理由（2 段階の除外）</h2>
 * 単に「{@code RECORDING} 行が見つかったら完成ファイルの有無で判定する」だけでは、
 * <b>今まさに録画・ダウンロードが進行中の配信</b>まで「まだ完成ファイルが無い」という理由で
 * 誤って失敗と判定してしまう。進行中かどうかは次の 2 つで確認する。
 * <ol>
 *   <li>{@link ActiveVideoJobs#isActive(String)} … このアプリが追跡中か。
 *       自動録画（{@link StreamRecorder}）と手動ダウンロード（{@link VideoDownloadService}）は
 *       同じ {@link ActiveVideoJobs} に予約するため、この1か所への問い合わせで両方を
 *       まとめて確認できる（{@code isRecording} と {@code isDownloading} をそれぞれ確認するのと
 *       等価）。どちらも完了・失敗の記録を先に済ませてから予約を外す順序なので、
 *       予約中なら必ず進行中。<b>録画とダウンロードの両方を見る必要がある。</b>
 *       どちらも同じ {@code recordings} テーブルに {@code RECORDING} 行を作り、同じ
 *       {@code yt-dlp} で出力先に書き込むため、この確認を欠くと<b>まだ書き込み中のファイルに
 *       {@link RecordingSalvager} を掛けて壊す</b>（詰め替えは出力ファイルを置き換えるため）。</li>
 *   <li>{@link ProcessLauncher#isRunningWithCommandLineContaining(String)} … OS 上に
 *       まだ {@code yt-dlp} プロセスが生きているか。<b>こちらが欠かせない。</b>
 *       録画プロセスはアプリを再起動しても生き残る（{@code bin/service.sh stop} は JVM しか
 *       止めない）ため、1 だけだと「再起動直後、実際にはまだ録画中なのに追跡していない」
 *       ものを失敗と誤判定し、その後 {@code yt-dlp} が完成させても永久に失敗表示のままになる。</li>
 * </ol>
 * この 2 段階により、アプリ起動直後でも定期巡回でも「今すぐチェック」からでも安全に呼べる。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecordingReconciler {

    private final RecordingRepository recordingRepository;
    private final RecordingFileService recordingFileService;
    private final RecordingHistoryService recordingHistoryService;
    private final ActiveVideoJobs activeVideoJobs;
    private final ProcessLauncher processLauncher;
    private final VideoMetadataExtractor videoMetadataExtractor;
    private final RecordingSalvager recordingSalvager;

    /**
     * 救済できなかった録画の主キーと、そのときのファイルの合計サイズ。
     *
     * <p><b>同じ録画を毎巡回 {@code ffmpeg} に掛け続けないための記憶。</b>
     * 断片そのものが壊れていて何度やっても失敗する録画は珍しくない。それを放っておくと、
     * 数GBのファイルに対する外部プロセスの起動が巡回のたび（既定 300 秒ごと）に
     * <b>永久に繰り返され</b>、CPU とディスク I/O を無駄に食い続ける
     * （通知の再試行に上限を設けているのと同じ理由）。
     *
     * <p>ただし「二度と試さない」にはしない。このクラスは元々<b>あとから状況が変わった録画を
     * 救済する</b>ためのものなので、{@link RecordingFileService#totalFileSizeFor(Recording)}
     * が前回の失敗時と変わっていれば（＝断片が増えた、完成ファイルが現れた）改めて試す。
     *
     * <p>DB には持たせない。{@code ddl-auto: update} の下で列を足すのは既存データのある環境で
     * 壊れやすいうえ、アプリを再起動したときに一度だけ試し直されるのは実害が無いため
     * （{@code ffmpeg} を入れ直した場合などはむしろ望ましい）。
     */
    private final Map<Long, Long> unsalvageableFileSizes = new ConcurrentHashMap<>();

    /**
     * DB 上の録画状態を、実ファイルの有無という事実に合わせて補正する。
     *
     * <p>対象は 2 種類。どちらも {@link RecordingSalvager} を通すため、
     * <b>配信の途中で切れた録画は「そこまでを再生できる形」に直したうえで
     * {@code PARTIAL} として記録される</b>（以前はこれらが再生できないまま失敗扱いだった）。
     * <ul>
     *   <li>{@code RECORDING} のまま止まっているもの … 再生できるものが作れれば完了・途中まで、
     *       何も作れなければ失敗へ</li>
     *   <li>{@code FAILED} なのにファイルが残っているもの … 再生できる形に直せれば救済する。
     *       ファイルが実在するときだけ状態が変わるので安全。何も残っていない録画では
     *       外部コマンドを起動せずに読み飛ばす。<b>一度救済に失敗した録画は、ファイルの
     *       合計サイズが変わるまで再試行しない</b>——必ず失敗する録画を毎巡回 {@code ffmpeg} に
     *       掛け続けると、数GBのファイルに対する外部プロセスの起動コストを永久に払い続ける
     *       ことになるため（{@link #unsalvageableFileSizes} 参照）</li>
     * </ul>
     *
     * <p>{@link com.example.monitor.scheduler.LiveStreamPollingScheduler} から、
     * 定期巡回のたびと「今すぐチェック」の両方で呼ばれる。
     */
    public void reconcileOrphanedRecordings() {
        for (Recording recording : recordingRepository.findByStatus(RecordingStatus.RECORDING)) {
            if (isStillInProgress(recording.getVideoId())) {
                continue;
            }
            reconcileOne(recording, "録画中のまま更新されていなかった録画");
        }

        for (Recording recording : recordingRepository.findByStatus(RecordingStatus.FAILED)) {
            // ファイルが1つも無ければ詰め替えを試みるまでもない（毎巡回 ffprobe を走らせない）
            if (recordingFileService.sizeIfExists(recording).isEmpty()
                    && !recordingFileService.hasAnyFileFor(recording)) {
                continue;
            }
            if (isKnownUnsalvageable(recording)) {
                continue;
            }
            reconcileOne(recording, "失敗と記録されていた録画");
        }

        generateMissingThumbnails();
    }

    /**
     * 録画 1 件の状態を、実ファイルの状態に合わせて補正する。
     *
     * <p>このメソッドに入る時点で<b>録画プロセスが終わっていることが確認済み</b>でなければ
     * ならない。{@link RecordingSalvager} は出力ファイルを書き換えるため、
     * 進行中の録画に対して呼ぶと録画そのものを壊す。
     *
     * @param recording 対象の録画履歴
     * @param context   ログに出す「どういう状態だったか」の説明
     */
    private void reconcileOne(Recording recording, String context) {
        Path outputFile = recordingFileService.resolveFilePath(recording);
        SalvageOutcome salvage = recordingSalvager.ensurePlayable(outputFile);

        if (!salvage.isPlayable()) {
            // 次の巡回で同じ ffmpeg を走らせ直さないよう、失敗した時点のファイルの状態を覚えておく
            unsalvageableFileSizes.put(recording.getId(), recordingFileService.totalFileSizeFor(recording));
            if (recording.getStatus() == RecordingStatus.RECORDING) {
                recordingHistoryService.markFailed(recording.getId());
                log.warn("{}を失敗として補正しました: id={}, video={}",
                        context, recording.getId(), recording.getVideoId());
            }
            return;
        }

        // 救済できたので見送りの記憶は不要（同じ録画が再び失敗と記録されたら改めて試す）
        unsalvageableFileSizes.remove(recording.getId());

        if (salvage.status() == SalvageStatus.SALVAGED) {
            recordingHistoryService.markPartial(recording.getId(), salvage.fileSizeBytes());
            log.info("{}について、途中までの内容を再生できる形に直しました: id={}, video={}, size={}",
                    context, recording.getId(), recording.getVideoId(), salvage.fileSizeBytes());
            return;
        }

        recordingHistoryService.markCompleted(recording.getId(), salvage.fileSizeBytes());
        log.info("{}を完了として補正しました: id={}, video={}, size={}",
                context, recording.getId(), recording.getVideoId(), salvage.fileSizeBytes());
    }

    /**
     * 前回救済に失敗したときから状況が変わっていないかを、外部プロセスを起動せずに判断する。
     *
     * <p>同じ内容のファイルに対して {@code ffmpeg} を何度掛けても結果は変わらない。
     * 判断材料にファイルの合計サイズを使う理由は {@link #unsalvageableFileSizes} を参照。
     *
     * @param recording 対象の録画履歴
     * @return 前回の失敗時とファイルの状態が同じなら {@code true}（救済を見送るべき）
     */
    private boolean isKnownUnsalvageable(Recording recording) {
        Long sizeWhenFailed = unsalvageableFileSizes.get(recording.getId());
        if (sizeWhenFailed == null) {
            return false;
        }

        long currentSize = recordingFileService.totalFileSizeFor(recording);
        if (sizeWhenFailed == currentSize) {
            log.debug("前回と同じ状態で救済に失敗している録画のため見送ります: id={}, video={}",
                    recording.getId(), recording.getVideoId());
            return true;
        }

        log.info("ファイルの状態が変わっていたため救済をもう一度試します: id={}, video={}, size={}→{}",
                recording.getId(), recording.getVideoId(), sizeWhenFailed, currentSize);
        return false;
    }

    /**
     * 完了済みなのにサムネイルが無い録画について、再生時間とサムネイルを後から作る。
     *
     * <p>サムネイルの仕組みを入れる前に録画したものや、生成に失敗したものを救うための処理。
     * 取得できなくても録画自体は問題なく再生できるので、失敗しても状態は変えずに次回へ持ち越す
     * （次の巡回でまた対象として拾われる）。
     */
    private void generateMissingThumbnails() {
        List<RecordingStatus> playableStatuses = List.of(RecordingStatus.COMPLETED, RecordingStatus.PARTIAL);
        for (Recording recording : recordingRepository.findByStatusInAndThumbnailPathIsNull(playableStatuses)) {
            recordingFileService.resolveExistingFile(recording).ifPresent(videoFile -> {
                Integer durationSeconds = videoMetadataExtractor.extractDurationSeconds(videoFile).orElse(null);
                if (durationSeconds == null) {
                    return;
                }

                String thumbnailPath = videoMetadataExtractor.extractThumbnail(videoFile, durationSeconds)
                        .map(recordingFileService::toRelativePath)
                        .orElse(null);

                recordingHistoryService.updateMediaMetadata(recording.getId(), durationSeconds, thumbnailPath);
                log.info("録画の再生時間とサムネイルを生成しました: id={}, video={}, duration={}秒",
                        recording.getId(), recording.getVideoId(), durationSeconds);
            });
        }
    }

    /**
     * この動画の録画がまだ進行中かどうかを判定する。判定の 2 段階についてはクラスの JavaDoc を参照。
     *
     * @param videoId 対象の動画 ID
     * @return まだ進行中なら {@code true}（補正の対象外にすべき）
     */
    private boolean isStillInProgress(String videoId) {
        if (activeVideoJobs.isActive(videoId)) {
            log.debug("このアプリが追跡中の録画・ダウンロードのため補正の対象外とします: video={}", videoId);
            return true;
        }
        if (processLauncher.isRunningWithCommandLineContaining(videoId)) {
            // アプリ再起動をまたいで生き残っている録画プロセス
            log.info("追跡は失われていますが録画プロセスは稼働中のため、補正の対象外とします: video={}", videoId);
            return true;
        }
        return false;
    }
}
