package com.example.monitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ActiveVideoJobs} の単体テスト。
 *
 * <p>ここは<b>自動録画（{@link StreamRecorder}）と手動ダウンロード
 * （{@link VideoDownloadService}）が共有している予約機構</b>そのもの。
 * 「確認してから登録する」ではなく「追加が成否をその場で返す」ことが排他の土台なので、
 * ここが壊れると両方が黙って同じ出力先へ {@code yt-dlp} を二重起動する。
 */
@DisplayName("ActiveVideoJobs")
class ActiveVideoJobsTest {

    /** 本物と同じ形式の動画 ID。 */
    private static final String VIDEO_ID = "aqz-KE-bpKQ";

    private ActiveVideoJobs activeVideoJobs;

    @BeforeEach
    void setUp() {
        activeVideoJobs = new ActiveVideoJobs();
    }

    @Nested
    @DisplayName("reserve()")
    class Reserve {

        @Test
        @DisplayName("正常系：未予約の動画IDは予約できる")
        void testMethod01() {
            assertThat(activeVideoJobs.reserve(VIDEO_ID)).isTrue();
        }

        @Test
        @DisplayName("異常系：予約済みの動画IDは二度目の予約が失敗する")
        void testMethod02() {
            // 「確認」と「登録」を分けると、その隙間に別スレッドが入って二重起動しうる。
            // 1回の操作で成否が返ることが排他そのものになっている
            activeVideoJobs.reserve(VIDEO_ID);

            assertThat(activeVideoJobs.reserve(VIDEO_ID)).isFalse();
        }

        @Test
        @DisplayName("正常系：別の動画IDは互いに影響しない")
        void testMethod03() {
            activeVideoJobs.reserve(VIDEO_ID);

            assertThat(activeVideoJobs.reserve("dQw4w9WgXcQ")).isTrue();
        }
    }

    @Nested
    @DisplayName("release()")
    class Release {

        @Test
        @DisplayName("正常系：解放すると同じ動画IDを再び予約できる")
        void testMethod01() {
            // 解放し損ねると、その動画IDは以降永久に録画もダウンロードもできなくなる
            activeVideoJobs.reserve(VIDEO_ID);

            activeVideoJobs.release(VIDEO_ID);

            assertThat(activeVideoJobs.reserve(VIDEO_ID)).isTrue();
        }

        @Test
        @DisplayName("正常系：予約していない動画IDを解放しても例外にならない")
        void testMethod02() {
            // 起動に失敗した経路からも解放が呼ばれるため、未予約でも落ちては困る
            activeVideoJobs.release(VIDEO_ID);

            assertThat(activeVideoJobs.isActive(VIDEO_ID)).isFalse();
        }

        @Test
        @DisplayName("正常系：解放の対象は指定した動画IDだけ")
        void testMethod03() {
            activeVideoJobs.reserve(VIDEO_ID);
            activeVideoJobs.reserve("dQw4w9WgXcQ");

            activeVideoJobs.release(VIDEO_ID);

            assertThat(activeVideoJobs.isActive("dQw4w9WgXcQ")).isTrue();
        }
    }

    @Nested
    @DisplayName("isActive()")
    class IsActive {

        @Test
        @DisplayName("正常系：予約していなければ false を返す")
        void testMethod01() {
            assertThat(activeVideoJobs.isActive(VIDEO_ID)).isFalse();
        }

        @Test
        @DisplayName("正常系：予約中は true を返す")
        void testMethod02() {
            // RecordingReconciler が「まだ処理中のものを失敗と誤判定しない」ために見る
            activeVideoJobs.reserve(VIDEO_ID);

            assertThat(activeVideoJobs.isActive(VIDEO_ID)).isTrue();
        }

        @Test
        @DisplayName("正常系：解放後は false を返す")
        void testMethod03() {
            activeVideoJobs.reserve(VIDEO_ID);

            activeVideoJobs.release(VIDEO_ID);

            assertThat(activeVideoJobs.isActive(VIDEO_ID)).isFalse();
        }
    }
}
