package com.example.monitor.security;

import com.example.monitor.entity.AppUser;
import com.example.monitor.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Spring Security にログイン利用者を提供する。
 *
 * <p>権限は {@link AppUser.Role} をそのまま {@code ROLE_} プレフィックス付きの権限に変換する
 * （{@code hasRole("ADMIN")} が内部で {@code ROLE_ADMIN} という文字列を要求するため）。
 *
 * <p>{@link AppUser#enabled} が {@code false} の利用者は {@code disabled(true)} で
 * 無効化として扱う。これにより無効化された利用者は認証情報が合っていてもログインできなくなる
 * （レコード自体は削除しない。{@code docs/user-portal-design.md} 3.2 参照）。
 *
 * <p>{@code cli} プロファイルでは Bean 化しない。CLI にはログイン機能が無く、
 * このクラスが依存する {@link AppUserRepository} 自体は CLI でも使えるが、
 * 認証まわりの Bean は Web 専用であることを一貫して明示する方針にしている
 * （{@code SecurityConfig} の JavaDoc 参照）。
 */
@Service
@Profile("!cli")
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    /**
     * ログインIDから Spring Security 用の利用者情報を組み立てる。
     *
     * @param username ログインID
     * @return 認証・認可に使う利用者情報
     * @throws UsernameNotFoundException 該当する利用者が存在しない場合
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser appUser = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("ユーザーが見つかりません: " + username));

        return new AuthenticatedAppUser(appUser);
    }
}
