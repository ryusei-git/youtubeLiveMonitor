package com.example.monitor.service;

import com.example.monitor.dto.SoundMarkRequest;
import com.example.monitor.dto.SoundMarkResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.exception.RecordingNotFoundException;
import com.example.monitor.exception.SoundMarkNotFoundException;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.repository.SoundMarkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SoundMarkService} の二度押しのまとめ・位置の上限・本人だけの削除を確かめる（Issue #606）。
 *
 * <h2>{@code @DataJpaTest} にする理由</h2>
 * 二度押しは「同じ人・同じ種類・前後の範囲」の問い合わせ、本人だけの削除は「印・録画・利用者」の組の削除で決まっている。
 * リポジトリをモックにすると、確かめたい条件ごと差し替えてしまうので、実際の H2 に問い合わせを流す。
 *
 * <h2>スレッドを立てない理由</h2>
 * このサービスは呼び出したスレッドの中で読み書きするだけで、ほかのスレッドを使わない。{@code @DataJpaTest} のテストの
 * トランザクションはコミットされず、ほかのスレッドからは行が見えないので、テストもテストのスレッドから直接呼ぶ。
 *
 * <p>サービスは Bean にせず、{@link SoundDetectionServiceTest} とそろえて {@code @BeforeEach} で作る。
 * ログイン中の利用者（{@link CurrentAppUser}）は、テストの中で別の利用者に差し替えるのでモックにする。
 */
@DataJpaTest
@DisplayName("SoundMarkService")
class SoundMarkServiceTest {

    @Autowired
    private SoundMarkRepository soundMarkRepository;

    @Autowired
    private RecordingRepository recordingRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private TestEntityManager entityManager;

    private CurrentAppUser currentAppUser;
    private SoundMarkService service;
    private AppUser alice;
    private AppUser bob;
    private Recording recording;

    @BeforeEach
    void setUp() {
        currentAppUser = mock(CurrentAppUser.class);
        service = new SoundMarkService(soundMarkRepository, recordingRepository, currentAppUser);
        alice = appUserRepository.save(new AppUser("alice", "hashed-password", AppUser.Role.USER));
        bob = appUserRepository.save(new AppUser("bob", "hashed-password", AppUser.Role.USER));
        recording = recording(60);
        when(currentAppUser.require()).thenReturn(alice);
    }

    private Recording recording(Integer durationSeconds) {
        return recordingRepository.save(Recording.builder()
                .videoId("abcdefghijk")
                .videoTitle("耳キスのテスト")
                .filePath("test.mp4")
                .status(RecordingStatus.COMPLETED)
                .durationSeconds(durationSeconds)
                .build());
    }

    private SoundMarkResponse add(Recording target, long positionMs) {
        return service.add(target.getId(), new SoundMarkRequest("EAR_KISS", positionMs));
    }

    @Nested
    @DisplayName("add()")
    class Add {

        @Test
        @DisplayName("正常系：印を付けると、位置と mine=true を返す")
        void testMethod01() {
            SoundMarkResponse response = service.add(recording.getId(), new SoundMarkRequest("EAR_KISS", 5000L));

            assertThat(response.id()).isNotNull();
            assertThat(response.positionMs()).isEqualTo(5000L);
            assertThat(response.mine()).isTrue();
        }

        @Test
        @DisplayName("正常系：同じ人が前後 1000ms 以内に付け直すと、新しく作らずに前の印を返す")
        void testMethod02() {
            SoundMarkResponse first = add(recording, 5000);

            SoundMarkResponse later = add(recording, 6000);
            SoundMarkResponse earlier = add(recording, 4000);

            assertThat(later.id()).isEqualTo(first.id());
            assertThat(earlier.id()).isEqualTo(first.id());
            assertThat(soundMarkRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系：1000ms より離れていれば、別の印を作る")
        void testMethod03() {
            SoundMarkResponse first = add(recording, 5000);

            SoundMarkResponse second = add(recording, 6001);

            assertThat(second.id()).isNotEqualTo(first.id());
            assertThat(soundMarkRepository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("正常系：ほかの人が近くに付けた印はまとめない")
        void testMethod04() {
            SoundMarkResponse byAlice = add(recording, 5000);
            when(currentAppUser.require()).thenReturn(bob);

            SoundMarkResponse byBob = add(recording, 5500);

            assertThat(byBob.id()).isNotEqualTo(byAlice.id());
        }

        @Test
        @DisplayName("異常系：位置が無い・負なら IllegalArgumentException")
        void testMethod05() {
            assertThatThrownBy(() -> service.add(recording.getId(), new SoundMarkRequest("EAR_KISS", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("印の位置（positionMs）は 0 以上で指定してください");
            assertThatThrownBy(() -> service.add(recording.getId(), new SoundMarkRequest("EAR_KISS", -1L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("印の位置（positionMs）は 0 以上で指定してください");
        }

        @Test
        @DisplayName("正常系：位置は録画の長さ＋1000ms まで付けられ、長さの分からない録画では上限を見ない")
        void testMethod06() {
            SoundMarkResponse atLimit = add(recording, 61000);
            Recording unknownLength = recording(null);
            SoundMarkResponse farAway = add(unknownLength, 10000000);

            assertThat(atLimit.positionMs()).isEqualTo(61000L);
            assertThat(farAway.positionMs()).isEqualTo(10000000L);
        }

        @Test
        @DisplayName("異常系：録画の長さ＋1000ms を超える位置は IllegalArgumentException")
        void testMethod07() {
            assertThatThrownBy(() -> add(recording, 61001))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("印の位置（positionMs）が録画の長さを超えています");
        }

        @Test
        @DisplayName("異常系：種類が不正なら IllegalArgumentException、録画が無ければ RecordingNotFoundException")
        void testMethod08() {
            assertThatThrownBy(() -> service.add(recording.getId(), new SoundMarkRequest("EAR_LICK", 5000L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("印の種類が正しくありません: EAR_LICK");
            assertThatThrownBy(() -> service.add(recording.getId(), new SoundMarkRequest(null, 5000L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("印の種類が正しくありません: null");
            assertThatThrownBy(() -> service.add(999999L, new SoundMarkRequest("EAR_KISS", 5000L)))
                    .isInstanceOf(RecordingNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("list()")
    class ListMarks {

        @Test
        @DisplayName("正常系：その録画の印を、位置の順（同じ位置は付けた順）にほかの人の印も含めて返す")
        void testMethod01() {
            SoundMarkResponse aliceAt3000 = add(recording, 3000);
            when(currentAppUser.require()).thenReturn(bob);
            SoundMarkResponse bobAt3000 = add(recording, 3000);
            SoundMarkResponse bobAt1000 = add(recording, 1000);
            add(recording(60), 2000);
            when(currentAppUser.require()).thenReturn(alice);

            List<SoundMarkResponse> marks = service.list(recording.getId(), "EAR_KISS");

            assertThat(marks).extracting(SoundMarkResponse::id)
                    .containsExactly(bobAt1000.id(), aliceAt3000.id(), bobAt3000.id());
            assertThat(marks).extracting(SoundMarkResponse::mine).containsExactly(false, true, false);
        }

        @Test
        @DisplayName("異常系：種類が不正なら IllegalArgumentException")
        void testMethod02() {
            assertThatThrownBy(() -> service.list(recording.getId(), "EAR_LICK"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("印の種類が正しくありません: EAR_LICK");
        }
    }

    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("正常系：自分の印を消せる")
        void testMethod01() {
            SoundMarkResponse mine = add(recording, 5000);

            service.delete(recording.getId(), mine.id());
            entityManager.flush();
            entityManager.clear();

            assertThat(soundMarkRepository.existsById(mine.id())).isFalse();
        }

        @Test
        @DisplayName("異常系：ほかの人の印・別の録画の印・無い印は SoundMarkNotFoundException で、印は残る")
        void testMethod02() {
            when(currentAppUser.require()).thenReturn(bob);
            SoundMarkResponse byBob = add(recording, 5000);
            when(currentAppUser.require()).thenReturn(alice);
            SoundMarkResponse onOther = add(recording(60), 5000);

            assertThatThrownBy(() -> service.delete(recording.getId(), byBob.id()))
                    .isInstanceOf(SoundMarkNotFoundException.class)
                    .hasMessage("印が見つかりません: id=" + byBob.id());
            assertThatThrownBy(() -> service.delete(recording.getId(), onOther.id()))
                    .isInstanceOf(SoundMarkNotFoundException.class)
                    .hasMessage("印が見つかりません: id=" + onOther.id());
            assertThatThrownBy(() -> service.delete(recording.getId(), 999999L))
                    .isInstanceOf(SoundMarkNotFoundException.class)
                    .hasMessage("印が見つかりません: id=999999");
            entityManager.flush();
            entityManager.clear();

            assertThat(soundMarkRepository.existsById(byBob.id())).isTrue();
            assertThat(soundMarkRepository.existsById(onOther.id())).isTrue();
        }

        @Test
        @DisplayName("異常系：録画が無ければ RecordingNotFoundException")
        void testMethod03() {
            SoundMarkResponse mine = add(recording, 5000);

            assertThatThrownBy(() -> service.delete(999999L, mine.id()))
                    .isInstanceOf(RecordingNotFoundException.class);
        }
    }
}
