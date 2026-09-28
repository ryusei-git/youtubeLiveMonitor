package com.example.monitor.service;

import com.example.monitor.dto.SoundCandidateListResponse;
import com.example.monitor.dto.SoundCandidateResponse;
import com.example.monitor.dto.SoundCandidateVerdictRequest;
import com.example.monitor.dto.SoundDetectionRunResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.entity.SoundCandidate;
import com.example.monitor.entity.SoundCandidate.Verdict;
import com.example.monitor.entity.SoundDetectionRun;
import com.example.monitor.entity.SoundMark;
import com.example.monitor.exception.RecordingNotFoundException;
import com.example.monitor.exception.SoundDetectionConflictException;
import com.example.monitor.exception.SoundDetectionNotFoundException;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.repository.SoundCandidateRepository;
import com.example.monitor.repository.SoundDetectionRunRepository;
import com.example.monitor.sound.EarKissDetector;
import com.example.monitor.sound.EarKissModel;
import com.example.monitor.sound.PcmDecoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * {@link SoundDetectionService} の付け直し・ほかの版の答えの写し・実行記録・アプリの終了で止めた検出の印を確かめる（Issue #606）。
 *
 * <h2>{@code @DataJpaTest} にする理由</h2>
 * 付け直しと答えの写しの決まりは、写し元を選ぶ問い合わせ（{@link SoundCandidateRepository#findAnsweredInOtherVersions}）と
 * 見回りの対象を選ぶ問い合わせ（{@link SoundDetectionRunRepository#findPendingRecordings}）の条件に乗っている。
 * #549（写した答えを取り消しても、次の版上げで元の答えがよみがえる）は問い合わせの条件の抜けだったので、
 * リポジトリをモックにすると確かめたい所ごと差し替えてしまう。実際の H2 に JPQL を流して確かめる。
 *
 * <h2>スレッドを立てない理由</h2>
 * 見回り（{@code startPending()}）と、管理者の今すぐ検出が検出を始める経路は、検出を仮想スレッドで動かすので呼ばない。
 * <ul>
 *   <li>{@code mockStatic}・{@code mockConstruction} は、それを作ったスレッドにしか効かない。ほかのスレッドでは本物の
 *       {@link PcmDecoder#open}（{@code nice ffmpeg}）が動く。</li>
 *   <li>{@code @DataJpaTest} のテストのトランザクションはコミットされないので、ほかのスレッドからはテストが入れた行が見えない
 *       （外部キーの待ちで止まりうる）。</li>
 * </ul>
 * {@link SoundDetectionService#detect}・{@link SoundDetectionService#processPending()} は、テストのスレッドから直接呼ぶ。
 * {@code detect()} の中の {@link TransactionTemplate} はテストのトランザクションに加わるので、付け直しの結果はコミットせずに読める。
 * 検出の途中でアプリの終了が始まる場合も、差し替えた検出器の中で {@link SoundDetectionService#stop()} を呼んで、同じスレッドで作る。
 *
 * <h2>サービスを Bean にせず、テストごとに作る理由</h2>
 * 排他（{@code running}）とアプリの終了の旗（{@code stopping}）はサービスが持つので、Bean を使い回すとテストをまたいで残る。
 * {@code stop()} を呼ぶテストが、ほかのテストの見回りまで止めてしまう。
 *
 * <h2>止めた検出の印を一時ディレクトリへ向ける理由</h2>
 * 印（{@code stoppedMarker}）の既定の場所 {@code data/sound-detection-stopped} は作業ディレクトリからの相対で、
 * テストはリポジトリの直下で動く。本番の端末ではそこが稼働中のサービスの {@code data/} で、同じファイルになる。
 * 向けないと、{@code processPending()} を呼ぶテストが本番の印を読んで消し（止めた回が数えられたままになる）、
 * 検出の途中で {@code stop()} を呼ぶテストがテストの DB の録画の id を本番の印に書く（本番の見回りが別の録画の回数を戻す）。
 * そこでサービスは必ず {@code newService()} で作り、印をテストごとの一時ディレクトリへ向ける。
 */
@DataJpaTest
@DisplayName("SoundDetectionService")
class SoundDetectionServiceTest {

    /** 今の版より前の版（その 1）。検出器の版の列は 40 文字まで。 */
    private static final String OLD_V0 = "test-old-v0";

    /** 今の版より前の版（その 2）。 */
    private static final String OLD_V1 = "test-old-v1";

    @Autowired
    private RecordingRepository recordingRepository;

    @Autowired
    private SoundCandidateRepository soundCandidateRepository;

    @Autowired
    private SoundDetectionRunRepository soundDetectionRunRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TestEntityManager entityManager;

    /** 今の版。版を上げてもテストを直さずに済むよう、同梱の重みから読む。 */
    private final String version = EarKissModel.load().version();

    /** 止めた検出の印の場所（テストごとの一時ディレクトリの中）。 */
    private Path marker;

    private RecordingFileService recordingFileService;
    private CurrentAppUser currentAppUser;
    private SoundDetectionService service;
    private AppUser alice;
    private AppUser bob;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        marker = tempDir.resolve("sound-detection-stopped");
        // 厳密スタブ（MockitoExtension）にしないのは、ログイン中の利用者を使わないテストで不要なスタブとして落ちるため
        recordingFileService = mock(RecordingFileService.class);
        currentAppUser = mock(CurrentAppUser.class);
        service = newService();
        alice = appUserRepository.save(new AppUser("alice", "hashed-password", AppUser.Role.USER));
        bob = appUserRepository.save(new AppUser("bob", "hashed-password", AppUser.Role.USER));
        when(currentAppUser.require()).thenReturn(alice);
    }

    /** サービスを作る。止めた検出の印を一時ディレクトリへ向ける（クラスの説明）ので、ほかの方法で作らない。 */
    private SoundDetectionService newService() {
        SoundDetectionService created = new SoundDetectionService(recordingRepository, recordingFileService,
                soundCandidateRepository, soundDetectionRunRepository, new TransactionTemplate(transactionManager),
                currentAppUser);
        ReflectionTestUtils.setField(created, "stoppedMarker", marker.toString());
        return created;
    }

    private Recording recording(RecordingStatus status, Integer durationSeconds) {
        return recordingRepository.save(Recording.builder()
                .videoId("abcdefghijk")
                .videoTitle("耳キスのテスト")
                .filePath("test.mp4")
                .status(status)
                .durationSeconds(durationSeconds)
                .build());
    }

    private SoundCandidate candidate(Recording recording, long positionMs, String detectorVersion, Verdict verdict,
                                     AppUser reviewer) {
        SoundCandidate candidate = new SoundCandidate(recording, SoundMark.Kind.EAR_KISS, positionMs, 0.5, detectorVersion);
        if (verdict != null) {
            candidate.review(verdict, reviewer);
        }
        return soundCandidateRepository.save(candidate);
    }

    private SoundCandidate carried(SoundCandidate source, String detectorVersion) {
        return soundCandidateRepository.save(SoundCandidate.carryOver(source, detectorVersion));
    }

    /**
     * 答えた時刻を書き込む（時刻の順を待って作らないため）。呼んだ後は手元の録画と候補が切り離されるので、使うものは読み直す。
     */
    private void setReviewedAt(SoundCandidate candidate, Instant at) {
        entityManager.flush();
        if (at == null) {
            entityManager.getEntityManager()
                    .createQuery("UPDATE SoundCandidate c SET c.reviewedAt = NULL WHERE c.id = :id")
                    .setParameter("id", candidate.getId())
                    .executeUpdate();
        } else {
            entityManager.getEntityManager()
                    .createQuery("UPDATE SoundCandidate c SET c.reviewedAt = :at WHERE c.id = :id")
                    .setParameter("at", at)
                    .setParameter("id", candidate.getId())
                    .executeUpdate();
        }
        entityManager.clear();
    }

    /** 候補を DB から読み直す（書き換えが DB に届いたかを確かめるため）。 */
    private SoundCandidate reloaded(SoundCandidate candidate) {
        entityManager.flush();
        entityManager.clear();
        return soundCandidateRepository.findById(candidate.getId()).orElseThrow();
    }

    /** 検出器の最終の候補。{@code positionMs()} はフレーム × 5 なので、位置は 5 の倍数だけを渡す。 */
    private static List<EarKissDetector.FinalCandidate> finals(long... positionsMs) {
        return Arrays.stream(positionsMs)
                .mapToObj(ms -> new EarKissDetector.FinalCandidate(
                        (int) (ms / 5), 0, 0.9, 0.0, 1, (int) (ms / 5), (int) (ms / 5)))
                .toList();
    }

    private static EarKissDetector.Result result(List<EarKissDetector.FinalCandidate> finals) {
        return new EarKissDetector.Result(0, 0, new double[0], new int[0],
                List.of(), List.of(), Double.NaN, List.of(), finals);
    }

    /** ffmpeg と検出器を差し替えて、テストのスレッドで検出する。 */
    private void detectWith(Recording recording, List<EarKissDetector.FinalCandidate> finals) {
        EarKissDetector.Result result = result(finals);
        try (MockedStatic<PcmDecoder> decoder = mockStatic(PcmDecoder.class);
             MockedConstruction<EarKissDetector> detector = mockConstruction(EarKissDetector.class,
                     (mock, context) -> when(mock.detect(any())).thenReturn(result))) {
            decoder.when(() -> PcmDecoder.open(any(), any(), any())).thenReturn(new ByteArrayInputStream(new byte[0]));
            service.detect(recording);
        }
    }

    /** ffmpeg を起動できない場合を作る。 */
    private void detectFailing(Recording recording, IOException failure) {
        EarKissDetector.Result result = result(finals());
        try (MockedStatic<PcmDecoder> decoder = mockStatic(PcmDecoder.class);
             MockedConstruction<EarKissDetector> detector = mockConstruction(EarKissDetector.class,
                     (mock, context) -> when(mock.detect(any())).thenReturn(result))) {
            decoder.when(() -> PcmDecoder.open(any(), any(), any())).thenThrow(failure);
            service.detect(recording);
        }
    }

    /**
     * 検出の途中でアプリの終了が始まった場合を、スレッドを立てずに作る。検出器の中にいる間は走っている検出が入っているので、
     * {@code stop()} が印を書く。本番で JVM が止まって ffmpeg の出力が切れるのを、例外で模す。
     */
    private void detectStoppedMidway(Recording recording) {
        try (MockedStatic<PcmDecoder> decoder = mockStatic(PcmDecoder.class);
             MockedConstruction<EarKissDetector> detector = mockConstruction(EarKissDetector.class,
                     (mock, context) -> when(mock.detect(any())).thenAnswer(invocation -> {
                         service.stop();
                         throw new IOException("パイプが閉じられました");
                     }))) {
            decoder.when(() -> PcmDecoder.open(any(), any(), any())).thenReturn(new ByteArrayInputStream(new byte[0]));
            service.detect(recording);
        }
    }

    /** 今の版の候補を DB から読み直し、位置の順に返す。 */
    private List<SoundCandidate> currentRows(Recording recording) {
        entityManager.flush();
        entityManager.clear();
        return soundCandidateRepository
                .findByRecordingAndKindAndDetectorVersion(recording, SoundMark.Kind.EAR_KISS, version).stream()
                .sorted(Comparator.comparingLong(SoundCandidate::getPositionMs))
                .toList();
    }

    private SoundDetectionRun currentRun(Recording recording) {
        return soundDetectionRunRepository
                .findByRecordingAndKindAndDetectorVersion(recording, SoundMark.Kind.EAR_KISS, version).orElseThrow();
    }

    private SoundDetectionRun savedRun(Recording recording, String detectorVersion, Consumer<SoundDetectionRun> change) {
        SoundDetectionRun run = new SoundDetectionRun(recording, SoundMark.Kind.EAR_KISS, detectorVersion);
        change.accept(run);
        return soundDetectionRunRepository.save(run);
    }

    private List<Long> pendingIds() {
        return soundDetectionRunRepository
                .findPendingRecordings(SoundMark.Kind.EAR_KISS, version, SoundDetectionRun.MAX_ATTEMPTS).stream()
                .map(Recording::getId)
                .toList();
    }

    @Nested
    @DisplayName("detect()")
    class Detect {

        @Test
        @DisplayName("正常系：同じ版で付け直すと、答えの無い候補だけを消し、答えのある候補は残す")
        void testMethod01() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            SoundCandidate answered = candidate(recording, 10000, version, Verdict.CONFIRMED, alice);
            candidate(recording, 50000, version, null, null);

            detectWith(recording, finals(30000));

            List<SoundCandidate> rows = currentRows(recording);
            assertThat(rows).extracting(SoundCandidate::getPositionMs).containsExactly(10000L, 30000L);
            assertThat(rows.get(0).getId()).isEqualTo(answered.getId());
            assertThat(rows.get(0).getVerdict()).isEqualTo(Verdict.CONFIRMED);
            // 30000 の行の id は比べない。未回答の候補を作り直すかどうかは今後変わりうる（#551 の finding）
            assertThat(rows.get(1).getVerdict()).isNull();
            assertThat(rows.get(1).getCarriedFromId()).isNull();
            SoundDetectionRun run = currentRun(recording);
            assertThat(run.getStatus()).isEqualTo(SoundDetectionRun.Status.DONE);
            assertThat(run.getCandidateCount()).isEqualTo(1);
            assertThat(run.getAttempts()).isEqualTo(1);
            assertThat(run.getMessage()).isNull();
        }

        @Test
        @DisplayName("正常系：答えのある候補から 1000ms 以内には新しい候補を作らず、それより離れていれば作る")
        void testMethod02() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            candidate(recording, 10000, version, Verdict.CONFIRMED, alice);

            detectWith(recording, finals(8995, 9000, 11000, 11005));

            // ちょうど 1000ms 離れた 9000・11000 は作らない
            assertThat(currentRows(recording)).extracting(SoundCandidate::getPositionMs)
                    .containsExactly(8995L, 10000L, 11005L);
            // 作らなかった分も数える
            assertThat(currentRun(recording).getCandidateCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("正常系：写した候補は、答えを取り消されていても消さず、その近くに新しい候補を作らない")
        void testMethod03() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            SoundCandidate source = candidate(recording, 20000, OLD_V1, Verdict.CONFIRMED, alice);
            SoundCandidate copy = carried(source, version);
            copy.review(null, null);

            detectWith(recording, finals(20000));

            List<SoundCandidate> rows = currentRows(recording);
            assertThat(rows).extracting(SoundCandidate::getId).containsExactly(copy.getId());
            assertThat(rows.get(0).getVerdict()).isNull();
            assertThat(rows.get(0).getCarriedFromId()).isEqualTo(source.getId());
        }

        @Test
        @DisplayName("正常系：ほかの版の答えのある候補を今の版へ写し、答えの無い候補は写さない")
        void testMethod04() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            SoundCandidate answered = candidate(recording, 30000, OLD_V1, Verdict.CONFIRMED, alice);
            candidate(recording, 40000, OLD_V1, null, null);

            detectWith(recording, finals());

            List<SoundCandidate> rows = currentRows(recording);
            assertThat(rows).extracting(SoundCandidate::getPositionMs).containsExactly(30000L);
            SoundCandidate copy = rows.get(0);
            assertThat(copy.getVerdict()).isEqualTo(Verdict.CONFIRMED);
            assertThat(copy.getReviewedBy().getId()).isEqualTo(alice.getId());
            assertThat(copy.getCarriedFromId()).isEqualTo(answered.getId());
            // DB の精度にそろえるため、比べる相手は読み直した元の行
            assertThat(copy.getReviewedAt())
                    .isEqualTo(soundCandidateRepository.findById(answered.getId()).orElseThrow().getReviewedAt());
            assertThat(copy.getScore()).isEqualTo(0.5);
            // 写した候補は数えない
            assertThat(currentRun(recording).getCandidateCount()).isZero();
        }

        @Test
        @DisplayName("正常系：同じ位置の答えが複数の版にあれば、答えた時刻がいちばん新しいものだけを写し、答えた時刻の無い行は最後に回す")
        void testMethod05() {
            Recording saved = recording(RecordingStatus.COMPLETED, 3600);
            SoundCandidate r0 = candidate(saved, 40000, OLD_V0, Verdict.REJECTED, alice);
            SoundCandidate r1 = candidate(saved, 40500, OLD_V1, Verdict.CONFIRMED, alice);
            SoundCandidate r2 = candidate(saved, 39800, OLD_V1, Verdict.CONFIRMED, alice);
            setReviewedAt(r0, Instant.parse("2026-01-01T00:00:00Z"));
            setReviewedAt(r1, Instant.parse("2026-02-01T00:00:00Z"));
            setReviewedAt(r2, null);
            Recording recording = recordingRepository.findById(saved.getId()).orElseThrow();

            detectWith(recording, finals());

            List<SoundCandidate> rows = currentRows(recording);
            assertThat(rows).extracting(SoundCandidate::getPositionMs).containsExactly(40500L);
            assertThat(rows.get(0).getVerdict()).isEqualTo(Verdict.CONFIRMED);
            assertThat(rows.get(0).getCarriedFromId()).isEqualTo(r1.getId());
        }

        @Test
        @DisplayName("正常系：今の版に答えのある候補があれば、その近くへほかの版の答えを写さず、2 回付け直しても二重に写さない")
        void testMethod06() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            SoundCandidate current = candidate(recording, 60000, version, Verdict.REJECTED, alice);
            candidate(recording, 60300, OLD_V1, Verdict.CONFIRMED, alice);
            SoundCandidate far = candidate(recording, 90000, OLD_V1, Verdict.CONFIRMED, alice);

            detectWith(recording, finals());
            detectWith(recording, finals());

            List<SoundCandidate> rows = currentRows(recording);
            assertThat(rows).extracting(SoundCandidate::getPositionMs).containsExactly(60000L, 90000L);
            assertThat(rows.get(0).getId()).isEqualTo(current.getId());
            assertThat(rows.get(0).getVerdict()).isEqualTo(Verdict.REJECTED);
            assertThat(rows.get(1).getCarriedFromId()).isEqualTo(far.getId());
        }

        @Test
        @DisplayName("正常系：写した行の答えを取り消していれば、元の行の答えを写さない")
        void testMethod07() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            SoundCandidate source = candidate(recording, 70000, OLD_V0, Verdict.CONFIRMED, alice);
            SoundCandidate copy = carried(source, OLD_V1);
            copy.review(null, null);

            detectWith(recording, finals(70000));

            // #549 の前のコードでは、元の行の CONFIRMED が写って落ちる
            List<SoundCandidate> rows = currentRows(recording);
            assertThat(rows).extracting(SoundCandidate::getPositionMs).containsExactly(70000L);
            assertThat(rows.get(0).getVerdict()).isNull();
            assertThat(rows.get(0).getCarriedFromId()).isNull();
        }

        @Test
        @DisplayName("正常系：写した行に答え直していれば、答え直した答えを写す")
        void testMethod08() {
            Recording saved = recording(RecordingStatus.COMPLETED, 3600);
            SoundCandidate first = candidate(saved, 70000, OLD_V0, Verdict.CONFIRMED, alice);
            setReviewedAt(first, Instant.parse("2026-01-01T00:00:00Z"));
            Recording recording = recordingRepository.findById(saved.getId()).orElseThrow();
            SoundCandidate source = soundCandidateRepository.findById(first.getId()).orElseThrow();
            SoundCandidate copy = carried(source, OLD_V1);
            copy.review(Verdict.REJECTED, alice);

            detectWith(recording, finals());

            List<SoundCandidate> rows = currentRows(recording);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getVerdict()).isEqualTo(Verdict.REJECTED);
            assertThat(rows.get(0).getCarriedFromId()).isEqualTo(copy.getId());
        }

        @Test
        @DisplayName("正常系：6 時間ちょうどの録画は検出し、6 時間を超える録画は検出せずに見回りの対象から外す")
        void testMethod09() {
            Recording sixHours = recording(RecordingStatus.COMPLETED, 21600);
            detectWith(sixHours, finals());
            assertThat(currentRun(sixHours).getStatus()).isEqualTo(SoundDetectionRun.Status.DONE);

            Recording tooLong = recording(RecordingStatus.COMPLETED, 21601);
            EarKissDetector.Result result = result(finals());
            try (MockedStatic<PcmDecoder> decoder = mockStatic(PcmDecoder.class);
                 MockedConstruction<EarKissDetector> detector = mockConstruction(EarKissDetector.class,
                         (mock, context) -> when(mock.detect(any())).thenReturn(result))) {
                decoder.when(() -> PcmDecoder.open(any(), any(), any()))
                        .thenReturn(new ByteArrayInputStream(new byte[0]));
                service.detect(tooLong);

                decoder.verify(() -> PcmDecoder.open(any(), any(), any()), never());
                assertThat(detector.constructed()).isEmpty();
            }
            SoundDetectionRun run = currentRun(tooLong);
            assertThat(run.getStatus()).isEqualTo(SoundDetectionRun.Status.FAILED);
            assertThat(run.getAttempts()).isEqualTo(SoundDetectionRun.MAX_ATTEMPTS);
            assertThat(run.getMessage()).isEqualTo("6 時間を超えるため検出しない");
        }

        @Test
        @DisplayName("異常系：音声を読めなければ、候補を変えずに失敗として記録し、3 回で見回りの対象から外れる")
        void testMethod10() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            SoundCandidate unanswered = candidate(recording, 50000, version, null, null);

            detectFailing(recording, new IOException("ffmpeg を起動できませんでした"));

            SoundDetectionRun run = currentRun(recording);
            assertThat(run.getStatus()).isEqualTo(SoundDetectionRun.Status.FAILED);
            assertThat(run.getAttempts()).isEqualTo(1);
            assertThat(run.getMessage()).isEqualTo("ffmpeg を起動できませんでした");
            assertThat(currentRows(recording)).extracting(SoundCandidate::getId).containsExactly(unanswered.getId());

            detectFailing(recording, new IOException("ffmpeg を起動できませんでした"));
            detectFailing(recording, new IOException("ffmpeg を起動できませんでした"));

            assertThat(currentRun(recording).getAttempts()).isEqualTo(3);
            assertThat(pendingIds()).doesNotContain(recording.getId());
        }

        @Test
        @DisplayName("異常系：理由の無い例外は、例外のクラス名を理由にする")
        void testMethod11() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);

            detectFailing(recording, new IOException());

            assertThat(currentRun(recording).getMessage()).isEqualTo("java.io.IOException");
        }

        @Test
        @DisplayName("正常系：完了の後のやり直しは回数を数え直すので、やり直しの後の失敗 1 回では対象から外れない")
        void testMethod12() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);

            detectWith(recording, finals());
            detectWith(recording, finals());
            detectFailing(recording, new IOException("ffmpeg を起動できませんでした"));

            // #550 の前のコードでは attempts が 3 になって外れ、落ちる
            SoundDetectionRun run = currentRun(recording);
            assertThat(run.getStatus()).isEqualTo(SoundDetectionRun.Status.FAILED);
            assertThat(run.getAttempts()).isEqualTo(1);
            assertThat(pendingIds()).contains(recording.getId());
        }
    }

    @Nested
    @DisplayName("stop()")
    class Stop {

        @Test
        @DisplayName("正常系：検出が走っていなければ、印を書かない")
        void testMethod01() {
            service.stop();

            assertThat(Files.exists(marker)).isFalse();
        }

        @Test
        @DisplayName("正常系：検出の途中でアプリの終了が始まると、止めた録画と版を印に書き、失敗を記録せず、候補を変えない")
        void testMethod02() throws IOException {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            savedRun(recording, version, run -> {
                run.start();
                run.fail("x");
            });
            SoundCandidate unanswered = candidate(recording, 50000, version, null, null);

            detectStoppedMidway(recording);

            assertThat(Files.readString(marker).strip()).isEqualTo(recording.getId() + " " + version);
            SoundDetectionRun run = currentRun(recording);
            assertThat(run.getStatus()).isEqualTo(SoundDetectionRun.Status.FAILED);
            assertThat(run.getAttempts()).isEqualTo(2);
            // start() で書いたまま（失敗の理由を書かない）
            assertThat(run.getMessage()).isEqualTo("実行中に止まった");
            assertThat(run.getFinishedAt()).isNull();
            assertThat(currentRows(recording)).extracting(SoundCandidate::getId).containsExactly(unanswered.getId());
        }

        @Test
        @DisplayName("正常系：次の起動の見回りが印を読み、止めた回を回数から外して、印を消す")
        void testMethod03() throws IOException {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            savedRun(recording, version, run -> {
                run.start();
                run.fail("x");
                run.start();
            });
            Files.writeString(marker, recording.getId() + " " + version);

            // 再起動の代わりに 2 つ目のサービスを作る
            newService().processPending();

            SoundDetectionRun run = currentRun(recording);
            assertThat(run.getStatus()).isEqualTo(SoundDetectionRun.Status.FAILED);
            assertThat(run.getAttempts()).isEqualTo(1);
            assertThat(run.getMessage()).isEqualTo("アプリの終了で止めた（回数に数えない）");
            assertThat(run.getFinishedAt()).isNull();
            assertThat(Files.exists(marker)).isFalse();
        }

        @Test
        @DisplayName("正常系：印の回がもう終わっていれば（完了を書けていれば）、印を消すだけで記録は変えない")
        void testMethod04() throws IOException {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            savedRun(recording, version, run -> {
                run.start();
                run.finish(2);
            });
            Files.writeString(marker, recording.getId() + " " + version);

            newService().processPending();

            SoundDetectionRun run = currentRun(recording);
            assertThat(run.getStatus()).isEqualTo(SoundDetectionRun.Status.DONE);
            assertThat(run.getAttempts()).isEqualTo(1);
            assertThat(run.getCandidateCount()).isEqualTo(2);
            assertThat(run.getMessage()).isNull();
            assertThat(Files.exists(marker)).isFalse();
        }

        @Test
        @DisplayName("異常系：印が読めない・印の録画が無いときは、例外を投げずに印を消す")
        void testMethod05() throws IOException {
            for (String content : List.of("x", "999999 " + version)) {
                Files.writeString(marker, content);

                assertThatCode(() -> newService().processPending()).doesNotThrowAnyException();
                assertThat(Files.exists(marker)).isFalse();
            }
        }

        @Test
        @DisplayName("正常系：アプリの終了が始まった後の見回りは、検出を始めない")
        void testMethod06() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            // 止めていなければ検出する条件
            when(recordingFileService.resolveExistingFile(any())).thenReturn(Optional.of(Path.of("test.mp4")));
            service.stop();

            // 止める決まりが壊れていたときに本物の ffmpeg を起動しないよう、差し替えの中で呼ぶ
            EarKissDetector.Result result = result(finals());
            try (MockedStatic<PcmDecoder> decoder = mockStatic(PcmDecoder.class);
                 MockedConstruction<EarKissDetector> detector = mockConstruction(EarKissDetector.class,
                         (mock, context) -> when(mock.detect(any())).thenReturn(result))) {
                decoder.when(() -> PcmDecoder.open(any(), any(), any()))
                        .thenReturn(new ByteArrayInputStream(new byte[0]));
                service.processPending();

                decoder.verify(() -> PcmDecoder.open(any(), any(), any()), never());
            }
            assertThat(soundDetectionRunRepository
                    .findByRecordingAndKindAndDetectorVersion(recording, SoundMark.Kind.EAR_KISS, version)).isEmpty();
        }
    }

    @Nested
    @DisplayName("processPending()")
    class ProcessPending {

        @Test
        @DisplayName("正常系：検出の前に、見回りの対象の録画へ、ほかの版の答えのある候補だけを写す（録画中・ファイルが無くても）")
        void testMethod01() {
            Recording target = recording(RecordingStatus.COMPLETED, 3600);
            SoundCandidate answered = candidate(target, 60000, OLD_V1, Verdict.CONFIRMED, alice);
            candidate(target, 120000, OLD_V1, null, null);
            recording(RecordingStatus.RECORDING, null);

            service.processPending();
            service.processPending();

            List<SoundCandidate> rows = currentRows(target);
            assertThat(rows).extracting(SoundCandidate::getPositionMs).containsExactly(60000L);
            assertThat(rows.get(0).getVerdict()).isEqualTo(Verdict.CONFIRMED);
            assertThat(rows.get(0).getCarriedFromId()).isEqualTo(answered.getId());
            assertThat(soundDetectionRunRepository
                    .findByRecordingAndKindAndDetectorVersion(target, SoundMark.Kind.EAR_KISS, version)).isEmpty();
        }

        @Test
        @DisplayName("正常系：今の版に候補がある所へは、答えの無い候補でも先に写さない")
        void testMethod02() {
            Recording target = recording(RecordingStatus.COMPLETED, 3600);
            SoundCandidate unanswered = candidate(target, 60300, version, null, null);
            candidate(target, 60000, OLD_V1, Verdict.CONFIRMED, alice);

            service.processPending();

            assertThat(currentRows(target)).extracting(SoundCandidate::getId).containsExactly(unanswered.getId());
        }
    }

    @Nested
    @DisplayName("listCandidates()")
    class ListCandidates {

        @Test
        @DisplayName("正常系：今の版の候補だけを位置の順に返し、自分の答えにだけ reviewedByMe を付ける")
        void testMethod01() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            candidate(recording, 30000, version, null, null);
            candidate(recording, 10000, version, Verdict.CONFIRMED, alice);
            candidate(recording, 20000, version, Verdict.REJECTED, bob);
            candidate(recording, 5000, OLD_V1, Verdict.CONFIRMED, alice);

            SoundCandidateListResponse response = service.listCandidates(recording.getId(), "EAR_KISS");

            assertThat(response.candidates()).extracting(SoundCandidateResponse::positionMs)
                    .containsExactly(10000L, 20000L, 30000L);
            assertThat(response.candidates()).extracting(SoundCandidateResponse::reviewedByMe)
                    .containsExactly(true, false, false);
            assertThat(response.detectorVersion()).isEqualTo(version);
        }

        @Test
        @DisplayName("正常系：状態は、実行記録が無ければ PENDING、完了なら DONE、失敗が上限未満なら PENDING、上限なら FAILED")
        void testMethod02() {
            Recording noRun = recording(RecordingStatus.COMPLETED, 3600);
            Recording done = recording(RecordingStatus.COMPLETED, 3600);
            savedRun(done, version, run -> {
                run.start();
                run.finish(0);
            });
            Recording failedTwice = recording(RecordingStatus.COMPLETED, 3600);
            savedRun(failedTwice, version, run -> {
                run.start();
                run.fail("x");
                run.start();
                run.fail("x");
            });
            Recording failedThrice = recording(RecordingStatus.COMPLETED, 3600);
            savedRun(failedThrice, version, run -> {
                run.start();
                run.fail("x");
                run.start();
                run.fail("x");
                run.start();
                run.fail("x");
            });

            assertThat(service.listCandidates(noRun.getId(), "EAR_KISS").state())
                    .isEqualTo(SoundCandidateListResponse.State.PENDING);
            assertThat(service.listCandidates(done.getId(), "EAR_KISS").state())
                    .isEqualTo(SoundCandidateListResponse.State.DONE);
            assertThat(service.listCandidates(failedTwice.getId(), "EAR_KISS").state())
                    .isEqualTo(SoundCandidateListResponse.State.PENDING);
            assertThat(service.listCandidates(failedThrice.getId(), "EAR_KISS").state())
                    .isEqualTo(SoundCandidateListResponse.State.FAILED);
        }

        @Test
        @DisplayName("正常系：6 時間を超える録画は、実行記録によらず UNSUPPORTED")
        void testMethod03() {
            Recording noRun = recording(RecordingStatus.COMPLETED, 21601);
            Recording givenUp = recording(RecordingStatus.COMPLETED, 21601);
            savedRun(givenUp, version, run -> run.giveUp("x"));

            assertThat(service.listCandidates(noRun.getId(), "EAR_KISS").state())
                    .isEqualTo(SoundCandidateListResponse.State.UNSUPPORTED);
            assertThat(service.listCandidates(givenUp.getId(), "EAR_KISS").state())
                    .isEqualTo(SoundCandidateListResponse.State.UNSUPPORTED);
        }

        @Test
        @DisplayName("異常系：種類が EAR_KISS でなければ IllegalArgumentException、録画が無ければ RecordingNotFoundException")
        void testMethod04() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);

            assertThatThrownBy(() -> service.listCandidates(recording.getId(), "EAR_LICK"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("候補の種類が正しくありません: EAR_LICK");
            assertThatThrownBy(() -> service.listCandidates(recording.getId(), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("候補の種類が正しくありません: null");
            assertThatThrownBy(() -> service.listCandidates(999999L, "EAR_KISS"))
                    .isInstanceOf(RecordingNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("answer()")
    class Answer {

        @Test
        @DisplayName("正常系：答えると答えた人と時刻が入り、null で取り消すと両方とも空になる")
        void testMethod01() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            SoundCandidate target = candidate(recording, 10000, version, null, null);

            SoundCandidateResponse answered = service.answer(recording.getId(), target.getId(),
                    new SoundCandidateVerdictRequest("CONFIRMED"));

            assertThat(answered.verdict()).isEqualTo(Verdict.CONFIRMED);
            assertThat(answered.reviewedByMe()).isTrue();
            SoundCandidate row = reloaded(target);
            assertThat(row.getReviewedBy().getId()).isEqualTo(alice.getId());
            assertThat(row.getReviewedAt()).isNotNull();

            service.answer(recording.getId(), target.getId(), new SoundCandidateVerdictRequest(null));

            SoundCandidate cancelled = reloaded(target);
            assertThat(cancelled.getVerdict()).isNull();
            assertThat(cancelled.getReviewedBy()).isNull();
            assertThat(cancelled.getReviewedAt()).isNull();
        }

        @Test
        @DisplayName("正常系：ほかの人の答えも上書きできる")
        void testMethod02() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            SoundCandidate target = candidate(recording, 10000, version, Verdict.CONFIRMED, bob);

            service.answer(recording.getId(), target.getId(), new SoundCandidateVerdictRequest("REJECTED"));

            SoundCandidate row = reloaded(target);
            assertThat(row.getVerdict()).isEqualTo(Verdict.REJECTED);
            assertThat(row.getReviewedBy().getId()).isEqualTo(alice.getId());
        }

        @Test
        @DisplayName("異常系：答えの値が不正なら IllegalArgumentException")
        void testMethod03() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            SoundCandidate target = candidate(recording, 10000, version, null, null);

            assertThatThrownBy(() -> service.answer(recording.getId(), target.getId(),
                    new SoundCandidateVerdictRequest("MAYBE")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("答え（verdict）は CONFIRMED・REJECTED・null のどれかにしてください: MAYBE");
            assertThatThrownBy(() -> service.answer(recording.getId(), target.getId(),
                    new SoundCandidateVerdictRequest("confirmed")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("異常系：別の録画の候補・今の版でない候補・無い候補は SoundDetectionNotFoundException")
        void testMethod04() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            Recording other = recording(RecordingStatus.COMPLETED, 3600);
            SoundCandidate onOther = candidate(other, 10000, version, null, null);
            SoundCandidate oldVersion = candidate(recording, 10000, OLD_V1, null, null);
            SoundCandidateVerdictRequest request = new SoundCandidateVerdictRequest("CONFIRMED");

            assertThatThrownBy(() -> service.answer(recording.getId(), onOther.getId(), request))
                    .isInstanceOf(SoundDetectionNotFoundException.class)
                    .hasMessage("候補が見つかりません: id=" + onOther.getId());
            assertThatThrownBy(() -> service.answer(recording.getId(), oldVersion.getId(), request))
                    .isInstanceOf(SoundDetectionNotFoundException.class)
                    .hasMessage("候補が見つかりません: id=" + oldVersion.getId());
            assertThatThrownBy(() -> service.answer(recording.getId(), 999999L, request))
                    .isInstanceOf(SoundDetectionNotFoundException.class)
                    .hasMessage("候補が見つかりません: id=999999");
        }
    }

    @Nested
    @DisplayName("startDetection()")
    class StartDetection {

        @Test
        @DisplayName("異常系：再生できない録画・長さの分からない録画は SoundDetectionConflictException")
        void testMethod01() {
            List<Recording> targets = List.of(
                    recording(RecordingStatus.RECORDING, null),
                    recording(RecordingStatus.FAILED, 60),
                    recording(RecordingStatus.COMPLETED, null));

            for (Recording target : targets) {
                assertThatThrownBy(() -> service.startDetection(target.getId(), "EAR_KISS", false))
                        .isInstanceOf(SoundDetectionConflictException.class)
                        .hasMessageStartingWith("再生できる録画で、長さが分かっているものだけ検出できます: id=");
            }
        }

        @Test
        @DisplayName("異常系：今の版で検出済みなら、force が無いと SoundDetectionConflictException")
        void testMethod02() {
            Recording done = recording(RecordingStatus.COMPLETED, 3600);
            savedRun(done, version, run -> {
                run.start();
                run.finish(0);
            });

            assertThatThrownBy(() -> service.startDetection(done.getId(), "EAR_KISS", false))
                    .isInstanceOf(SoundDetectionConflictException.class)
                    .hasMessage("今の版で検出済みです。やり直すときは force=true を付けてください: id=" + done.getId());
        }

        @Test
        @DisplayName("異常系：検出が走っていれば SoundDetectionConflictException で、実行記録を作らない")
        void testMethod03() {
            Recording fresh = recording(RecordingStatus.COMPLETED, 3600);
            Recording done = recording(RecordingStatus.COMPLETED, 3600);
            savedRun(done, version, run -> {
                run.start();
                run.finish(0);
            });
            AtomicBoolean running = (AtomicBoolean) ReflectionTestUtils.getField(service, "running");
            running.set(true);
            try {
                assertThatThrownBy(() -> service.startDetection(fresh.getId(), "EAR_KISS", false))
                        .isInstanceOf(SoundDetectionConflictException.class)
                        .hasMessage("耳キスの検出が走っています。終わってから始めてください");
                assertThatThrownBy(() -> service.startDetection(done.getId(), "EAR_KISS", true))
                        .isInstanceOf(SoundDetectionConflictException.class)
                        .hasMessage("耳キスの検出が走っています。終わってから始めてください");

                assertThat(soundDetectionRunRepository
                        .findByRecordingAndKindAndDetectorVersion(fresh, SoundMark.Kind.EAR_KISS, version)).isEmpty();
            } finally {
                running.set(false);
            }
        }

        @Test
        @DisplayName("異常系：種類が不正なら IllegalArgumentException、録画が無ければ RecordingNotFoundException")
        void testMethod04() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);

            assertThatThrownBy(() -> service.startDetection(recording.getId(), "EAR_LICK", false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("候補の種類が正しくありません: EAR_LICK");
            assertThatThrownBy(() -> service.startDetection(999999L, "EAR_KISS", false))
                    .isInstanceOf(RecordingNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getRun()")
    class GetRun {

        @Test
        @DisplayName("正常系：今の版の実行記録を返す")
        void testMethod01() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            savedRun(recording, version, run -> {
                run.start();
                run.finish(3);
            });

            SoundDetectionRunResponse response = service.getRun(recording.getId(), "EAR_KISS");

            assertThat(response.status()).isEqualTo(SoundDetectionRun.Status.DONE);
            assertThat(response.candidateCount()).isEqualTo(3);
            assertThat(response.attempts()).isEqualTo(1);
            assertThat(response.detectorVersion()).isEqualTo(version);
        }

        @Test
        @DisplayName("異常系：今の版の記録が無ければ SoundDetectionNotFoundException（ほかの版の記録だけがある場合も）")
        void testMethod02() {
            Recording noRun = recording(RecordingStatus.COMPLETED, 3600);
            Recording oldOnly = recording(RecordingStatus.COMPLETED, 3600);
            savedRun(oldOnly, OLD_V1, run -> {
                run.start();
                run.finish(3);
            });

            assertThatThrownBy(() -> service.getRun(noRun.getId(), "EAR_KISS"))
                    .isInstanceOf(SoundDetectionNotFoundException.class)
                    .hasMessage("今の版の検出の記録がありません: recording=" + noRun.getId());
            assertThatThrownBy(() -> service.getRun(oldOnly.getId(), "EAR_KISS"))
                    .isInstanceOf(SoundDetectionNotFoundException.class)
                    .hasMessage("今の版の検出の記録がありません: recording=" + oldOnly.getId());
        }
    }
}
