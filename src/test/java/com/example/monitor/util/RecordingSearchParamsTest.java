package com.example.monitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.data.domain.Sort.Order.asc;
import static org.springframework.data.domain.Sort.Order.desc;

@DisplayName("RecordingSearchParams")
class RecordingSearchParamsTest {

    @Nested
    @DisplayName("toSort()")
    class ToSort {

        @Test
        @DisplayName("正常系：newest は開始時刻の新しい順、同時刻なら主キーの大きい順")
        void testMethod01() {
            // 主キーまで決めておかないと、ページをまたいだときに同じ録画が 2 回出たり抜けたりする
            assertThat(RecordingSearchParams.toSort("newest"))
                    .containsExactly(desc("startedAt"), desc("id"));
        }

        @Test
        @DisplayName("正常系：oldest は開始時刻の古い順、同時刻なら主キーの小さい順")
        void testMethod02() {
            assertThat(RecordingSearchParams.toSort("oldest"))
                    .containsExactly(asc("startedAt"), asc("id"));
        }

        @Test
        @DisplayName("正常系：longest は長さの降順、同じ長さなら newest の順")
        void testMethod03() {
            assertThat(RecordingSearchParams.toSort("longest"))
                    .containsExactly(desc("durationSeconds"), desc("startedAt"), desc("id"));
        }

        @Test
        @DisplayName("正常系：largest はサイズの降順、同じサイズなら newest の順")
        void testMethod04() {
            assertThat(RecordingSearchParams.toSort("largest"))
                    .containsExactly(desc("fileSizeBytes"), desc("startedAt"), desc("id"));
        }

        @Test
        @DisplayName("異常系：知らない名前は既定の並びに読み替えず IllegalArgumentException（400）にする")
        void testMethod05() {
            // 黙って newest に寄せると、URL に残った条件と画面の並びが食い違う
            assertThatThrownBy(() -> RecordingSearchParams.toSort("bogus"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("toWatchedFilter()")
    class ToWatchedFilter {

        @Test
        @DisplayName("正常系：指定なし（null）は視聴状態で絞らない")
        void testMethod01() {
            assertThat(RecordingSearchParams.toWatchedFilter(null)).isNull();
        }

        @Test
        @DisplayName("正常系：watched は視聴済みだけに絞る（true）")
        void testMethod02() {
            assertThat(RecordingSearchParams.toWatchedFilter("watched")).isTrue();
        }

        @Test
        @DisplayName("正常系：unwatched は未視聴だけに絞る（false）")
        void testMethod03() {
            assertThat(RecordingSearchParams.toWatchedFilter("unwatched")).isFalse();
        }

        @Test
        @DisplayName("異常系：知らない値は絞らない扱いにせず IllegalArgumentException（400）にする")
        void testMethod04() {
            assertThatThrownBy(() -> RecordingSearchParams.toWatchedFilter("bogus"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
