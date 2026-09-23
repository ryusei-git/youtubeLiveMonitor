package com.example.monitor.playground;

import com.google.api.client.util.GenericData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ApiPlaygroundService")
class ApiPlaygroundServiceTest {

    /** テスト用のハンドラ。指定した定義と、実行時の振る舞いを差し替えられるようにする。 */
    private static class StubHandler implements PlaygroundApiHandler {
        private final ApiDefinition definition;
        private final RuntimeException failure;

        StubHandler(String id, int quotaCost, List<ApiParameter> parameters, RuntimeException failure) {
            this.definition = new ApiDefinition(id, id, "説明", quotaCost, parameters);
            this.failure = failure;
        }

        @Override
        public ApiDefinition definition() {
            return definition;
        }

        @Override
        public Object execute(Map<String, String> parameters) throws IOException {
            if (failure != null) {
                throw failure;
            }
            GenericData response = new GenericData();
            response.put("ok", true);
            return response;
        }
    }

    private static StubHandler handler(String id, int quotaCost) {
        return new StubHandler(id, quotaCost, List.of(), null);
    }

    @Nested
    @DisplayName("listApis()")
    class ListApis {

        @Test
        @DisplayName("正常系：クォータの安い順に並べる")
        void testMethod01() {
            ApiPlaygroundService service = new ApiPlaygroundService(
                    List.of(handler("search.list", 100), handler("videos.list", 1)));

            assertThat(service.listApis()).extracting(ApiDefinition::id)
                    .containsExactly("videos.list", "search.list");
        }

        @Test
        @DisplayName("正常系：ハンドラが1つも無くても空の一覧を返す")
        void testMethod02() {
            ApiPlaygroundService service = new ApiPlaygroundService(List.of());

            assertThat(service.listApis()).isEmpty();
        }
    }

    @Nested
    @DisplayName("execute()")
    class Execute {

        @Test
        @DisplayName("正常系：応答をJSON文字列にして返す")
        void testMethod01() {
            ApiPlaygroundService service = new ApiPlaygroundService(List.of(handler("videos.list", 1)));

            ApiExecutionResult result =
                    service.execute(new ApiExecutionRequest("videos.list", Map.of()));

            assertThat(result.success()).isTrue();
            assertThat(result.responseJson()).contains("\"ok\"");
            assertThat(result.errorMessage()).isNull();
        }

        @Test
        @DisplayName("正常系：消費クォータを累計する")
        void testMethod02() {
            ApiPlaygroundService service = new ApiPlaygroundService(
                    List.of(handler("videos.list", 1), handler("search.list", 100)));

            service.execute(new ApiExecutionRequest("videos.list", Map.of()));
            ApiExecutionResult second =
                    service.execute(new ApiExecutionRequest("search.list", Map.of()));

            assertThat(second.quotaCost()).isEqualTo(100);
            assertThat(second.sessionQuotaTotal()).isEqualTo(101);
            assertThat(service.sessionQuotaTotal()).isEqualTo(101);
        }

        @Test
        @DisplayName("正常系：呼び出しが失敗してもクォータは消費したものとして数える")
        void testMethod03() {
            // リクエストが Google に届いた時点で消費されるため、成否で数え方を変えない
            ApiPlaygroundService service = new ApiPlaygroundService(List.of(
                    new StubHandler("videos.list", 1, List.of(), new RuntimeException("失敗"))));

            ApiExecutionResult result =
                    service.execute(new ApiExecutionRequest("videos.list", Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.sessionQuotaTotal()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系：失敗はHTTPエラーにせず結果の本文で返す")
        void testMethod04() {
            ApiPlaygroundService service = new ApiPlaygroundService(List.of(
                    new StubHandler("videos.list", 1, List.of(), new RuntimeException("part が不正です"))));

            ApiExecutionResult result =
                    service.execute(new ApiExecutionRequest("videos.list", Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("part が不正です");
            assertThat(result.responseJson()).isNull();
        }

        @Test
        @DisplayName("異常系：エラーメッセージに含まれるAPIキーを伏せ字にする")
        void testMethod05() {
            // YouTube はエラー本文にリクエストURLをそのまま含めるため、APIキーが漏れる（実際に発生した）
            String leaked = "400 Bad Request\nGET https://youtube.googleapis.com/youtube/v3/videos"
                    + "?id=abc&part=bogus&key=SECRET_API_KEY_VALUE";
            ApiPlaygroundService service = new ApiPlaygroundService(List.of(
                    new StubHandler("videos.list", 1, List.of(), new RuntimeException(leaked))));

            ApiExecutionResult result =
                    service.execute(new ApiExecutionRequest("videos.list", Map.of()));

            assertThat(result.errorMessage()).doesNotContain("SECRET_API_KEY_VALUE");
            assertThat(result.errorMessage()).contains("key=***");
            // 伏せるのはキーだけで、原因を読み取るのに必要な情報は残す
            assertThat(result.errorMessage()).contains("400 Bad Request");
            assertThat(result.errorMessage()).contains("part=bogus");
        }

        @Test
        @DisplayName("異常系：存在しないAPIを指定すると例外を投げる")
        void testMethod06() {
            ApiPlaygroundService service = new ApiPlaygroundService(List.of(handler("videos.list", 1)));

            assertThatThrownBy(() -> service.execute(new ApiExecutionRequest("nope.list", Map.of())))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("異常系：必須項目が空ならクォータを消費せずに弾く")
        void testMethod07() {
            ApiPlaygroundService service = new ApiPlaygroundService(List.of(new StubHandler(
                    "videos.list", 1, List.of(ApiParameter.required("id", "動画ID", "例")), null)));

            assertThatThrownBy(() -> service.execute(
                    new ApiExecutionRequest("videos.list", Map.of("id", "  "))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("動画ID");
            // 呼び出す前に弾くので消費は 0 のまま
            assertThat(service.sessionQuotaTotal()).isZero();
        }

        @Test
        @DisplayName("正常系：パラメータがnullでも必須項目が無ければ実行できる")
        void testMethod08() {
            ApiPlaygroundService service = new ApiPlaygroundService(List.of(handler("videos.list", 1)));

            ApiExecutionResult result =
                    service.execute(new ApiExecutionRequest("videos.list", null));

            assertThat(result.success()).isTrue();
        }
    }
}
