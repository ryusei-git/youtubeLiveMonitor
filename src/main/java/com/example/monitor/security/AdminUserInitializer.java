package com.example.monitor.security;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AppUser.Role;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.util.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 起動時に管理者が1人もいなければ、{@code .env} の値から初期管理者を作成する
 * （{@code docs/user-portal-design.md} 3.3）。
 *
 * <h2>二重作成しない判定方法</h2>
 * {@link AppUserRepository#existsByRole(Role)} で「管理者が1人でもいるか」を見る。
 * 特定のユーザー名との一致で判定しないのは、運用者が初期管理者のユーザー名を
 * 後から変えていても、既に管理者がいる事実だけで十分だから
 * （毎回 {@code .env} の値で上書きすると、画面から変更したパスワードが
 * 次回起動で消えてしまう）。
 *
 * <h2>パスワードが未設定の場合</h2>
 * 空のまま作成すると空文字列がそのままハッシュ化されてしまい、それはそれで
 * ログインできる（しかも本人以外にも推測されやすい）ため、作成せず ERROR ログだけ残す。
 * この場合、運用者が {@code .env} に {@code ADMIN_PASSWORD} を設定して再起動するまで
 * ログインできる利用者が1人も存在しない状態になる。
 *
 * <h2>パスワードが長すぎる場合</h2>
 * BCrypt は 72 バイトを超えるパスワードをハッシュ化できず例外を投げる。{@link CommandLineRunner} の例外は
 * 起動そのものを止め、監視・通知まで動かなくなるので、未設定のときと同じく作成だけ見送って ERROR ログを残す
 * （{@link com.example.monitor.util.PasswordPolicy#MAX_BYTES}）。
 *
 * <h2>{@code cli} プロファイルで動かさない理由</h2>
 * CLI 実行（{@code channel list} 等）のたびに管理者作成を試みる必要はなく、
 * また依存する {@link PasswordEncoder} は Web 専用の {@code SecurityConfig} が
 * 提供するため、このクラスが {@code cli} プロファイルでも Bean 化されると
 * 依存解決に失敗して CLI が起動できなくなる
 * （{@code MonitoringController} で実際に起きた事故と同種）。
 */
@Component
@Profile("!cli")
@RequiredArgsConstructor
@Slf4j
@Order(1)
public class AdminUserInitializer implements CommandLineRunner {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final MonitorProperties monitorProperties;

    /**
     * 管理者の有無を確認し、必要なら初期管理者を作成する。
     *
     * @param args 未使用（{@link CommandLineRunner} のシグネチャ上必要）
     */
    @Override
    public void run(String... args) {
        if (appUserRepository.existsByRole(Role.ADMIN)) {
            log.debug("管理者は既に存在するため、初期管理者の作成をスキップします");
            return;
        }

        String username = monitorProperties.admin().username();
        String password = monitorProperties.admin().password();
        if (password == null || password.isBlank()) {
            log.error("管理者が1人も存在せず、ADMIN_PASSWORDも未設定のためログインできる利用者がいません。"
                    + ".envにADMIN_USERNAME/ADMIN_PASSWORDを設定してから再起動してください");
            return;
        }
        if (PasswordPolicy.exceedsMaxBytes(password)) {
            log.error("ADMIN_PASSWORDが長すぎるため初期管理者を作成しませんでした（UTF-8で{}バイトまで。全角なら24文字まで）。"
                    + ".envのADMIN_PASSWORDを短くしてから再起動してください", PasswordPolicy.MAX_BYTES);
            return;
        }

        AppUser admin = new AppUser(username, passwordEncoder.encode(password), Role.ADMIN);
        appUserRepository.save(admin);
        log.info("初期管理者を作成しました: user={}", username);
    }
}
