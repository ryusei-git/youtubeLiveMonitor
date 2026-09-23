package com.example.monitor.controller;

import com.example.monitor.exception.ChannelAlreadyRegisteredException;
import com.example.monitor.exception.ChannelNotFoundException;
import com.example.monitor.exception.MonitoringInProgressException;
import com.example.monitor.exception.RecordingInProgressException;
import com.example.monitor.exception.RecordingNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Nested
    @DisplayName("handleChannelAlreadyRegistered()")
    class HandleChannelAlreadyRegistered {

        @Test
        @DisplayName("正常系：409 Conflictとメッセージを含むレスポンスを返す")
        void testMethod01() {
            ResponseEntity<Map<String, String>> response =
                    handler.handleChannelAlreadyRegistered(new ChannelAlreadyRegisteredException("UCxxxxxxxx"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).containsEntry("error", "既に登録されています: UCxxxxxxxx");
        }
    }

    @Nested
    @DisplayName("handleMonitoringInProgress()")
    class HandleMonitoringInProgress {

        @Test
        @DisplayName("正常系：409 Conflictとメッセージを含むレスポンスを返す")
        void testMethod01() {
            ResponseEntity<Map<String, String>> response =
                    handler.handleMonitoringInProgress(new MonitoringInProgressException());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).containsEntry("error", "監視サイクルが既に実行中です。完了までお待ちください");
        }
    }

    @Nested
    @DisplayName("handleChannelNotFound()")
    class HandleChannelNotFound {

        @Test
        @DisplayName("正常系：404 Not Foundとメッセージを含むレスポンスを返す")
        void testMethod01() {
            ResponseEntity<Map<String, String>> response =
                    handler.handleChannelNotFound(new ChannelNotFoundException(1L));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).containsEntry("error", "チャンネルが見つかりません: id=1");
        }
    }

    @Nested
    @DisplayName("handleRecordingNotFound()")
    class HandleRecordingNotFound {

        @Test
        @DisplayName("正常系：404 Not Foundとメッセージを含むレスポンスを返す")
        void testMethod01() {
            ResponseEntity<Map<String, String>> response =
                    handler.handleRecordingNotFound(new RecordingNotFoundException(1L));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).containsEntry("error", "録画履歴が見つかりません: id=1");
        }
    }

    @Nested
    @DisplayName("handleRecordingInProgress()")
    class HandleRecordingInProgress {

        @Test
        @DisplayName("正常系：409 Conflictとメッセージを含むレスポンスを返す")
        void testMethod01() {
            ResponseEntity<Map<String, String>> response =
                    handler.handleRecordingInProgress(new RecordingInProgressException(1L));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).containsEntry("error", "録画中のため削除できません: id=1");
        }
    }

    @Nested
    @DisplayName("handleIllegalArgument()")
    class HandleIllegalArgument {

        @Test
        @DisplayName("正常系：400 Bad Requestとメッセージを含むレスポンスを返す")
        void testMethod01() {
            ResponseEntity<Map<String, String>> response =
                    handler.handleIllegalArgument(new IllegalArgumentException("不正な入力です"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "不正な入力です");
        }
    }

    @Nested
    @DisplayName("handleIllegalState()")
    class HandleIllegalState {

        @Test
        @DisplayName("正常系：500 Internal Server Errorとメッセージを含むレスポンスを返す")
        void testMethod01() {
            ResponseEntity<Map<String, String>> response =
                    handler.handleIllegalState(new IllegalStateException("内部エラーです"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).containsEntry("error", "内部エラーです");
        }
    }

    @Nested
    @DisplayName("handleNoResourceFound()")
    class HandleNoResourceFound {

        @Test
        @DisplayName("正常系：存在しないパスは404を返す（500にしない）")
        void testMethod01() {
            // 汎用の Exception ハンドラを足した際、これが 500 になり
            // 存在しない URL を叩かれるたびに ERROR ログが出ていた（実際に発生した）
            ResponseEntity<Map<String, String>> response = handler.handleNoResourceFound(
                    new NoResourceFoundException(HttpMethod.GET, "/nonexistent-page.html"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("handleUnexpected()")
    class HandleUnexpected {

        @Test
        @DisplayName("正常系：想定外の例外は500を返す")
        void testMethod01() {
            ResponseEntity<Map<String, String>> response =
                    handler.handleUnexpected(new RuntimeException("想定外"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).containsEntry("error", "想定外");
        }

        @Test
        @DisplayName("異常系：メッセージを持たない例外でもハンドラ自身が落ちない")
        void testMethod02() {
            // Map.of は値に null を許さないため、代替文言が無いとエラー応答の組み立てで落ちる
            ResponseEntity<Map<String, String>> response =
                    handler.handleUnexpected(new RuntimeException());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).extractingByKey("error").isNotNull();
        }
    }
}
