package com.example.monitor.repository;

import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.entity.SoundDetectionRun;
import com.example.monitor.entity.SoundMark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SoundDetectionRunRepository} の見回りの対象の問い合わせと、実行記録の一意制約を確かめる（Issue #606）。
 *
 * <h2>{@code @DataJpaTest} にする理由</h2>
 * 見回りの対象（{@link SoundDetectionRunRepository#findPendingRecordings}）は JPQL の条件（再生できる録画・長さ・
 * その版の記録の状態と回数）と並びで決まり、一意制約は実際に作られた DDL で決まる。どちらもモックでは確かめられないので、
 * {@link UserSubscriptionRepositoryTest} と同じくインメモリの H2 に対して SQL を流す。
 *
 * <h2>スレッドを立てない理由</h2>
 * リポジトリはテストのスレッドから直接呼べる。{@code @DataJpaTest} のテストのトランザクションはコミットされず、
 * ほかのスレッドからは行が見えないので、ほかのスレッドを使う理由も無い。並びは録画の開始時刻の新しい順（同じなら id の大きい順）で、
 * 保存した順に開始時刻が入るので、時刻の順を待って作る必要も無い。
 */
@DataJpaTest
@DisplayName("SoundDetectionRunRepository")
class SoundDetectionRunRepositoryTest {

    private static final String VERSION = "test-v2";
    private static final String OTHER_VERSION = "test-v1";

    @Autowired
    private SoundDetectionRunRepository soundDetectionRunRepository;

    @Autowired
    private RecordingRepository recordingRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Recording recording(RecordingStatus status, Integer durationSeconds) {
        return recordingRepository.save(Recording.builder()
                .videoId("abcdefghijk")
                .videoTitle("耳キスのテスト")
                .filePath("test.mp4")
                .status(status)
                .durationSeconds(durationSeconds)
                .build());
    }

    private SoundDetectionRun run(Recording recording, String detectorVersion, Consumer<SoundDetectionRun> change) {
        SoundDetectionRun run = new SoundDetectionRun(recording, SoundMark.Kind.EAR_KISS, detectorVersion);
        change.accept(run);
        return soundDetectionRunRepository.save(run);
    }

    private List<Long> pendingIds() {
        entityManager.flush();
        entityManager.clear();
        return soundDetectionRunRepository
                .findPendingRecordings(SoundMark.Kind.EAR_KISS, VERSION, SoundDetectionRun.MAX_ATTEMPTS).stream()
                .map(Recording::getId)
                .toList();
    }

    @Nested
    @DisplayName("findPendingRecordings()")
    class FindPendingRecordings {

        @Test
        @DisplayName("正常系：再生できる録画で長さが分かり、その版の記録が無いものを、開始の新しい順に返す")
        void testMethod01() {
            Recording r1 = recording(RecordingStatus.COMPLETED, 3600);
            Recording r2 = recording(RecordingStatus.PARTIAL, 1800);
            recording(RecordingStatus.RECORDING, null);
            recording(RecordingStatus.FAILED, 60);
            recording(RecordingStatus.COMPLETED, null);

            assertThat(pendingIds()).containsExactly(r2.getId(), r1.getId());
        }

        @Test
        @DisplayName("正常系：その版で完了した録画は外し、ほかの版で完了しただけの録画は返す")
        void testMethod02() {
            Recording doneInThisVersion = recording(RecordingStatus.COMPLETED, 3600);
            run(doneInThisVersion, VERSION, run -> {
                run.start();
                run.finish(0);
            });
            Recording doneInOtherVersion = recording(RecordingStatus.COMPLETED, 3600);
            run(doneInOtherVersion, OTHER_VERSION, run -> {
                run.start();
                run.finish(0);
            });

            assertThat(pendingIds()).containsExactly(doneInOtherVersion.getId());
        }

        @Test
        @DisplayName("正常系：失敗が上限の回数に達した録画は外し、上限未満なら返す")
        void testMethod03() {
            Recording failedTwice = recording(RecordingStatus.COMPLETED, 3600);
            run(failedTwice, VERSION, run -> {
                run.start();
                run.fail("x");
                run.start();
                run.fail("x");
            });
            Recording failedThrice = recording(RecordingStatus.COMPLETED, 3600);
            run(failedThrice, VERSION, run -> {
                run.start();
                run.fail("x");
                run.start();
                run.fail("x");
                run.start();
                run.fail("x");
            });
            Recording givenUp = recording(RecordingStatus.COMPLETED, 3600);
            run(givenUp, VERSION, run -> run.giveUp("x"));

            assertThat(pendingIds()).containsExactly(failedTwice.getId());
        }
    }

    @Nested
    @DisplayName("findByRecordingAndKindAndDetectorVersion()")
    class FindByRecordingAndKindAndDetectorVersion {

        @Test
        @DisplayName("正常系：録画・種類・版の組の記録を返し、版が違えば空")
        void testMethod01() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            SoundDetectionRun saved = run(recording, VERSION, SoundDetectionRun::start);
            entityManager.flush();
            entityManager.clear();

            assertThat(soundDetectionRunRepository
                    .findByRecordingAndKindAndDetectorVersion(recording, SoundMark.Kind.EAR_KISS, VERSION))
                    .hasValueSatisfying(found -> assertThat(found.getId()).isEqualTo(saved.getId()));
            assertThat(soundDetectionRunRepository
                    .findByRecordingAndKindAndDetectorVersion(recording, SoundMark.Kind.EAR_KISS, OTHER_VERSION))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("save()")
    class Save {

        @Test
        @DisplayName("異常系：同じ録画・種類・版の記録を 2 つ保存すると一意制約違反になる")
        void testMethod01() {
            Recording recording = recording(RecordingStatus.COMPLETED, 3600);
            run(recording, VERSION, SoundDetectionRun::start);
            SoundDetectionRun duplicate = new SoundDetectionRun(recording, SoundMark.Kind.EAR_KISS, VERSION);
            duplicate.start();

            assertThatThrownBy(() -> soundDetectionRunRepository.saveAndFlush(duplicate))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
