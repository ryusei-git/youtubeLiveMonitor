package com.example.monitor.service;

import com.example.monitor.dto.InvitationCheckResponse;
import com.example.monitor.dto.InvitationResponse;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.entity.Invitation;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.repository.InvitationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvitationService")
class InvitationServiceTest {

    /** 使用済みの招待を断るときの文言。 */
    private static final String ALREADY_USED_MESSAGE =
            "この招待リンクは既に使われています。登録済みのアカウントでログインしてください。";

    /** 期限切れの招待を断るときの文言。 */
    private static final String EXPIRED_MESSAGE = "この招待リンクは期限が切れています。管理者に再発行を依頼してください。";

    /** 利用者名が既に使われていて断るときの文言。 */
    private static final String USERNAME_TAKEN_MESSAGE = "この利用者名は既に使われています。別の名前にしてください。";

    @Mock
    private InvitationRepository invitationRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditLogger auditLogger;

    @InjectMocks
    private InvitationService service;

    private static Invitation invitation(long id, LocalDateTime expiresAt, LocalDateTime acceptedAt) {
        Invitation invitation = new Invitation();
        invitation.setId(id);
        invitation.setToken("token-" + id);
        invitation.setExpiresAt(expiresAt);
        invitation.setAcceptedAt(acceptedAt);
        return invitation;
    }

    /** 期限内の期限。 */
    private static LocalDateTime valid() {
        return LocalDateTime.now().plusDays(1);
    }

    /** 期限切れの期限。 */
    private static LocalDateTime expired() {
        return LocalDateTime.now().minusMinutes(1);
    }

    @Nested
    @DisplayName("register()")
    class Register {

        @Test
        @DisplayName("正常系：一般利用者（USER）を作り、利用者名の前後の空白を落とし、招待を条件付きの更新で使用済みにする")
        void testMethod01() {
            when(invitationRepository.findByToken("token-10")).thenReturn(Optional.of(invitation(10, valid(), null)));
            when(passwordEncoder.encode("password-123")).thenReturn("encoded");
            when(invitationRepository.markAccepted(eq(10L), eq("alice"), any(LocalDateTime.class))).thenReturn(1);

            service.register("token-10", "  alice  ", "password-123");

            ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
            verify(appUserRepository).saveAndFlush(captor.capture());
            AppUser saved = captor.getValue();
            assertThat(saved.getUsername()).isEqualTo("alice");
            assertThat(saved.getRole()).isEqualTo(AppUser.Role.USER);
            assertThat(saved.getPasswordHash()).isEqualTo("encoded");
            assertThat(saved.isEnabled()).isTrue();
            // 読んだ招待を save し直さない（#519 で消した「読んでから書く」が戻っていないこと）
            verify(invitationRepository, never()).save(any(Invitation.class));
            verify(auditLogger).record(eq(AuditAction.USER_CREATE), eq(AuditOutcome.SUCCESS), any(), any(), any(),
                    eq("USER"), eq("alice"), any());
        }

        @Test
        @DisplayName("異常系：tokenが見つからなければ断り、ハッシュ化も登録もしない")
        void testMethod02() {
            when(invitationRepository.findByToken("token-10")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.register("token-10", "alice", "password-123"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("この招待リンクは使用できません");
            verify(passwordEncoder, never()).encode(any());
            verify(appUserRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("異常系：使用済みの招待では登録できない")
        void testMethod03() {
            when(invitationRepository.findByToken("token-10"))
                    .thenReturn(Optional.of(invitation(10, valid(), LocalDateTime.now().minusDays(1))));

            assertThatThrownBy(() -> service.register("token-10", "alice", "password-123"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(ALREADY_USED_MESSAGE);
            verify(invitationRepository, never()).markAccepted(any(), any(), any());
            verify(appUserRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("異常系：期限切れの招待では登録できない")
        void testMethod04() {
            when(invitationRepository.findByToken("token-10")).thenReturn(Optional.of(invitation(10, expired(), null)));

            assertThatThrownBy(() -> service.register("token-10", "alice", "password-123"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(EXPIRED_MESSAGE);
        }

        @Test
        @DisplayName("異常系：利用者名が3文字未満・64文字を超えると断る")
        void testMethod05() {
            when(invitationRepository.findByToken("token-10")).thenReturn(Optional.of(invitation(10, valid(), null)));

            assertThatThrownBy(() -> service.register("token-10", "ab", "password-123"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("利用者名は3〜64文字にしてください");
            assertThatThrownBy(() -> service.register("token-10", "a".repeat(65), "password-123"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("利用者名は3〜64文字にしてください");
        }

        @Test
        @DisplayName("異常系：利用者名に改行・全角空白・ゼロ幅スペースを含むと断る")
        void testMethod06() {
            when(invitationRepository.findByToken("token-10")).thenReturn(Optional.of(invitation(10, valid(), null)));

            // 見えない文字（全角空白 U+3000・ゼロ幅スペース U+200B）をそのままソースに入れず、エスケープで書く
            for (String username : List.of("ali\nce", "ali\u3000ce", "ali\u200Bce")) {
                assertThatThrownBy(() -> service.register("token-10", username, "password-123"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("利用者名に空白・改行・見えない文字は使えません");
            }
        }

        @Test
        @DisplayName("異常系：パスワードが8文字未満なら要件の文言で断り、ハッシュ化しない")
        void testMethod07() {
            when(invitationRepository.findByToken("token-10")).thenReturn(Optional.of(invitation(10, valid(), null)));

            assertThatThrownBy(() -> service.register("token-10", "alice", "short"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("パスワードは8文字以上にしてください");
            verify(passwordEncoder, never()).encode(any());
        }

        @Test
        @DisplayName("異常系：パスワードがUTF-8で72バイトを超えると要件の文言で断り、ハッシュ化しない")
        void testMethod08() {
            when(invitationRepository.findByToken("token-10")).thenReturn(Optional.of(invitation(10, valid(), null)));

            // 全角 25 文字は UTF-8 で 75 バイト
            assertThatThrownBy(() -> service.register("token-10", "alice", "あ".repeat(25)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("パスワードが長すぎます。半角の英数字・記号なら72文字、全角の文字なら24文字までにしてください");
            verify(passwordEncoder, never()).encode(any());
        }

        @Test
        @DisplayName("異常系：同じ名前の利用者がいれば断り、招待を使用済みにしない")
        void testMethod09() {
            when(invitationRepository.findByToken("token-10")).thenReturn(Optional.of(invitation(10, valid(), null)));
            when(appUserRepository.findByUsername("alice"))
                    .thenReturn(Optional.of(new AppUser("alice", "hash", AppUser.Role.USER)));

            assertThatThrownBy(() -> service.register("token-10", "alice", "password-123"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(USERNAME_TAKEN_MESSAGE);
            // #519 の後はハッシュ化がこの確認より前に来るので、encode の呼び出しは確かめない
            verify(invitationRepository, never()).markAccepted(any(), any(), any());
            verify(appUserRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("異常系：確認の後に同じ招待が先に使われていたら（更新が0件）断り、利用者を作らない")
        void testMethod10() {
            when(invitationRepository.findByToken("token-10")).thenReturn(Optional.of(invitation(10, valid(), null)));
            when(invitationRepository.markAccepted(eq(10L), eq("alice"), any(LocalDateTime.class))).thenReturn(0);

            assertThatThrownBy(() -> service.register("token-10", "alice", "password-123"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(ALREADY_USED_MESSAGE);
            verify(appUserRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("異常系：確認の後に同じ名前が先に登録されていたら（一意制約違反）利用者名の文言で断る")
        void testMethod11() {
            when(invitationRepository.findByToken("token-10")).thenReturn(Optional.of(invitation(10, valid(), null)));
            when(invitationRepository.markAccepted(eq(10L), eq("alice"), any(LocalDateTime.class))).thenReturn(1);
            when(appUserRepository.saveAndFlush(any(AppUser.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate"));

            assertThatThrownBy(() -> service.register("token-10", "alice", "password-123"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(USERNAME_TAKEN_MESSAGE);
        }
    }

    @Nested
    @DisplayName("check()")
    class Check {

        @Test
        @DisplayName("正常系：未使用で期限内の招待は使える")
        void testMethod01() {
            when(invitationRepository.findByToken("token-10")).thenReturn(Optional.of(invitation(10, valid(), null)));

            InvitationCheckResponse result = service.check("token-10");

            assertThat(result.usable()).isTrue();
            assertThat(result.reason()).isNull();
        }

        @Test
        @DisplayName("異常系：tokenが見つからなければ使えず、URLの切れを疑う理由を返す")
        void testMethod02() {
            when(invitationRepository.findByToken("token-10")).thenReturn(Optional.empty());

            InvitationCheckResponse result = service.check("token-10");

            assertThat(result.usable()).isFalse();
            assertThat(result.reason())
                    .isEqualTo("この招待リンクは見つかりませんでした。URLが途中で切れていないか確認してください。");
        }

        @Test
        @DisplayName("異常系：使用済みの招待は使えない")
        void testMethod03() {
            when(invitationRepository.findByToken("token-10"))
                    .thenReturn(Optional.of(invitation(10, valid(), LocalDateTime.now().minusDays(1))));

            InvitationCheckResponse result = service.check("token-10");

            assertThat(result.usable()).isFalse();
            assertThat(result.reason()).isEqualTo(ALREADY_USED_MESSAGE);
        }

        @Test
        @DisplayName("異常系：期限切れの招待は使えない")
        void testMethod04() {
            when(invitationRepository.findByToken("token-10")).thenReturn(Optional.of(invitation(10, expired(), null)));

            InvitationCheckResponse result = service.check("token-10");

            assertThat(result.usable()).isFalse();
            assertThat(result.reason()).isEqualTo(EXPIRED_MESSAGE);
        }
    }

    @Nested
    @DisplayName("issue()")
    class Issue {

        @Test
        @DisplayName("正常系：有効日数は省略・0以下なら7日、90日を超えたら90日に丸める")
        void testMethod01() {
            when(invitationRepository.save(any(Invitation.class))).thenAnswer(call -> call.getArgument(0));
            LocalDateTime now = LocalDateTime.now();

            service.issue(null, null);
            service.issue(null, 0);
            service.issue(null, 200);
            service.issue(null, 30);

            ArgumentCaptor<Invitation> captor = ArgumentCaptor.forClass(Invitation.class);
            verify(invitationRepository, times(4)).save(captor.capture());
            List<Invitation> saved = captor.getAllValues();
            assertThat(saved.get(0).getExpiresAt()).isCloseTo(now.plusDays(7), within(1, ChronoUnit.MINUTES));
            assertThat(saved.get(1).getExpiresAt()).isCloseTo(now.plusDays(7), within(1, ChronoUnit.MINUTES));
            assertThat(saved.get(2).getExpiresAt()).isCloseTo(now.plusDays(90), within(1, ChronoUnit.MINUTES));
            assertThat(saved.get(3).getExpiresAt()).isCloseTo(now.plusDays(30), within(1, ChronoUnit.MINUTES));
        }

        @Test
        @DisplayName("正常系：覚え書きの前後の空白を落とし、空白だけならnullにする。tokenは毎回作る")
        void testMethod02() {
            when(invitationRepository.save(any(Invitation.class))).thenAnswer(call -> call.getArgument(0));

            InvitationResponse labeled = service.issue("  友達A  ", null);
            service.issue("   ", null);

            ArgumentCaptor<Invitation> captor = ArgumentCaptor.forClass(Invitation.class);
            verify(invitationRepository, times(2)).save(captor.capture());
            Invitation first = captor.getAllValues().get(0);
            assertThat(first.getLabel()).isEqualTo("友達A");
            assertThat(first.getToken()).isNotEmpty();
            assertThat(labeled.token()).isEqualTo(first.getToken());
            assertThat(labeled.usable()).isTrue();
            assertThat(captor.getAllValues().get(1).getLabel()).isNull();
            assertThat(captor.getAllValues().get(1).getToken()).isNotEqualTo(first.getToken());
        }
    }

    @Nested
    @DisplayName("revoke()")
    class Revoke {

        @Test
        @DisplayName("正常系：招待があれば消してtrueを返す")
        void testMethod01() {
            when(invitationRepository.existsById(5L)).thenReturn(true);

            assertThat(service.revoke(5L)).isTrue();
            verify(invitationRepository).deleteById(5L);
        }

        @Test
        @DisplayName("異常系：招待が無ければfalseを返し、削除を試みない")
        void testMethod02() {
            when(invitationRepository.existsById(5L)).thenReturn(false);

            assertThat(service.revoke(5L)).isFalse();
            verify(invitationRepository, never()).deleteById(any());
        }
    }
}
