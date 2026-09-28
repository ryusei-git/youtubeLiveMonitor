package com.example.monitor.cli;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.service.AuditLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code set-password} の引数の受け取り方と、パスワードを決め直す処理を確かめる。
 *
 * <p>パスワードを引数に書かせない決まり（シェルの履歴と {@code ps} に平文で残るため）は、picocli の
 * 解釈で守っている。{@code -p} の {@code arity = "0"} を消すと、{@code --password=値} と {@code -p=値} を
 * 受け取り、入力を求めずにその値で変えてしまう（#582 のレビューで見つけた）。そのため
 * {@link PasswordOption} では、{@link CommandLine#execute} で実際に解釈させて確かめる。
 *
 * <p>Gradle のテストは端末につながらないので {@code System.console()} が {@code null} になり、picocli は
 * 入力を標準入力から 1 行読む。{@code System.setIn} で置いた行が、実行後に打ったパスワードの代わりになる。
 * {@code null} になるのは、今テストを動かす JDK 21（{@code build.gradle} の toolchain）の動き。JDK 22 からは
 * 端末につながっていなくても {@code System.console()} が {@code null} を返さないことがあり、そのとき picocli は
 * {@code System.console()} から読むので、{@code System.setIn} の行は読まれない。JDK を上げて
 * {@link PasswordOption} が止まる・落ちるようになったら、まずこれを疑うこと。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SetPasswordCommand")
class SetPasswordCommandTest {

    /** 監査ログの詳細。画面からの変更と見分けるために CLI が書く文字列。 */
    private static final String AUDIT_DETAIL = "CLI（set-password）";

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private AuditLogger auditLogger;

    @InjectMocks
    private SetPasswordCommand command;

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;
    private InputStream originalIn;

    @BeforeEach
    void redirectStreams() {
        originalOut = System.out;
        originalErr = System.err;
        originalIn = System.in;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        System.setIn(originalIn);
    }

    /** 主キー 1 の管理者を作る。 */
    private static AppUser admin() {
        AppUser user = new AppUser("admin", "old-hash", AppUser.Role.ADMIN);
        user.setId(1L);
        return user;
    }

    @Nested
    @DisplayName("-p・--password の受け取り方（picocli に解釈させる）")
    class PasswordOption {

        /** picocli の誤りの表示を受け取る。 */
        private final StringWriter picocliErr = new StringWriter();

        /** 標準入力に {@code typed} の 1 行を置いてから、picocli に引数を解釈させて実行する。 */
        private int execute(String typed, String... args) {
            System.setIn(new ByteArrayInputStream((typed + "\n").getBytes(StandardCharsets.UTF_8)));
            CommandLine commandLine = new CommandLine(command);
            commandLine.setErr(new PrintWriter(picocliErr, true));
            return commandLine.execute(args);
        }

        @Test
        @DisplayName("異常系：--password=値 は引数の誤りとして2を返し、利用者を探さない")
        void testMethod01() {
            int exitCode = execute("typed-pass-1", "-u", "admin", "--password=secret-pass-1");

            assertThat(exitCode).isEqualTo(2);
            assertThat(picocliErr.toString()).contains("should be specified without 'secret-pass-1' parameter");
            verifyNoInteractions(appUserRepository, auditLogger);
        }

        @Test
        @DisplayName("異常系：-p=値 も引数の誤りとして2を返し、利用者を探さない")
        void testMethod02() {
            int exitCode = execute("typed-pass-1", "-u", "admin", "-p=secret-pass-1");

            assertThat(exitCode).isEqualTo(2);
            assertThat(picocliErr.toString()).contains("should be specified without 'secret-pass-1' parameter");
            verifyNoInteractions(appUserRepository, auditLogger);
        }

        @Test
        @DisplayName("異常系：-p の後ろに離して書いた値も引数の誤りとして2を返し、利用者を探さない")
        void testMethod03() {
            int exitCode = execute("typed-pass-1", "-u", "admin", "-p", "secret-pass-1");

            assertThat(exitCode).isEqualTo(2);
            assertThat(picocliErr.toString()).contains("Unmatched argument at index 3: 'secret-pass-1'");
            verifyNoInteractions(appUserRepository, auditLogger);
        }

        @Test
        @DisplayName("正常系：-p だけなら、実行後に入力した1行を新しいパスワードにする")
        void testMethod04() {
            when(appUserRepository.findByUsername("admin")).thenReturn(Optional.of(admin()));
            when(appUserRepository.updatePassword(eq(1L), anyString(), any(LocalDateTime.class))).thenReturn(1);

            int exitCode = execute("typed-pass-1", "-u", "admin", "-p");

            assertThat(exitCode).isZero();
            ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
            verify(appUserRepository).updatePassword(eq(1L), hash.capture(), any(LocalDateTime.class));
            assertThat(new BCryptPasswordEncoder().matches("typed-pass-1", hash.getValue())).isTrue();
        }
    }

    @Nested
    @DisplayName("call()")
    class Call {

        /** オプションを picocli を通さずに入れる（ChannelRecordCommandTest と同じ形）。入れた配列を返す。 */
        private char[] givenOptions(String username, String password) {
            char[] chars = password.toCharArray();
            ReflectionTestUtils.setField(command, "username", username);
            ReflectionTestUtils.setField(command, "password", chars);
            return chars;
        }

        @Test
        @DisplayName("正常系：BCryptのハッシュとミリ秒に切り詰めた変更時刻で書き、成功を記録して0を返す")
        void testMethod01() {
            char[] password = givenOptions("admin", "new-password-1");
            when(appUserRepository.findByUsername("admin")).thenReturn(Optional.of(admin()));
            when(appUserRepository.updatePassword(eq(1L), anyString(), any(LocalDateTime.class))).thenReturn(1);

            int exitCode = command.call();

            assertThat(exitCode).isZero();
            ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<LocalDateTime> changedAt = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(appUserRepository).updatePassword(eq(1L), hash.capture(), changedAt.capture());
            assertThat(new BCryptPasswordEncoder().matches("new-password-1", hash.getValue())).isTrue();
            // ナノ秒のままだと H2 が丸めて DB の値が後になり、変えた本人のセッションまで失効する
            assertThat(changedAt.getValue().getNano() % 1_000_000).isZero();
            verify(auditLogger).record(AuditAction.PASSWORD_CHANGE, AuditOutcome.SUCCESS, null, null, null,
                    "USER", "admin", AUDIT_DETAIL);
            assertThat(outContent.toString()).contains("パスワードを変更しました: admin（管理者）");
            // 受け取ったパスワードの配列は、使った後に消す
            assertThat(password).containsOnly('\0');
        }

        @Test
        @DisplayName("異常系：利用者が見つからなければ1を返し、管理者のログインIDを案内し、更新も記録もしない")
        void testMethod02() {
            givenOptions("nobody", "new-password-1");
            when(appUserRepository.findByUsername("nobody")).thenReturn(Optional.empty());
            when(appUserRepository.findAll())
                    .thenReturn(List.of(admin(), new AppUser("carol", "hash", AppUser.Role.USER)));

            int exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(errContent.toString())
                    .contains("利用者が見つかりません: nobody")
                    .contains("管理者のログインID: admin");
            verify(appUserRepository, never()).updatePassword(any(), any(), any());
            verifyNoInteractions(auditLogger);
        }

        @Test
        @DisplayName("異常系：新しいパスワードが要件を満たさなければ1を返し、失敗を記録して更新しない")
        void testMethod03() {
            givenOptions("admin", "short");
            when(appUserRepository.findByUsername("admin")).thenReturn(Optional.of(admin()));

            int exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(errContent.toString()).contains("パスワードは8文字以上にしてください");
            verify(auditLogger).record(AuditAction.PASSWORD_CHANGE, AuditOutcome.FAILURE, null, null, null,
                    "USER", "admin", AUDIT_DETAIL + ": パスワードは8文字以上にしてください");
            verify(appUserRepository, never()).updatePassword(any(), any(), any());
        }

        @Test
        @DisplayName("異常系：更新が0件（実行中に利用者が消えた）なら1を返し、成功を記録しない")
        void testMethod04() {
            givenOptions("admin", "new-password-1");
            when(appUserRepository.findByUsername("admin")).thenReturn(Optional.of(admin()));
            when(appUserRepository.updatePassword(eq(1L), anyString(), any(LocalDateTime.class))).thenReturn(0);

            int exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(errContent.toString()).contains("実行中に削除された可能性があります");
            verifyNoInteractions(auditLogger);
        }
    }
}
