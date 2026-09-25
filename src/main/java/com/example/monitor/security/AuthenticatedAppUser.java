package com.example.monitor.security;

import com.example.monitor.entity.AppUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import java.time.LocalDateTime;
import java.util.List;

/** 削除後に同名の利用者が登録されても、以前のセッションを引き継がせないためIDを保持する。 */
public final class AuthenticatedAppUser extends User {
    private static final long serialVersionUID = 1L;
    private final Long userId;
    /** パスワードの変更より前のセッションを見分けるため、ログイン時点の変更時刻を持つ。 */
    private final LocalDateTime passwordChangedAt;

    /**
     * セッションにエンティティ自体を保持せず、照合用のIDだけを追加する。
     * @param user 認証する利用者
     */
    public AuthenticatedAppUser(AppUser user) {
        super(user.getUsername(), user.getPasswordHash(), user.isEnabled(), true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        this.userId = user.getId();
        this.passwordChangedAt = user.getPasswordChangedAt();
    }

    /** @return ログイン時点の利用者ID */
    public Long getUserId() {
        return userId;
    }

    /** @return ログイン時点の最後のパスワード変更の時刻。変えたことが無ければ {@code null} */
    public LocalDateTime getPasswordChangedAt() {
        return passwordChangedAt;
    }
}
