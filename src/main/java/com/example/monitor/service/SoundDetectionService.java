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
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
 * <h2>ほかの版の答えを今の版へ写す理由（Issue #481）</h2>
 * 一覧（{@link #listCandidates}）は今の版の候補だけを返すので、写さないと、版を上げた途端に答えた候補が
 * 「未確認」の新しい候補に置き換わって見え、答え直させることになる。写すときの決まりと理由:
 * <ul>
 *   <li>同じ位置（{@link #NEAR_ANSWER_MS} 以内。新しい候補を作らない範囲と同じく、同じ音とみなす）の答えが複数の版に
 *       あれば、答えた時刻がいちばん新しいものだけを写す。利用者のいまの判断は最後の答えなので。
 *       今の版に答えのある候補（写したものを含む）が既にあれば写さない。同じ版で付け直しても二重に写さないため。</li>
 *   <li>写し元にするのは、まだどの行にも写されていない行（写しの鎖の末端）だけ
 *       （{@link SoundCandidateRepository#findAnsweredInOtherVersions}）。写した後の答えの正本は写した行で、
 *       写した行の答えを取り消したら、その位置は次の版へ何も写さない。元の行も写し元にすると、取り消す前の答えが
 *       次の版でよみがえるため。</li>
 *   <li>写した候補は、検出器の上限（1 時間あたりの数）の外に置き、数えないし消さない。上限は聞いて答える数を
 *       抑えるためのもので、答え済みの候補は聞く手間を増やさない。数に入れると、答えた候補が新しい候補を押し出すか、
 *       答えた候補が画面から消える。</li>
 *   <li>検出器の新しい候補は、写した候補の近くにも作らない（答えのある候補の近くに作らないのと同じ理由）。</li>
 * </ul>
 *
 * <h2>版を上げたら、検出を待たずに答えだけ先に写す理由（Issue #551）</h2>
 * 付け直し（{@code saveCandidates}）のときにしか写さないと、版を上げてからその録画を付け直すまでの間、一覧は今の版の候補を
 * 0 件で返し、答えた候補まで画面から消える。付け直しは 1 本ずつで、録画中は始めない（「1 本ずつ、録画中は処理しない理由」）ので、
 * 古い録画の番が来るのは何時間も後になりうる。そこで見回りのはじめ（{@link #processPending()}）に、今の版で検出の済んでいない
 * 録画すべてへ、答えのある候補だけを先に写す。DB の読み書きだけで検出器は動かさないので、録画中でも、ファイルが無くても行う。
 * <ul>
 *   <li>答えの無い候補は写さない。付け直せば今の版の検出器が出し直すもので、前の版の外れを先に聞かせる値打ちは低いため。</li>
 *   <li>前の版の候補をそのまま一覧に出す方法は取らない。答え（{@link #answer}）を前の版の行にも受け付けることになり、
 *       写し元の決まり（{@link SoundCandidateRepository#findAnsweredInOtherVersions}。写した後の元の行は、写した時点の
 *       答えのまま止まっている）が崩れるため。</li>
 *   <li>先に写した候補は、付け直しで「答えのある候補（写したもの）」として残るので、二重に写らない。検出器の新しい候補も、
 *       その近くには作らない。</li>
 * </ul>
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

    /**
     * 答えのある候補（写したものを含む）から前後これ以内（ミリ秒）は同じ音とみなし、新しい候補を作らず、
     * ほかの版の答えも写さない。
     */
    private static final long NEAR_ANSWER_MS = 1000;

    /**
     * 検出 1 本の時間の上限を決める割合（録画の長さ ÷ この値）。実測は 2 時間の録画で CPU 40 秒（長さの 0.6%）なので、
     * 録画やビルドと CPU を取り合って遅くなっても、ふつうは届かない（{@link #limitOf}）。
     */
    private static final int LIMIT_DIVISOR = 10;

    /** 検出 1 本の時間の上限に足す時間。短い録画でも、ffmpeg の起動や読み込みの待ちで誤って止めないため。 */
    private static final Duration LIMIT_EXTRA = Duration.ofMinutes(10);

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

    /** アプリの終了が始まったか（{@link #stop()}）。立った後は新しい検出を始めず、失敗も記録しない。 */
    private volatile boolean stopping;

    /** いま走っている検出。無ければ {@code null}。終了時の印（{@link #stop()}）と時間の上限（{@link #abortIfOverdue()}）に使う。 */
    private final AtomicReference<CurrentDetection> current = new AtomicReference<>();

    /**
     * アプリの終了で止めた検出の印（{@link #stop()}）を置くファイル。作業ディレクトリからの相対で、H2 と同じ {@code data/} の下
     * （{@link DeviceDownloadService} の一時ファイルと同じ考え方）。確認用の起動（{@code bin/sandbox.sh} は {@code .sandbox/} で動かす）
     * の印は、本番の印と混ざらない。
     */
    @Value("${monitor.sound-detection.stopped-marker:data/sound-detection-stopped}")
    private String stoppedMarker = "data/sound-detection-stopped";

    /**
     * 見回りを仮想スレッドで 1 回始める。検出がまだ走っていれば見送る。
     *
     * <p>見送るとき、走っている検出が時間の上限を超えていれば止める（{@link #abortIfOverdue()}）。アプリの終了が始まった後は始めない。
     *
     * <p>{@code @Scheduled} のスレッドは配信の巡回などと共有しているので、数十分かかりうる見回りでは塞がない。
     */
    public void startPending() {
        if (stopping) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            abortIfOverdue();
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
     *
     * <p>初めに、前回のアプリの終了で止めた検出を回数から外す（{@link #forgiveStoppedRun()}）。外した録画がこの回の対象に入るよう、対象を引く前に行う。
     *
     * <p>検出の前に、対象の録画すべてへほかの版の答えを写す（クラスの説明「版を上げたら、検出を待たずに答えだけ先に写す理由」）。
     * こちらは録画中でも、ファイルが無くても行う。ファイルも CPU もほとんど使わないため。1 本で失敗しても、ほかの録画は続ける。
     */
    public void processPending() {
        forgiveStoppedRun();
        String version = EarKissModel.load().version();
        List<Recording> pending = soundDetectionRunRepository.findPendingRecordings(
                KIND, version, SoundDetectionRun.MAX_ATTEMPTS);
        for (Recording recording : pending) {
            // 終了の途中は H2 が DB を閉じていることが多く（stop() の JavaDoc）、残りの録画の数だけ「写せませんでした」が並ぶので、
            // 検出と同じくやめる。写せなかった分は次の起動の見回りで写す
            if (stopping) {
                return;
            }
            carryAnswersAhead(recording, version);
        }
        for (int i = 0; i < pending.size(); i++) {
            if (stopping) {
                return;
            }
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
     * <p>アプリの終了で止めた回は回数に数えない（{@link #stop()}）。時間の上限（{@link #limitOf}）を超えて止めた回は、
     * 失敗として数える（固まる録画を見回りのたびに試し続けないため）。
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
        CurrentDetection detection = new CurrentDetection(recording.getId(), version, limitOf(duration));
        // 実行記録を「開始」にする前に置く。後に置くと、その間に終了したとき stop() が印を残さず、回数に数えられる
        current.set(detection);
        try {
            // 検出の途中で JVM が落ちても回数が増えるよう、始める前に書く（SoundDetectionRun の JavaDoc）
            updateRun(recording, version, SoundDetectionRun::start);
            List<EarKissDetector.FinalCandidate> finals;
            try (InputStream pcm = PcmDecoder.open(recordingFileService.resolveFilePath(recording), null, null)) {
                detection.pcm = pcm;
                finals = new EarKissDetector(model).detect(pcm).finals();
            }
            transactionTemplate.executeWithoutResult(status -> saveCandidates(recording, version, finals));
            log.info("耳キスの候補を付けました: recording={}, duration={}秒, 候補={}件, 処理={}秒", recording.getId(),
                    duration, finals.size(), String.format("%.1f", (System.nanoTime() - started) / 1e9));
        } catch (IOException | RuntimeException e) {
            if (stopping) {
                // 終了の途中は H2 が DB を閉じていることが多い（stop() の JavaDoc）。回数は stop() の印から次の見回りが戻す
                log.info("アプリの終了中のため、耳キスの検出の結果を記録しません: recording={}", recording.getId());
                return;
            }
            String reason = detection.abortReason != null ? detection.abortReason
                    : e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.warn("耳キスの検出に失敗しました: recording={}", recording.getId(), e);
            try {
                updateRun(recording, version, run -> run.fail(reason));
            } catch (RuntimeException saveFailure) {
                log.warn("耳キスの検出の失敗を記録できませんでした: recording={}", recording.getId(), saveFailure);
            }
        } finally {
            current.set(null);
        }
    }

    /**
     * 録画に付いた、今の版の候補を位置の順に返す。今の版の検出が済んだか（{@code state}）も一緒に返す。
     *
     * <p>古い版の候補は返さない。学び直しで版を上げると新しい版で付け直すので、混ぜると同じ音に候補が 2 つ並ぶため。
     * 古い版の答えは、見回りのはじめ（検出の前）と付け直しで今の版へ写すので、ここに出る（Issue #481・#551）。
     *
     * <p>{@link #MAX_DURATION_SECONDS} を超える録画は、実行記録によらず {@code UNSUPPORTED} にする。見回りは失敗として
     * 記録する（{@link #detect}）が、やり直しても変わらないので、画面で失敗と分けて出すため。
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
        Integer duration = recording.getDurationSeconds();
        SoundCandidateListResponse.State state = duration != null && duration > MAX_DURATION_SECONDS
                ? SoundCandidateListResponse.State.UNSUPPORTED
                : soundDetectionRunRepository
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
            abortIfOverdue();
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
     * アプリの終了時に、走っている検出があれば「アプリの終了で止めた」印をファイル（{@link #stoppedMarker}）に残す。
     * 次の起動の見回りが印を読み、その回を回数に数えない形（{@link SoundDetectionRun#cancel()}）に直す（{@link #forgiveStoppedRun()}）。
     *
     * <p>回数は検出の前に増やしている（{@link SoundDetectionRun} の JavaDoc）ので、何もしないと、ふつうの停止
     * （{@code bin/service.sh restart}。ビルドのたびに行う）でも 1 回と数えられ、再起動が続くと録画が見回りの対象から外れる
     * （{@link SoundDetectionRun#MAX_ATTEMPTS}）。JVM ごと落ちた場合（{@code -XX:+ExitOnOutOfMemoryError}・{@code kill -9}）は
     * ここを通らないので、今までどおり数える。
     *
     * <p>ここでは DB に書かない。H2 は JVM の終了で自分から DB を閉じる（H2 の終了フック。{@code AUTO_SERVER=TRUE} では
     * {@code DB_CLOSE_ON_EXIT=FALSE} にできない）。その終了フックは Spring の終了処理と別のスレッドで同時に走るので、
     * この Bean が DB の Bean より先に破棄されても、ここでは DB がもう閉じていることが多い。ファイルなら確実に残せる。
     *
     * <p>検出は止めず、終わるのも待たない。JVM が止まれば ffmpeg の出力の読み手がいなくなり、ffmpeg も止まる
     * （{@link PcmDecoder} の JavaDoc）。
     */
    @PreDestroy
    public void stop() {
        stopping = true;
        CurrentDetection detection = current.get();
        if (detection == null) {
            return;
        }
        Path marker = Path.of(stoppedMarker);
        try {
            Files.createDirectories(marker.toAbsolutePath().getParent());
            Files.writeString(marker, detection.recordingId + " " + detection.version);
            log.info("アプリの終了で耳キスの検出を止めます。次の見回りでこの回を回数から外します: recording={}",
                    detection.recordingId);
        } catch (IOException e) {
            log.warn("耳キスの検出を止めた印を書けませんでした（この回は回数に数えられます）: recording={}",
                    detection.recordingId, e);
        }
    }

    /**
     * 前回のアプリの終了で止めた検出の印（{@link #stop()}）を読み、その回を回数に数えない形（{@link SoundDetectionRun#cancel()}）に直す。
     *
     * <p>印の中身は「録画の主キー 半角空白 検出器の版」。版も持つのは、版を上げるビルドの後の再起動で、今の版ではなく
     * 止めたときの版の記録を直すため。録画が消えた・記録が無い・その回がもう終わっていた（{@code cancel()} は何もしない）ときも、
     * 読めなかったときも、印は消す。残すと、見回りのたびに同じ印を読み直すため。
     */
    private void forgiveStoppedRun() {
        Path marker = Path.of(stoppedMarker);
        if (!Files.exists(marker)) {
            return;
        }
        try {
            String[] parts = Files.readString(marker).strip().split(" ", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("印の形が正しくありません");
            }
            Long recordingId = Long.valueOf(parts[0]);
            String version = parts[1];
            recordingRepository.findById(recordingId).ifPresent(recording -> transactionTemplate.executeWithoutResult(
                    status -> soundDetectionRunRepository.findByRecordingAndKindAndDetectorVersion(recording, KIND, version)
                            .ifPresent(run -> {
                                run.cancel();
                                soundDetectionRunRepository.save(run);
                            })));
            log.info("前回のアプリの終了で止めた耳キスの検出の印を読みました: recording={}, version={}", recordingId, version);
        } catch (IOException | RuntimeException e) {
            log.warn("耳キスの検出を止めた印を読めませんでした。印を消して続けます: {}", marker, e);
        }
        try {
            Files.deleteIfExists(marker);
        } catch (IOException e) {
            log.warn("耳キスの検出を止めた印を消せませんでした: {}", marker, e);
        }
    }

    /**
     * 走っている検出が時間の上限を超えていれば、WARN を出して止める。見回りの見送りと、今すぐ検出の 409 のときに呼ぶ。
     *
     * <p>ffmpeg が固まる・録画の置き場の読み込みが止まると、検出は戻らず {@code running} が立ったままになり、
     * 見回りは毎回黙って見送り、今すぐ検出は常に 409 になる。専用のタイマーは持たず、見送るたびに確かめる
     * （見回りは 10 分ごとに来るので、上限を超えてから遅くとも 10 分ほどで止まる。止まらなければ毎回 WARN が出る）。
     * 止めた回は失敗として数える（{@link #detect} の catch が {@code abortReason} を理由にする）。
     *
     * <p>ffmpeg の出力を閉じるのは別の仮想スレッドで行う。閉じる処理は ffmpeg が終わるまで待つ（{@link PcmDecoder}）ので、
     * 読み込みが止まった ffmpeg だと戻らず、呼び出し元（配信の巡回と共有する {@code @Scheduled} のスレッド・HTTP のスレッド）を塞ぐため。
     * 閉じると ffmpeg が止まり、検出は {@link IOException} で終わって {@link #detect} の catch に入る。
     *
     * <p>閉じるのは検出 1 本につき 1 回だけにする（WARN は見送るたびに出す）。読み込みが止まったままの ffmpeg は
     * 強制終了しても終わらないことがあり、見送るたびに閉じ直すと、ffmpeg の終了を待ったまま戻らない仮想スレッドが
     * 1 本ずつ増え続けるため。閉じ直しても、1 回目で送った強制終了より効くことは無い。
     */
    private void abortIfOverdue() {
        CurrentDetection detection = current.get();
        if (detection == null) {
            return;
        }
        Duration elapsed = Duration.between(detection.startedAt, Instant.now());
        if (elapsed.compareTo(detection.limit) <= 0) {
            return;
        }
        log.warn("耳キスの検出が時間の上限を超えたので止めます: recording={}, 経過={}分, 上限={}分",
                detection.recordingId, elapsed.toMinutes(), detection.limit.toMinutes());
        detection.abortReason = "時間の上限（" + detection.limit.toMinutes() + " 分）を超えたので止めた";
        InputStream pcm = detection.pcm;
        if (pcm == null || !detection.closing.compareAndSet(false, true)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                pcm.close();
            } catch (IOException e) {
                log.warn("耳キスの検出を止められませんでした: recording={}", detection.recordingId, e);
            }
        });
    }

    /**
     * 検出 1 本の時間の上限。録画の長さ ÷ {@link #LIMIT_DIVISOR} ＋ {@link #LIMIT_EXTRA}（2 時間の録画で 22 分、6 時間で 46 分）。
     * 長さが分からなければ 6 時間として扱う（見回り・今すぐ検出は長さの分かる録画しか渡さないが、念のため）。
     *
     * @param durationSeconds 録画の長さ（秒）。分からなければ {@code null}
     * @return 上限
     */
    private static Duration limitOf(Integer durationSeconds) {
        long seconds = durationSeconds != null ? durationSeconds : MAX_DURATION_SECONDS;
        return Duration.ofSeconds(seconds).dividedBy(LIMIT_DIVISOR).plus(LIMIT_EXTRA);
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
     * 答えの無い候補を消し、ほかの版の答えを写し、新しい候補を入れて、実行記録を完了にする。1 つのトランザクションの中で呼ぶ。
     * 写す決まりはクラスの説明「ほかの版の答えを今の版へ写す理由」を参照。
     *
     * <p>写した候補は、答えを取り消されていても消さない（上限の外に置き、消さない決まり）。消すと、元の行が
     * どの行にも写されていない状態に戻って写し元に入り、次の付け直しで元の答えがまた写って、取り消しが黙って元に戻るため。
     * 版を上げたときも同じことが起きないよう、写し元は写しの鎖の末端だけにしている
     * （{@link SoundCandidateRepository#findAnsweredInOtherVersions}）。
     *
     * <p>実行記録の候補の数は、検出器が出した数のまま（写した候補は数えない）。
     *
     * @param recording 録画
     * @param version   検出器の版
     * @param finals    検出器が出した候補
     */
    private void saveCandidates(Recording recording, String version, List<EarKissDetector.FinalCandidate> finals) {
        List<Long> kept = new ArrayList<>();
        for (SoundCandidate candidate
                : soundCandidateRepository.findByRecordingAndKindAndDetectorVersion(recording, KIND, version)) {
            if (candidate.getVerdict() == null && candidate.getCarriedFromId() == null) {
                soundCandidateRepository.delete(candidate);
            } else {
                kept.add(candidate.getPositionMs());
            }
        }
        carryAnswers(recording, version, kept);
        for (EarKissDetector.FinalCandidate found : finals) {
            if (isFree(kept, found.positionMs())) {
                soundCandidateRepository.save(
                        new SoundCandidate(recording, KIND, found.positionMs(), found.score(), version));
            }
        }
        updateRun(recording, version, run -> run.finish(finals.size()));
    }

    /**
     * ほかの版の答えのある候補を、今の版へ写す。{@code kept} のどれかから {@link #NEAR_ANSWER_MS} 以内の位置は写さない
     * （同じ音に候補を 2 つ並べない）。写した位置は {@code kept} に足す（後から入れる検出器の候補を、その近くに作らないため）。
     * 付け直し（{@code saveCandidates}）と、検出の前の写し（{@code carryAnswersAhead}）が、1 つのトランザクションの中で呼ぶ。
     *
     * @param recording 録画
     * @param version   今の検出器の版
     * @param kept      今の版に残す候補の位置。写した位置をここに足す
     * @return 写した候補の数
     */
    private int carryAnswers(Recording recording, String version, List<Long> kept) {
        int carried = 0;
        for (SoundCandidate answered : soundCandidateRepository.findAnsweredInOtherVersions(recording, KIND, version)) {
            if (isFree(kept, answered.getPositionMs())) {
                soundCandidateRepository.save(SoundCandidate.carryOver(answered, version));
                kept.add(answered.getPositionMs());
                carried++;
            }
        }
        return carried;
    }

    /**
     * 検出を待たずに、ほかの版の答えのある候補だけを今の版へ写す（クラスの説明「版を上げたら、検出を待たずに答えだけ先に写す理由」）。
     * 失敗しても例外は投げず、ログに残す。付け直しのときにまた写すので、ほかの録画の写しと検出を止めない。
     *
     * <p>今の版の候補は、答えの無いものも含めてすべて「残す候補」として扱う。答えの無い候補を消して作り直すのは付け直しだけで、
     * ここでその近くに答えを写すと、同じ音に候補が 2 つ並ぶため。
     *
     * @param recording 録画
     * @param version   今の検出器の版
     */
    private void carryAnswersAhead(Recording recording, String version) {
        try {
            Integer carried = transactionTemplate.execute(status -> {
                List<Long> kept = new ArrayList<>();
                for (SoundCandidate candidate
                        : soundCandidateRepository.findByRecordingAndKindAndDetectorVersion(recording, KIND, version)) {
                    kept.add(candidate.getPositionMs());
                }
                return carryAnswers(recording, version, kept);
            });
            if (carried != null && carried > 0) {
                log.info("検出の前に、ほかの版の答えを今の版へ写しました: recording={}, version={}, 件数={}",
                        recording.getId(), version, carried);
            }
        } catch (RuntimeException e) {
            log.warn("検出の前に、ほかの版の答えを写せませんでした。付け直しのときにまた写します: recording={}",
                    recording.getId(), e);
        }
    }

    /** 残す候補のどれからも {@link #NEAR_ANSWER_MS} より離れているか（同じ音に候補を 2 つ並べないため）。 */
    private static boolean isFree(List<Long> kept, long positionMs) {
        return kept.stream().noneMatch(position -> Math.abs(position - positionMs) <= NEAR_ANSWER_MS);
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

    /** 走っている検出 1 本。終了時の印（{@link #stop()}）と時間の上限（{@link #abortIfOverdue()}）に使う。 */
    private static final class CurrentDetection {

        /** 録画の主キー。 */
        private final Long recordingId;

        /** 検出器の版。 */
        private final String version;

        /** 始めた時刻。 */
        private final Instant startedAt = Instant.now();

        /** 時間の上限。 */
        private final Duration limit;

        /** ffmpeg の出力。開くまでは {@code null}。閉じると ffmpeg が止まる。 */
        private volatile InputStream pcm;

        /** 時間の上限で止めた理由。止めていなければ {@code null}。止めた回の失敗の理由にする。 */
        private volatile String abortReason;

        /** 時間の上限で ffmpeg の出力を閉じ始めたか。閉じるのを 1 回にするため（{@link #abortIfOverdue()}）。 */
        private final AtomicBoolean closing = new AtomicBoolean();

        CurrentDetection(Long recordingId, String version, Duration limit) {
            this.recordingId = recordingId;
            this.version = version;
            this.limit = limit;
        }
    }
}
