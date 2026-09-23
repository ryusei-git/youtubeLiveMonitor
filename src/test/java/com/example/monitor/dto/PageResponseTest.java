package com.example.monitor.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PageResponse")
class PageResponseTest {

    @Nested
    @DisplayName("from()")
    class From {

        @Test
        @DisplayName("正常系：Pageの各項目が同じ名前・同じ意味で詰め替えられる")
        void testMethod01() {
            Page<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(0, 2), 5);

            PageResponse<String> result = PageResponse.from(page);

            assertThat(result.content()).containsExactly("a", "b");
            assertThat(result.number()).isZero();
            assertThat(result.size()).isEqualTo(2);
            assertThat(result.totalElements()).isEqualTo(5);
            assertThat(result.totalPages()).isEqualTo(3);
            assertThat(result.first()).isTrue();
            assertThat(result.last()).isFalse();
            assertThat(result.numberOfElements()).isEqualTo(2);
            assertThat(result.empty()).isFalse();
        }

        @Test
        @DisplayName("正常系：最終ページではlastがtrueになりnumberOfElementsがsizeより少なくなる")
        void testMethod02() {
            Page<String> page = new PageImpl<>(List.of("e"), PageRequest.of(2, 2), 5);

            PageResponse<String> result = PageResponse.from(page);

            assertThat(result.number()).isEqualTo(2);
            assertThat(result.first()).isFalse();
            assertThat(result.last()).isTrue();
            assertThat(result.numberOfElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系：空ページでも例外にならず件数が0になる")
        void testMethod03() {
            Page<String> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);

            PageResponse<String> result = PageResponse.from(page);

            assertThat(result.content()).isEmpty();
            assertThat(result.totalElements()).isZero();
            assertThat(result.totalPages()).isZero();
            assertThat(result.numberOfElements()).isZero();
            assertThat(result.empty()).isTrue();
        }
    }
}
