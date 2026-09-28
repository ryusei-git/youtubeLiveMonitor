package com.example.monitor.service;

import com.example.monitor.dto.InvitationCheckResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.exception.TooManyPasswordAttemptsException;
import com.example.monitor.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordChangeService")
class PasswordChangeServiceTest {

    /** 操作元の IP。 */
    private static final String IP = "203.0.113.5";

    /** 今のパスワードが違うときの文言。 */
    private static final String WRONG_CURRENT_PASSWORD = "今のパスワードが違います";

    /** 新しいパスワードが短いときの文言（PasswordPolicy）。 */
    private static final String TOO_SHORT = "パスワードは8文字以上にしてください";

    /** 新しいパスワードが 72 バイトを超えるときの文言（PasswordPolicy）。 */
    private static final String TOO_LONG =
            "パスワードが長すぎます。半角の英数字・記号なら72文字、全角の文字なら24文字までにしてください";

    /** 再設定用のリンクが見つからないときの文言。 */
    private static final String RESET_TOKEN_NOT_FOUND =
            "このリンクは使用済みか、新しいリンクが発行されたため使えません。管理者に再発行を依頼してください。";

    /** 再設定用のリンクの期限が切れているときの文言。 */
    private static final String RESET_TOKEN_EXPIRED = "このリンクは期限が切れています。管理者に再発行を依頼してください。";

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditLogger auditLogger;

    /** テストごとに新しいインスタンスになるので、今のパスワードの照合の試行枠（#534）も空から始まる。 */
    @InjectMocks
    private PasswordChangeService service;

    private static AppUser user() {
        AppUser user = new AppUser("carol", "old-hash", AppUser.Role.USER);
        user.setId(1L);
        return user;
    }

    private static AppUser resetUser(LocalDateTime expiresAt) {
        AppUser user = user();
        user.setPasswordResetToken("reset-token");
        user.setPasswordResetExpiresAt(expiresAt);
        return user;
    }

    @Nested
    @DisplayName("changePassword()")
    class ChangePassword {

        @Test
        @DisplayName("正常系：変更時刻をミリ秒に切り詰めて書き、新しいハッシュと時刻を入れた利用者を返し、成功を記録する")
        void testMethod01() {
            when(appUserRepository.findById(1L)).thenReturn(Optional.of(user()));
            when(passwordEncoder.matches("old-password-1", "old-hash")).thenReturn(true);
            when(passwordEncoder.encode("new-password-1")).thenReturn("new-hash");
            when(appUserRepository.updatePassword(eq(1L), eq("new-hash"), any(LocalDateTime.class))).thenReturn(1);

            AppUser result = service.changePassword(1L, "old-password-1", "new-password-1", IP);

            ArgumentCaptor<LocalDateTime> changedAt = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(appUserRepository).updatePassword(eq(1L), eq("new-hash"), changedAt.capture());
            // ナノ秒のままだと H2 が 6 桁に丸めて DB の値が後になり、変更した本人のセッションまで失効する（実際に発生した）
            assertThat(changedAt.getValue().getNano() % 1_000_000).isZero();
            assertThat(result.getPasswordHash()).isEqualTo("new-hash");
            assertThat(result.getPasswordChangedAt()).isEqualTo(changedAt.getValue());
            verify(auditLogger).record(AuditAction.PASSWORD_CHANGE, AuditOutcome.SUCCESS, 1L, "carol", IP,
                    "USER", "carol", null);
        }

        @Test
        @DisplayName("異常系：利用者が見つからなければIllegalStateExceptionで、記録しない")
        void testMethod02() {
            when(appUserRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.changePassword(1L, "old-password-1", "new-password-1", IP))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("ログイン中の利用者が見つかりません: id=1");
            verifyNoInteractions(auditLogger);
        }

        @Test
        @DisplayName("異常系：今のパスワードが違えば断り、失敗を記録し、ハッシュ化も更新もしない")
        void testMethod03() {
            when(appUserRepository.findById(1L)).thenReturn(Optional.of(user()));
            when(passwordEncoder.matches("wrong-password", "old-hash")).thenReturn(false);

            assertThatThrownBy(() -> service.changePassword(1L, "wrong-password", "new-password-1", IP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(WRONG_CURRENT_PASSWORD);
            verify(auditLogger).record(AuditAction.PASSWORD_CHANGE, AuditOutcome.FAILURE, 1L, "carol", IP,
                    "USER", "carol", WRONG_CURRENT_PASSWORD);
            verify(passwordEncoder, never()).encode(any());
            verify(appUserRepository, never()).updatePassword(any(), any(), any());
        }

        @Test
        @DisplayName("異常系：今のパスワードがnullなら照合せずに同じ文言で断る")
        void testMethod04() {
            when(appUserRepository.findById(1L)).thenReturn(Optional.of(user()));

            assertThatThrownBy(() -> service.changePassword(1L, null, "new-password-1", IP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(WRONG_CURRENT_PASSWORD);
            verify(passwordEncoder, never()).matches(any(), any());
        }

        @Test
        @DisplayName("異常系：新しいパスワードが8文字未満なら要件の文言で断り、失敗を記録する")
        void testMethod05() {
            when(appUserRepository.findById(1L)).thenReturn(Optional.of(user()));
            when(passwordEncoder.matches("old-password-1", "old-hash")).thenReturn(true);

            assertThatThrownBy(() -> service.changePassword(1L, "old-password-1", "short", IP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(TOO_SHORT);
            verify(auditLogger).record(AuditAction.PASSWORD_CHANGE, AuditOutcome.FAILURE, 1L, "carol", IP,
                    "USER", "carol", TOO_SHORT);
            verify(appUserRepository, never()).updatePassword(any(), any(), any());
        }

        @Test
        @DisplayName("異常系：新しいパスワードが72バイトを超えると要件の文言で断り、ハッシュ化しない")
        void testMethod06() {
            when(appUserRepository.findById(1L)).thenReturn(Optional.of(user()));
            when(passwordEncoder.matches("old-password-1", "old-hash")).thenReturn(true);

            assertThatThrownBy(() -> service.changePassword(1L, "old-password-1", "a".repeat(73), IP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(TOO_LONG);
            verify(passwordEncoder, never()).encode(any());
        }

        @Test
        @DisplayName("異常系：今のパスワードを5回続けて間違えると、6回目は照合せずに一時制限の例外になる")
        void testMethod07() {
            when(appUserRepository.findById(1L)).thenReturn(Optional.of(user()));
            when(passwordEncoder.matches("wrong-password", "old-hash")).thenReturn(false);

            for (int attempt = 0; attempt < 5; attempt++) {
                assertThatThrownBy(() -> service.changePassword(1L, "wrong-password", "new-password-1", IP))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(WRONG_CURRENT_PASSWORD);
            }
            Throwable thrown = catchThrowable(() -> service.changePassword(1L, "wrong-password", "new-password-1", IP));

            assertThat(thrown).isInstanceOf(TooManyPasswordAttemptsException.class);
            assertThat(thrown.getMessage())
                    .isEqualTo("パスワード変更の試行が上限に達しました。時間をおいて、もう一度お試しください。");
            assertThat(((TooManyPasswordAttemptsException) thrown).getRetryAfterSeconds()).isBetween(1L, 900L);
            // 6 回目は BCrypt を走らせない
            verify(passwordEncoder, times(5)).matches(any(), any());
            // 制限中の拒否は記録しない
            verify(auditLogger, times(5)).record(eq(AuditAction.PASSWORD_CHANGE), eq(AuditOutcome.FAILURE),
                    any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("正常系：照合が合えば連続の失敗が0に戻る")
        void testMethod08() {
            when(appUserRepository.findById(1L)).thenReturn(Optional.of(user()));
            // 成功すると同じ AppUser のハッシュが new-hash に変わるので、2 つ目の引数は anyString() にする
            when(passwordEncoder.matches(eq("wrong-password"), anyString())).thenReturn(false);
            when(passwordEncoder.matches(eq("old-password-1"), anyString())).thenReturn(true);
            when(passwordEncoder.encode("new-password-1")).thenReturn("new-hash");
            when(appUserRepository.updatePassword(eq(1L), eq("new-hash"), any(LocalDateTime.class))).thenReturn(1);

            for (int attempt = 0; attempt < 4; attempt++) {
                assertThatThrownBy(() -> service.changePassword(1L, "wrong-password", "new-password-1", IP))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(WRONG_CURRENT_PASSWORD);
            }
            service.changePassword(1L, "old-password-1", "new-password-1", IP);
            for (int attempt = 0; attempt < 4; attempt++) {
                assertThatThrownBy(() -> service.changePassword(1L, "wrong-password", "new-password-1", IP))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(WRONG_CURRENT_PASSWORD);
            }
        }
    }

    @Nested
    @DisplayName("resetPassword()")
    class ResetPassword {

        @Test
        @DisplayName("正常系：リンクが使えれば、ミリ秒に切り詰めた変更時刻で1回限りの更新をし、成功を記録する")
        void testMethod01() {
            when(appUserRepository.findByPasswordResetToken("reset-token"))
                    .thenReturn(Optional.of(resetUser(LocalDateTime.now().plusHours(1))));
            when(passwordEncoder.encode("new-password-1")).thenReturn("new-hash");
            when(appUserRepository.resetPasswordByToken(eq("reset-token"), eq("new-hash"),
                    any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);

            service.resetPassword("reset-token", "new-password-1", IP);

            ArgumentCaptor<LocalDateTime> changedAt = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(appUserRepository).resetPasswordByToken(eq("reset-token"), eq("new-hash"),
                    changedAt.capture(), any(LocalDateTime.class));
            assertThat(changedAt.getValue().getNano() % 1_000_000).isZero();
            verify(auditLogger).record(AuditAction.PASSWORD_RESET, AuditOutcome.SUCCESS, 1L, "carol", IP,
                    "USER", "carol", null);
        }

        @Test
        @DisplayName("異常系：tokenがnullなら引かずに断り、監査ログに書かない")
        void testMethod02() {
            assertThatThrownBy(() -> service.resetPassword(null, "new-password-1", IP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(RESET_TOKEN_NOT_FOUND);
            verify(appUserRepository, never()).findByPasswordResetToken(any());
            verifyNoInteractions(auditLogger);
        }

        @Test
        @DisplayName("異常系：該当する利用者がいないtokenなら断り、監査ログに書かない")
        void testMethod03() {
            when(appUserRepository.findByPasswordResetToken("no-such-token")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resetPassword("no-such-token", "new-password-1", IP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(RESET_TOKEN_NOT_FOUND);
            verifyNoInteractions(auditLogger);
        }

        @Test
        @DisplayName("異常系：期限切れのリンクなら断り、利用者を付けて失敗を記録する")
        void testMethod04() {
            when(appUserRepository.findByPasswordResetToken("reset-token"))
                    .thenReturn(Optional.of(resetUser(LocalDateTime.now().minusMinutes(1))));

            assertThatThrownBy(() -> service.resetPassword("reset-token", "new-password-1", IP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(RESET_TOKEN_EXPIRED);
            verify(auditLogger).record(AuditAction.PASSWORD_RESET, AuditOutcome.FAILURE, 1L, "carol", IP,
                    "USER", "carol", RESET_TOKEN_EXPIRED);
            verify(appUserRepository, never()).resetPasswordByToken(any(), any(), any(), any());
        }

        @Test
        @DisplayName("異常系：新しいパスワードが8文字未満なら断り、失敗を記録する")
        void testMethod05() {
            when(appUserRepository.findByPasswordResetToken("reset-token"))
                    .thenReturn(Optional.of(resetUser(LocalDateTime.now().plusHours(1))));

            assertThatThrownBy(() -> service.resetPassword("reset-token", "short", IP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(TOO_SHORT);
            verify(auditLogger).record(AuditAction.PASSWORD_RESET, AuditOutcome.FAILURE, 1L, "carol", IP,
                    "USER", "carol", TOO_SHORT);
            verify(appUserRepository, never()).resetPasswordByToken(any(), any(), any(), any());
        }

        @Test
        @DisplayName("異常系：新しいパスワードが72バイトを超えると断り、ハッシュ化しない")
        void testMethod06() {
            when(appUserRepository.findByPasswordResetToken("reset-token"))
                    .thenReturn(Optional.of(resetUser(LocalDateTime.now().plusHours(1))));

            assertThatThrownBy(() -> service.resetPassword("reset-token", "a".repeat(73), IP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(TOO_LONG);
            verify(passwordEncoder, never()).encode(any());
        }

        @Test
        @DisplayName("異常系：読んでから書くまでに同じリンクが使われていたら（更新が0件）断り、失敗を記録する")
        void testMethod07() {
            when(appUserRepository.findByPasswordResetToken("reset-token"))
                    .thenReturn(Optional.of(resetUser(LocalDateTime.now().plusHours(1))));
            // encode もスタブする。しないと null のハッシュで呼ばれ、厳密スタブが引数の食い違いとして落とす
            when(passwordEncoder.encode("new-password-1")).thenReturn("new-hash");
            when(appUserRepository.resetPasswordByToken(eq("reset-token"), eq("new-hash"),
                    any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(0);

            assertThatThrownBy(() -> service.resetPassword("reset-token", "new-password-1", IP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(RESET_TOKEN_NOT_FOUND);
            verify(auditLogger).record(eq(AuditAction.PASSWORD_RESET), eq(AuditOutcome.FAILURE), eq(1L), eq("carol"),
                    eq(IP), eq("USER"), eq("carol"), any());
        }
    }

    @Nested
    @DisplayName("checkResetToken()")
    class CheckResetToken {

        @Test
        @DisplayName("正常系：期限内のリンクは使える")
        void testMethod01() {
            when(appUserRepository.findByPasswordResetToken("reset-token"))
                    .thenReturn(Optional.of(resetUser(LocalDateTime.now().plusHours(1))));

            InvitationCheckResponse result = service.checkResetToken("reset-token");

            assertThat(result.usable()).isTrue();
            assertThat(result.reason()).isNull();
        }

        @Test
        @DisplayName("異常系：該当する利用者がいないtokenは使えない")
        void testMethod02() {
            when(appUserRepository.findByPasswordResetToken("no-such-token")).thenReturn(Optional.empty());

            InvitationCheckResponse result = service.checkResetToken("no-such-token");

            assertThat(result.usable()).isFalse();
            assertThat(result.reason()).isEqualTo(RESET_TOKEN_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系：期限切れのリンクは使えない")
        void testMethod03() {
            when(appUserRepository.findByPasswordResetToken("reset-token"))
                    .thenReturn(Optional.of(resetUser(LocalDateTime.now().minusMinutes(1))));

            InvitationCheckResponse result = service.checkResetToken("reset-token");

            assertThat(result.usable()).isFalse();
            assertThat(result.reason()).isEqualTo(RESET_TOKEN_EXPIRED);
        }

        @Test
        @DisplayName("異常系：tokenがnullなら引かずに使えないと返す")
        void testMethod04() {
            InvitationCheckResponse result = service.checkResetToken(null);

            assertThat(result.usable()).isFalse();
            verify(appUserRepository, never()).findByPasswordResetToken(any());
        }
    }
}
