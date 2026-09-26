package com.example.monitor.service;

import com.example.monitor.dto.SoundCandidateListResponse;
import com.example.monitor.dto.SoundCandidateResponse;
import com.example.monitor.dto.SoundCandidateVerdictRequest;
import com.example.monitor.dto.SoundDetectionRunResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.entity.SoundCandidate;
import com.example.monitor.entity.SoundDetectionRun;
import com.example.monitor.entity.SoundMark;
import com.example.monitor.exception.RecordingNotFoundException;
import com.example.monitor.exception.SoundDetectionConflictException;
import com.example.monitor.exception.SoundDetectionNotFoundException;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.repository.SoundCandidateRepository;
import com.example.monitor.repository.SoundDetectionRunRepository;
import com.example.monitor.sound.EarKissDetector;
import com.example.monitor.sound.EarKissModel;
import com.example.monitor.sound.PcmDecoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 録画に耳キスの検出器（{@link EarKissDetector}）を掛け、結果を候補（{@link SoundCandidate}）と
 * 実行記録（{@link SoundDetectionRun}）として保存する（Issue #469）。
 *
 * <h2>1 本ずつ、録画中は処理しない理由</h2>
 * 検出は 2 時間の録画で CPU を 40 秒ほど使う（Issue #468 の実測）。{@code nice} が効くのは ffmpeg のデコードだけで、
 * FFT などの計算は JVM の中で動くので、録画（yt-dlp・ffmpeg）と CPU を取り合う。そこで録画中
 * （{@code RECORDING} の行がある間）は始めず、1 本終えるごとに確かめ直す。ほかに抑える仕組みは入れない。
 *
 * <h2>長すぎる録画は検出しない理由</h2>
 * 検出器の持つメモリは録画の長さに比例する（実測で 2 時間 50MB、4 時間 117MB）。本番は
 * {@code -XX:+ExitOnOutOfMemoryError} で、足りなければ JVM ごと落ちる。{@link #MAX_DURATION_SECONDS} を超える
 * 録画は、失敗（回数は上限）として記録して見回りの対象から外す。
 *
 * <h2>付け直しでも答えのある候補を消さない理由</h2>
 * 答えは学び直しの正解（正例・負例）で、付け直すたびに消えると聞き直させることになる。同じ版で付け直すときは、
 * 答えの無い候補だけを消して作り直し、答えのある候補の近く（{@link #NEAR_ANSWER_MS} 以内）には新しい候補を作らない
 * （同じ音に候補が 2 つ並ぶと、答えたはずの所をもう一度聞かされる）。
 *
 * <h2>検出をトランザクションの外で行う理由</h2>
 * 検出は数十秒かかる。その間 DB の接続を握ると、接続の少ないプール（5 本）を画面の API と取り合う。
 * 保存（候補の入れ替えと実行記録の完了）だけを 1 つのトランザクションにして、途中の状態を見せない。
 *
 * <p>配信の監視の巡回には入れない（{@code docs/pitfalls.md}「クォータを消費する API を監視ループに入れない」と同じ考え方で、
 * 重い処理で配信の検知を遅らせない）。{@link com.example.monitor.scheduler.SoundDetectionScheduler} が別の周期で呼ぶ。
 *
 * <h2>候補の API（Issue #470）もここに置く理由</h2>
 * 管理者の今すぐ検出は、見回りと同じ排他（{@code running}）を通す必要があり、その排他をこのクラスが持っている。
 * 候補の一覧と答えも、「今の版」の決め方を見回りとそろえるため、同じクラスに置く。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SoundDetectionService {

    /** 検出する種類。今の検出器は耳キスだけ。 */
    private static final SoundMark.Kind KIND = SoundMark.Kind.EAR_KISS;

    /** これより長い録画は検出しない（6 時間）。理由はクラスの JavaDoc を参照。 */
    private static final int MAX_DURATION_SECONDS = 6 * 3600;

    /** 答えのある候補から前後これ以内（ミリ秒）には、新しい候補を作らない。 */
    private static final long NEAR_ANSWER_MS = 1000;

    private final RecordingRepository recordingRepository;
    private final RecordingFileService recordingFileService;
    private final SoundCandidateRepository soundCandidateRepository;
    private final SoundDetectionRunRepository soundDetectionRunRepository;
    private final TransactionTemplate transactionTemplate;
    private final CurrentAppUser currentAppUser;

    /**
     * 検出が走っているか。
     *
     * <p>見回りは仮想スレッドへ逃がすので、{@code fixedDelay} だけでは前の回との重なりを防げない
     * （{@link RecordingReconciler} と同じ）。検出の入口を増やすときもこれを通し、2 本を同時に走らせない
     * （{@code docs/pitfalls.md}「巡回を起動する経路を増やすなら排他を通す」。CPU とメモリが倍になるため）。
     */
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * 見回りを仮想スレッドで 1 回始める。検出がまだ走っていれば見送る。
     *
     * <p>{@code @Scheduled} のスレッドは配信の巡回などと共有しているので、数十分かかりうる見回りでは塞がない。
     */
    public void startPending() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                processPending();
            } catch (RuntimeException e) {
                log.error("耳キスの検出の見回りに失敗しました。次回再試行します", e);
            } finally {
                running.set(false);
            }
        });
    }

    /**
     * まだ検出していない録画を、新しい順に 1 本ずつ検出する。
     *
     * <p>対象は {@link SoundDetectionRunRepository#findPendingRecordings} のうち、ファイルがあるもの。
     * 既存の録画も、この見回りで順に処理される。録画が始まったら、その回はそこでやめる（残りは次の回）。
     */
    public void processPending() {
        String version = EarKissModel.load().version();
        List<Recording> pending = soundDetectionRunRepository.findPendingRecordings(
                KIND, version, SoundDetectionRun.MAX_ATTEMPTS);
        for (int i = 0; i < pending.size(); i++) {
            if (recordingRepository.countByStatus(RecordingStatus.RECORDING) > 0) {
                log.info("録画中のため、耳キスの検出を次の見回りに回します: 残り {} 本", pending.size() - i);
                return;
            }
            Recording recording = pending.get(i);
            if (recordingFileService.resolveExistingFile(recording).isPresent()) {
                detect(recording);
            }
        }
    }

    /**
     * 録画 1 本に検出器を掛け、候補と実行記録を保存する。検出・保存に失敗しても例外は投げず、実行記録とログに残す。
     *
     * <p>長さの分からない録画は、見回りでは対象にしていない（{@link #processPending()}）。
     *
     * @param recording 対象の録画
     * @throws RuntimeException 同梱の重みのファイルを読めない場合（録画ごとの失敗ではないので、実行記録には残さない）
     */
    public void detect(Recording recording) {
        EarKissModel model = EarKissModel.load();
        String version = model.version();
        Integer duration = recording.getDurationSeconds();
        if (duration != null && duration > MAX_DURATION_SECONDS) {
            updateRun(recording, version, run -> run.giveUp("6 時間を超えるため検出しない"));
            log.info("6 時間を超えるため、耳キスの検出をしません: recording={}, duration={}秒", recording.getId(), duration);
            return;
        }

        long started = System.nanoTime();
        try {
            // 検出の途中で JVM が落ちても回数が増えるよう、始める前に書く（SoundDetectionRun の JavaDoc）
            updateRun(recording, version, SoundDetectionRun::start);
            List<EarKissDetector.FinalCandidate> finals;
            try (InputStream pcm = PcmDecoder.open(recordingFileService.resolveFilePath(recording), null, null)) {
                finals = new EarKissDetector(model).detect(pcm).finals();
            }
            transactionTemplate.executeWithoutResult(status -> saveCandidates(recording, version, finals));
            log.info("耳キスの候補を付けました: recording={}, duration={}秒, 候補={}件, 処理={}秒", recording.getId(),
                    duration, finals.size(), String.format("%.1f", (System.nanoTime() - started) / 1e9));
        } catch (IOException | RuntimeException e) {
            log.warn("耳キスの検出に失敗しました: recording={}", recording.getId(), e);
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            try {
                updateRun(recording, version, run -> run.fail(reason));
            } catch (RuntimeException saveFailure) {
                log.warn("耳キスの検出の失敗を記録できませんでした: recording={}", recording.getId(), saveFailure);
            }
        }
    }

    /**
     * 録画に付いた、今の版の候補を位置の順に返す。今の版の検出が済んだか（{@code state}）も一緒に返す。
     *
     * <p>古い版の候補は返さない。学び直しで版を上げると新しい版で付け直すので、混ぜると同じ音に候補が 2 つ並ぶため。
     *
     * @param recordingId 録画の主キー
     * @param kind        種類（{@code EAR_KISS}）
     * @return 状態・今の版・候補
     * @throws IllegalArgumentException   種類が無い・検出できない種類のとき（400）
     * @throws RecordingNotFoundException 録画が無いとき（404）
     */
    @Transactional(readOnly = true)
    public SoundCandidateListResponse listCandidates(Long recordingId, String kind) {
        requireKind(kind);
        Recording recording = requireRecording(recordingId);
        AppUser user = currentAppUser.require();
        String version = EarKissModel.load().version();
        SoundCandidateListResponse.State state = soundDetectionRunRepository
                .findByRecordingAndKindAndDetectorVersion(recording, KIND, version)
                .map(SoundDetectionService::stateOf)
                .orElse(SoundCandidateListResponse.State.PENDING);
        List<SoundCandidateResponse> candidates = soundCandidateRepository
                .findByRecordingAndKindAndDetectorVersion(recording, KIND, version).stream()
                .sorted(Comparator.comparingLong(SoundCandidate::getPositionMs).thenComparing(SoundCandidate::getId))
                .map(candidate -> SoundCandidateResponse.from(candidate, user))
                .toList();
        return new SoundCandidateListResponse(state, version, candidates);
    }

    /**
     * 候補に答える（{@code null} なら取り消す）。答えは全員で共有し、最後の答えを有効にする（{@link SoundCandidate}）ので、
     * ほかの人の答えも上書き・取り消しできる。
     *
     * <p>答えた人は常にログイン中の本人で、引数では受け取らない（{@link SoundMarkService} と同じ理由）。
     *
     * @param recordingId 録画の主キー
     * @param candidateId 候補の主キー
     * @param request     答え
     * @return 答えた後の候補
     * @throws IllegalArgumentException        答えの値が不正なとき（400）
     * @throws RecordingNotFoundException      録画が無いとき（404）
     * @throws SoundDetectionNotFoundException 候補が無い・別の録画の候補・今の版でない候補のとき（404）
     */
    @Transactional
    public SoundCandidateResponse answer(Long recordingId, Long candidateId, SoundCandidateVerdictRequest request) {
        SoundCandidate.Verdict verdict = parseVerdict(request.verdict());
        Recording recording = requireRecording(recordingId);
        AppUser user = currentAppUser.require();
        String version = EarKissModel.load().version();
        SoundCandidate candidate = soundCandidateRepository.findById(candidateId)
                .filter(found -> found.getRecording().getId().equals(recording.getId())
                        && found.getDetectorVersion().equals(version))
                .orElseThrow(() -> new SoundDetectionNotFoundException("候補が見つかりません: id=" + candidateId));
        candidate.review(verdict, user);
        return SoundCandidateResponse.from(candidate, user);
    }

    /**
     * 録画 1 本の検出を、見回りを待たずに仮想スレッドで始める（管理者のやり直し）。付け直しでも、答えのある候補は
     * 消さない（{@link #detect}）。
     *
     * <p><b>見回りと同じ排他（{@code running}）を通し、検出が走っていれば始めない。</b>2 本を同時に走らせると
     * CPU とメモリが倍になり、同じ録画なら候補の入れ替えが重なって、候補が二重にできうるため。
     *
     * <p>見回りの対象と同じく、再生できる録画（完了・途中まで）で、長さが分かっているものだけを受け付ける。
     * 長さが分からないと 6 時間の確かめ（{@link #detect}）を素通りし、メモリが録画の長さに比例する検出器で
     * JVM ごと落ちうるため。ほかの録画が録画中でも、見回りのように待たずに始める（管理者が録画を指定して頼んだため）。
     *
     * @param recordingId 録画の主キー
     * @param kind        種類（{@code EAR_KISS}）
     * @param force       今の版で検出済みでもやり直すか
     * @throws IllegalArgumentException        種類が無い・検出できない種類のとき（400）
     * @throws RecordingNotFoundException      録画が無いとき（404）
     * @throws SoundDetectionConflictException 再生できない・長さが分からない録画のとき、検出が走っているとき、
     *                                         今の版で検出済みで {@code force} が無いとき（409）
     */
    public void startDetection(Long recordingId, String kind, boolean force) {
        requireKind(kind);
        Recording recording = requireRecording(recordingId);
        RecordingStatus status = recording.getStatus();
        if ((status != RecordingStatus.COMPLETED && status != RecordingStatus.PARTIAL)
                || recording.getDurationSeconds() == null) {
            throw new SoundDetectionConflictException(
                    "再生できる録画で、長さが分かっているものだけ検出できます: id=" + recordingId);
        }
        // ponytail: 確かめてから排他を取るまでの一瞬に見回りがこの録画を終えると、force なしでも付け直す。
        // 答えのある候補は消えないので害は無い。気になるなら、排他を取ってから確かめる
        boolean done = soundDetectionRunRepository
                .findByRecordingAndKindAndDetectorVersion(recording, KIND, EarKissModel.load().version())
                .filter(run -> run.getStatus() == SoundDetectionRun.Status.DONE)
                .isPresent();
        if (done && !force) {
            throw new SoundDetectionConflictException(
                    "今の版で検出済みです。やり直すときは force=true を付けてください: id=" + recordingId);
        }
        if (!running.compareAndSet(false, true)) {
            throw new SoundDetectionConflictException("耳キスの検出が走っています。終わってから始めてください");
        }
        Thread.startVirtualThread(() -> {
            try {
                detect(recording);
            } catch (RuntimeException e) {
                log.error("耳キスの今すぐ検出に失敗しました: recording={}", recordingId, e);
            } finally {
                running.set(false);
            }
        });
    }

    /**
     * 録画の、今の版の実行記録を返す（管理者が今すぐ検出の結果を確かめるため）。
     *
     * @param recordingId 録画の主キー
     * @param kind        種類（{@code EAR_KISS}）
     * @return 実行記録
     * @throws IllegalArgumentException        種類が無い・検出できない種類のとき（400）
     * @throws RecordingNotFoundException      録画が無いとき（404）
     * @throws SoundDetectionNotFoundException 今の版で一度も検出を始めていないとき（404）
     */
    public SoundDetectionRunResponse getRun(Long recordingId, String kind) {
        requireKind(kind);
        Recording recording = requireRecording(recordingId);
        return soundDetectionRunRepository
                .findByRecordingAndKindAndDetectorVersion(recording, KIND, EarKissModel.load().version())
                .map(SoundDetectionRunResponse::from)
                .orElseThrow(() -> new SoundDetectionNotFoundException(
                        "今の版の検出の記録がありません: recording=" + recordingId));
    }

    /**
     * 実行記録から、画面に見せる状態を決める。上限の回数に達した失敗だけを {@code FAILED} にするのは、
     * それより前の失敗（途中で止まったものを含む）は見回りがまた試し、待てば候補が付きうるため。
     */
    private static SoundCandidateListResponse.State stateOf(SoundDetectionRun run) {
        if (run.getStatus() == SoundDetectionRun.Status.DONE) {
            return SoundCandidateListResponse.State.DONE;
        }
        return run.getAttempts() >= SoundDetectionRun.MAX_ATTEMPTS
                ? SoundCandidateListResponse.State.FAILED : SoundCandidateListResponse.State.PENDING;
    }

    /**
     * 種類を確かめる。今の検出器は耳キスだけなので、{@link SoundMark.Kind} にほかの種類が増えても 400 にする。
     */
    private static void requireKind(String kind) {
        if (!KIND.name().equals(kind)) {
            throw new IllegalArgumentException("候補の種類が正しくありません: " + kind);
        }
    }

    /**
     * 答えの文字列を直す。{@code null} は取り消し。
     *
     * <p>{@code Verdict.valueOf} を使わないのは、知らない名前のときの文言にクラス名が入るため
     * （{@link SoundMarkService} の種類と同じ）。
     */
    private static SoundCandidate.Verdict parseVerdict(String verdict) {
        if (verdict == null) {
            return null;
        }
        return Arrays.stream(SoundCandidate.Verdict.values())
                .filter(candidate -> candidate.name().equals(verdict))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "答え（verdict）は CONFIRMED・REJECTED・null のどれかにしてください: " + verdict));
    }

    private Recording requireRecording(Long recordingId) {
        return recordingRepository.findById(recordingId)
                .orElseThrow(() -> new RecordingNotFoundException(recordingId));
    }

    /**
     * 答えの無い候補を消し、新しい候補を入れて、実行記録を完了にする。1 つのトランザクションの中で呼ぶ。
     *
     * @param recording 録画
     * @param version   検出器の版
     * @param finals    検出器が出した候補
     */
    private void saveCandidates(Recording recording, String version, List<EarKissDetector.FinalCandidate> finals) {
        List<Long> answered = new ArrayList<>();
        for (SoundCandidate candidate
                : soundCandidateRepository.findByRecordingAndKindAndDetectorVersion(recording, KIND, version)) {
            if (candidate.getVerdict() == null) {
                soundCandidateRepository.delete(candidate);
            } else {
                answered.add(candidate.getPositionMs());
            }
        }
        for (EarKissDetector.FinalCandidate found : finals) {
            if (answered.stream().noneMatch(position -> Math.abs(position - found.positionMs()) <= NEAR_ANSWER_MS)) {
                soundCandidateRepository.save(
                        new SoundCandidate(recording, KIND, found.positionMs(), found.score(), version));
            }
        }
        updateRun(recording, version, run -> run.finish(finals.size()));
    }

    /**
     * 実行記録を（無ければ作って）書き換える。外側にトランザクションがあればそれに加わる。
     *
     * @param recording 録画
     * @param version   検出器の版
     * @param change    書き換え
     */
    private void updateRun(Recording recording, String version, Consumer<SoundDetectionRun> change) {
        transactionTemplate.executeWithoutResult(status -> {
            SoundDetectionRun run = soundDetectionRunRepository
                    .findByRecordingAndKindAndDetectorVersion(recording, KIND, version)
                    .orElseGet(() -> new SoundDetectionRun(recording, KIND, version));
            change.accept(run);
            soundDetectionRunRepository.save(run);
        });
    }
}
