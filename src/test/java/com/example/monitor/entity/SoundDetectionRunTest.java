package com.example.monitor.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SoundDetectionRun} の状態の移り変わり（回数・理由・時刻）を確かめる（Issue #606）。
 *
 * <p>状態を変えるのはエンティティの手書きのメソッドだけで、DB も Spring も要らないので、単体テストにする
 * （{@code @DataJpaTest} は要らない）。ほかのスレッドも使わない。
 */
@DisplayName("SoundDetectionRun")
class SoundDetectionRunTest {

    private static SoundDetectionRun newRun() {
        return new SoundDetectionRun(null, SoundMark.Kind.EAR_KISS, "test-v2");
    }

    @Nested
    @DisplayName("start()")
    class Start {

        @Test
        @DisplayName("正常系：始めると「失敗・回数 1・実行中に止まった」になる")
        void testMethod01() {
            SoundDetectionRun run = newRun();

            run.start();

            assertThat(run.getStatus()).isEqualTo(SoundDetectionRun.Status.FAILED);
            assertThat(run.getAttempts()).isEqualTo(1);
            assertThat(run.getMessage()).isEqualTo("実行中に止まった");
            assertThat(run.getStartedAt()).isNotNull();
            assertThat(run.getFinishedAt()).isNull();
            assertThat(run.getCandidateCount()).isZero();
        }

        @Test
        @DisplayName("正常系：失敗の後に始めると回数が増える")
        void testMethod02() {
            SoundDetectionRun run = newRun();

            run.start();
            run.fail("x");
            run.start();

            assertThat(run.getAttempts()).isEqualTo(2);
        }

        @Test
        @DisplayName("正常系：完了の後に始めると回数を 1 から数え直す")
        void testMethod03() {
            SoundDetectionRun run = newRun();

            run.start();
            run.finish(0);
            run.start();

            assertThat(run.getAttempts()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("finish()")
    class Finish {

        @Test
        @DisplayName("正常系：DONE・候補の数・message null・finishedAt が入り、回数は変わらない")
        void testMethod01() {
            SoundDetectionRun run = newRun();
            run.start();

            run.finish(5);

            assertThat(run.getStatus()).isEqualTo(SoundDetectionRun.Status.DONE);
            assertThat(run.getCandidateCount()).isEqualTo(5);
            assertThat(run.getMessage()).isNull();
            assertThat(run.getFinishedAt()).isNotNull();
            assertThat(run.getAttempts()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("fail()")
    class Fail {

        @Test
        @DisplayName("正常系：理由は 500 文字で切り、回数は変わらない")
        void testMethod01() {
            SoundDetectionRun run = newRun();
            run.start();
            String reason = "あ".repeat(500) + "い";

            run.fail(reason);

            assertThat(run.getStatus()).isEqualTo(SoundDetectionRun.Status.FAILED);
            assertThat(run.getMessage()).isEqualTo("あ".repeat(500));
            assertThat(run.getAttempts()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("giveUp()")
    class GiveUp {

        @Test
        @DisplayName("正常系：回数を上限にし、理由を入れ、startedAt と finishedAt を同じ時刻にする")
        void testMethod01() {
            SoundDetectionRun run = newRun();

            run.giveUp("6 時間を超えるため検出しない");

            assertThat(run.getStatus()).isEqualTo(SoundDetectionRun.Status.FAILED);
            assertThat(run.getAttempts()).isEqualTo(SoundDetectionRun.MAX_ATTEMPTS);
            assertThat(run.getMessage()).isEqualTo("6 時間を超えるため検出しない");
            assertThat(run.getStartedAt()).isNotNull();
            assertThat(run.getFinishedAt()).isEqualTo(run.getStartedAt());
        }
    }

    @Nested
    @DisplayName("cancel()")
    class Cancel {

        @Test
        @DisplayName("正常系：始めたまま（実行中に止まった）の回を止めると、回数を 1 戻し、理由を「アプリの終了で止めた」にする")
        void testMethod01() {
            SoundDetectionRun run = newRun();
            run.start();
            run.start();

            run.cancel();

            assertThat(run.getStatus()).isEqualTo(SoundDetectionRun.Status.FAILED);
            assertThat(run.getAttempts()).isEqualTo(1);
            assertThat(run.getMessage()).isEqualTo("アプリの終了で止めた（回数に数えない）");
            // 検出が終わっていないので入れない
            assertThat(run.getFinishedAt()).isNull();
        }

        @Test
        @DisplayName("正常系：2 回呼んでも、戻すのは 1 回分だけ")
        void testMethod02() {
            SoundDetectionRun run = newRun();
            run.start();
            run.start();

            run.cancel();
            run.cancel();

            assertThat(run.getAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系：完了を書いた後の回は変えない")
        void testMethod03() {
            SoundDetectionRun run = newRun();
            run.start();
            run.finish(2);

            run.cancel();

            assertThat(run.getStatus()).isEqualTo(SoundDetectionRun.Status.DONE);
            assertThat(run.getAttempts()).isEqualTo(1);
            assertThat(run.getCandidateCount()).isEqualTo(2);
            assertThat(run.getMessage()).isNull();
        }

        @Test
        @DisplayName("正常系：失敗を書いた後の回は変えない")
        void testMethod04() {
            SoundDetectionRun run = newRun();
            run.start();
            run.fail("x");

            run.cancel();

            assertThat(run.getStatus()).isEqualTo(SoundDetectionRun.Status.FAILED);
            assertThat(run.getAttempts()).isEqualTo(1);
            assertThat(run.getMessage()).isEqualTo("x");
        }
    }
}
