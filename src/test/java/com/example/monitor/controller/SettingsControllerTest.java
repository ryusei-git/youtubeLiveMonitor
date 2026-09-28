package com.example.monitor.controller;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.config.MonitorProperties.DiscordProperties;
import com.example.monitor.config.MonitorProperties.TwitchProperties;
import com.example.monitor.config.MonitorProperties.RecordingProperties;
import com.example.monitor.config.MonitorProperties.YouTubeProperties;
import com.example.monitor.dto.DirectoryPickResponse;
import com.example.monitor.dto.SettingsResponse;
import com.example.monitor.dto.SettingsUpdateRequest;
import com.example.monitor.service.AuditLogger;
import com.example.monitor.service.EnvironmentSettingsService;
import com.example.monitor.service.NativeDirectoryPickerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettingsController")
// ArgumentCaptor.forClass(Map.class) は総称型の情報を渡せず未検査キャストになる（StreamRecorderTest と同じ）
@SuppressWarnings("unchecked")
class SettingsControllerTest {

    @Mock
    private EnvironmentSettingsService environmentSettingsService;

    @Mock
    private NativeDirectoryPickerService nativeDirectoryPickerService;

    @Mock
    private AuditLogger auditLogger;

    private SettingsController newController(String apiKey, String webhookUrl, int maxHeight) {
        return newController(apiKey, webhookUrl, maxHeight, "", "");
    }

    /**
     * Twitch の設定有無まで指定してコントローラーを組み立てる。
     *
     * @param apiKey             YouTube API キー
     * @param webhookUrl         Discord Webhook URL
     * @param maxHeight          録画の画質上限
     * @param twitchClientId     Twitch の Client ID
     * @param twitchClientSecret Twitch の Client Secret
     * @return 組み立てたコントローラー
     */
    private SettingsController newController(String apiKey, String webhookUrl, int maxHeight,
                                             String twitchClientId, String twitchClientSecret) {
        MonitorProperties properties = new MonitorProperties(
                new YouTubeProperties(apiKey, 300),
                new TwitchProperties(twitchClientId, twitchClientSecret),
                new DiscordProperties(webhookUrl),
                new RecordingProperties("recordings", maxHeight),
                new MonitorProperties.AdminProperties("admin", ""));
        return new SettingsController(properties, environmentSettingsService, nativeDirectoryPickerService, auditLogger);
    }

    @Nested
    @DisplayName("getSettings()")
    class GetSettings {

        @Test
        @DisplayName("正常系：現在の設定値を返す")
        void testMethod01() {
            SettingsResponse response =
                    newController("dummy-key", "https://discord.com/api/webhooks/x/y", 1080).getSettings();

            assertThat(response.intervalSeconds()).isEqualTo(300);
            assertThat(response.recordingDirectory()).isEqualTo("recordings");
            assertThat(response.recordingMaxHeight()).isEqualTo(1080);
        }

        @Test
        @DisplayName("正常系：秘密情報は値そのものではなく設定有無だけを返す")
        void testMethod02() {
            SettingsResponse configured =
                    newController("dummy-key", "https://discord.com/api/webhooks/x/y", 0).getSettings();

            assertThat(configured.youTubeApiKeyConfigured()).isTrue();
            assertThat(configured.discordWebhookConfigured()).isTrue();
            // レスポンスに秘密情報そのものが含まれていないこと
            assertThat(configured.toString()).doesNotContain("dummy-key");
        }

        @Test
        @DisplayName("正常系：空文字の設定は未設定として扱う")
        void testMethod03() {
            SettingsResponse response = newController("", "", 0).getSettings();

            assertThat(response.youTubeApiKeyConfigured()).isFalse();
            assertThat(response.discordWebhookConfigured()).isFalse();
        }

        @Test
        @DisplayName("正常系：nullの設定も未設定として扱う")
        void testMethod04() {
            SettingsResponse response = newController(null, null, 0).getSettings();

            assertThat(response.youTubeApiKeyConfigured()).isFalse();
            assertThat(response.discordWebhookConfigured()).isFalse();
        }

        @Test
        @DisplayName("正常系：TwitchはClient IDとSecretが両方そろって初めて設定済みになる")
        void testMethod05() {
            // 片方だけでは認証できないため、まとめて1つの状態として扱う
            assertThat(newController("", "", 0, "id", "secret").getSettings().twitchConfigured()).isTrue();
            assertThat(newController("", "", 0, "id", "").getSettings().twitchConfigured()).isFalse();
            assertThat(newController("", "", 0, "", "secret").getSettings().twitchConfigured()).isFalse();
            assertThat(newController("", "", 0, "", "").getSettings().twitchConfigured()).isFalse();
        }

        @Test
        @DisplayName("正常系：Twitchの秘密情報も値そのものは返さない")
        void testMethod06() {
            SettingsResponse response = newController("", "", 0, "twitch-id", "twitch-secret").getSettings();

            assertThat(response.toString()).doesNotContain("twitch-secret").doesNotContain("twitch-id");
        }
    }

    @Nested
    @DisplayName("updateSettings()")
    class UpdateSettings {

        @Test
        @DisplayName("正常系：入力された項目だけを.envへの書き込み依頼に含める")
        void testMethod01() {
            SettingsController controller = newController("", "", 0);
            SettingsUpdateRequest request = new SettingsUpdateRequest(
                    "new-api-key", "https://discord.com/api/webhooks/123456789012345678/dummy-token",
                    "twitch-id", "twitch-secret", 60, "movies", 720);

            controller.updateSettings(request);

            ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
            verify(environmentSettingsService).updateEnvFile(captor.capture());
            assertThat(captor.getValue())
                    .containsEntry("YOUTUBE_API_KEY", "new-api-key")
                    .containsEntry("DISCORD_WEBHOOK_URL", "https://discord.com/api/webhooks/123456789012345678/dummy-token")
                    .containsEntry("TWITCH_CLIENT_ID", "twitch-id")
                    .containsEntry("TWITCH_CLIENT_SECRET", "twitch-secret")
                    .containsEntry("MONITOR_INTERVAL_SECONDS", "60")
                    .containsEntry("MONITOR_RECORDING_DIRECTORY", "movies")
                    .containsEntry("MONITOR_RECORDING_MAX_HEIGHT", "720");
        }

        @Test
        @DisplayName("正常系：null・空文字の項目は書き込み依頼に含めない（変更しない）")
        void testMethod02() {
            SettingsController controller = newController("", "", 0);
            SettingsUpdateRequest request = new SettingsUpdateRequest(null, "", null, null, null, "", null);

            controller.updateSettings(request);

            ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
            verify(environmentSettingsService).updateEnvFile(captor.capture());
            assertThat(captor.getValue()).isEmpty();
        }

        @Test
        @DisplayName("正常系：保存に成功したら204を返す")
        void testMethod03() {
            SettingsController controller = newController("", "", 0);
            SettingsUpdateRequest request = new SettingsUpdateRequest(null, null, null, null, null, null, null);

            ResponseEntity<Void> response = controller.updateSettings(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }
    }

    @Nested
    @DisplayName("pickDirectory()")
    class PickDirectory {

        @Test
        @DisplayName("正常系：選ばれたパスがある場合は200で返す")
        void testMethod01() {
            SettingsController controller = newController("", "", 0);
            when(nativeDirectoryPickerService.pickDirectory("/home/user/recordings"))
                    .thenReturn(Optional.of("/home/user/recordings/new"));

            ResponseEntity<DirectoryPickResponse> response = controller.pickDirectory("/home/user/recordings");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().path()).isEqualTo("/home/user/recordings/new");
        }

        @Test
        @DisplayName("正常系：キャンセルされた場合は204を返す")
        void testMethod02() {
            SettingsController controller = newController("", "", 0);
            when(nativeDirectoryPickerService.pickDirectory(null)).thenReturn(Optional.empty());

            ResponseEntity<DirectoryPickResponse> response = controller.pickDirectory(null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(response.getBody()).isNull();
        }
    }
}
