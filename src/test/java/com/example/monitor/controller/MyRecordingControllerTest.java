package com.example.monitor.controller;

import com.example.monitor.dto.PageResponse;
import com.example.monitor.dto.RecordingFavoriteRequest;
import com.example.monitor.dto.RecordingGenreCountResponse;
import com.example.monitor.dto.RecordingMarkResponse;
import com.example.monitor.dto.RecordingResponse;
import com.example.monitor.dto.RecordingWatchedRequest;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.entity.RecordingMark;
import com.example.monitor.exception.RecordingNotFoundException;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.service.RecordingHistoryService;
import com.example.monitor.service.RecordingMarkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MyRecordingController")
class MyRecordingControllerTest {

    @Mock
    private RecordingHistoryService recordingHistoryService;

    @Mock
    private RecordingMarkService recordingMarkService;

    @Mock
    private MonitoredChannelRepository monitoredChannelRepository;

    @InjectMocks
    private MyRecordingController controller;

    /** ログイン中の利用者。 */
    private static final Authentication AUTH = new TestingAuthenticationToken("user", null);

    /** 既定の並び順（開始時刻の新しい順 → 主キーの大きい順）。 */
    private static final Sort NEWEST = Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"));

    /** 録画。ログイン中の利用者は、このチャンネルを購読していない（購読を見る口が無い）。 */
    private static Recording recording(Long id) {
        MonitoredChannel channel = new MonitoredChannel("UCxxxxxxxx", "テストチャンネル");
        channel.setId(7L);
        return Recording.builder()
                .id(id).channel(channel).videoId("video001")
                .filePath("UCxxxxxxxx/video001.mp4")
                .status(RecordingStatus.COMPLETED).startedAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("listMyRecordings()")
    class ListMyRecordings {

        @Test
        @DisplayName("正常系：購読に限らない検索に、ログイン中の利用者と絞り込み条件・並び順・ページ指定をそのまま渡す")
        void testMethod01() {
            LocalDate from = LocalDate.of(2026, 9, 1);
            LocalDate to = LocalDate.of(2026, 9, 30);
            Sort oldest = Sort.by(Sort.Order.asc("startedAt"), Sort.Order.asc("id"));
            // 引数が 1 つでも違えばスタブが一致せず、テストが失敗する
            when(recordingHistoryService.search("user", false, 7L, "ASMR", RecordingStatus.COMPLETED,
                    from, to, "雑談", false, true, true, PageRequest.of(2, 50, oldest)))
                    .thenReturn(new PageImpl<>(List.of(recording(1L))));

            PageResponse<RecordingResponse> result = controller.listMyRecordings(
                    7L, "ASMR", RecordingStatus.COMPLETED, "oldest", from, to, "雑談", "unwatched",
                    true, true, 2, 50, AUTH);

            assertThat(result.content()).extracting(RecordingResponse::id).containsExactly(1L);
        }

        @Test
        @DisplayName("異常系：知らない並び順・視聴状態、逆転した期間、範囲外の件数、200文字を超える検索語はIllegalArgumentException（400）が発生し、検索しない")
        void testMethod02() {
            assertThatThrownBy(() -> controller.listMyRecordings(null, null, null, "foo", null, null, null, null,
                    false, false, 0, 20, AUTH))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controller.listMyRecordings(null, null, null, "newest", null, null, null, "foo",
                    false, false, 0, 20, AUTH))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controller.listMyRecordings(null, null, null, "newest",
                    LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 1), null, null, false, false, 0, 20, AUTH))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controller.listMyRecordings(null, null, null, "newest", null, null, null, null,
                    false, false, 0, 0, AUTH))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controller.listMyRecordings(null, null, null, "newest", null, null, null, null,
                    false, false, 0, 101, AUTH))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controller.listMyRecordings(null, "a".repeat(201), null, "newest", null, null,
                    null, null, false, false, 0, 20, AUTH))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(recordingHistoryService);
        }

        @Test
        @DisplayName("正常系：200文字ちょうどの検索語は受け付ける")
        void testMethod03() {
            String keyword = "a".repeat(200);
            when(recordingHistoryService.search("user", false, null, keyword, null, null, null, null, null,
                    false, false, PageRequest.of(0, 20, NEWEST)))
                    .thenReturn(new PageImpl<>(List.of()));

            PageResponse<RecordingResponse> result = controller.listMyRecordings(
                    null, keyword, null, "newest", null, null, null, null, false, false, 0, 20, AUTH);

            assertThat(result.content()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getRecording()")
    class GetRecording {

        @Test
        @DisplayName("正常系：購読に関係なく、録画にログイン中の利用者の視聴済み・お気に入りを付けて返す")
        void testMethod01() {
            RecordingMark mark = new RecordingMark();
            mark.setWatchedAt(Instant.now());
            mark.setFavorite(true);
            Recording recording = recording(1L);
            when(recordingHistoryService.findById(1L)).thenReturn(recording);
            when(recordingHistoryService.findMarks("user", List.of(1L))).thenReturn(Map.of(1L, mark));

            RecordingResponse result = controller.getRecording(1L, AUTH);

            assertThat(result.id()).isEqualTo(1L);
            assertThat(result.watched()).isTrue();
            assertThat(result.favorite()).isTrue();
        }

        @Test
        @DisplayName("異常系：無い録画はRecordingNotFoundException（404）が発生する")
        void testMethod02() {
            when(recordingHistoryService.findById(1L)).thenThrow(new RecordingNotFoundException(1L));

            assertThatThrownBy(() -> controller.getRecording(1L, AUTH))
                    .isInstanceOf(RecordingNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("setWatched()")
    class SetWatched {

        @Test
        @DisplayName("正常系：視聴済みの指定をRecordingMarkServiceへ渡し、変更後の印を返す")
        void testMethod01() {
            RecordingMarkResponse marked = new RecordingMarkResponse(1L, true, false);
            when(recordingMarkService.setWatched(1L, true)).thenReturn(marked);

            assertThat(controller.setWatched(1L, new RecordingWatchedRequest(true))).isSameAs(marked);
        }
    }

    @Nested
    @DisplayName("setFavorite()")
    class SetFavorite {

        @Test
        @DisplayName("正常系：お気に入りの指定をRecordingMarkServiceへ渡し、変更後の印を返す")
        void testMethod01() {
            RecordingMarkResponse marked = new RecordingMarkResponse(1L, false, true);
            when(recordingMarkService.setFavorite(1L, true)).thenReturn(marked);

            assertThat(controller.setFavorite(1L, new RecordingFavoriteRequest(true))).isSameAs(marked);
        }
    }

    @Nested
    @DisplayName("getGenres()")
    class GetGenres {

        @Test
        @DisplayName("正常系：購読に限らない、再生できる録画のジャンル別件数をそのまま返す")
        void testMethod01() {
            List<RecordingGenreCountResponse> genres = List.of(new RecordingGenreCountResponse("ASMR", 3));
            when(recordingHistoryService.countPlayableByGenre()).thenReturn(genres);

            assertThat(controller.getGenres()).isSameAs(genres);
        }
    }
}
