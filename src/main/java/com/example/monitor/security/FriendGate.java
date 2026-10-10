package com.example.monitor.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * 友人用の入口（関所）から来た要求かを見分け、その要求では管理者として扱わないための判定。
 *
 * <p>友人は管理者とは別の入口（運用リポジトリの関所。利用者の画面と API だけを通す許可リスト）から入る。
 * 関所はアプリへ渡す要求に必ず {@link #HEADER} を付ける（友人が送ってきた同名のヘッダは上書きする）。
 * 許可リストだけでは次の 3 つの道が残るため、このヘッダの有る要求では管理者を締め出す。
 * <ul>
 *   <li>フォームのログイン: 管理者も利用者も同じ {@code /api/auth/login} で、入口の区別は本文の {@code portal} だけ。
 *       関所から {@code portal=admin} を送れば管理者として入れてしまう（{@link PortalAwareAuthenticationProvider}）</li>
 *   <li>「ログインしたまま」の Cookie による自動ログイン（{@link AppRememberMeServices}）</li>
 *   <li>既にあるセッション（{@link ActiveAppUserFilter}）</li>
 * </ul>
 *
 * <p>値は見ず、ヘッダが<b>有ること</b>だけで判定する。このヘッダは権限を狭める向きにしか働かないので、
 * 誰がどんな値で付けても得はない（管理者の入口で付ければ、その要求で管理者として入れなくなるだけ）。
 * 逆に関所がヘッダを付け忘れるとこの締め出しは効かなくなる（そのときも関所の許可リストは残る）。
 */
public final class FriendGate {

    /** 関所がアプリへ渡す要求に付けるヘッダ。 */
    public static final String HEADER = "X-YLM-Friend-Gate";

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private FriendGate() {
    }

    /**
     * 友人用の入口から来た要求か。
     *
     * @param request 要求
     * @return {@link #HEADER} が有れば（値が空でも）true
     */
    public static boolean matches(HttpServletRequest request) {
        return request.getHeader(HEADER) != null;
    }

    /**
     * 管理者の権限を持つか。
     *
     * @param authorities 利用者の権限
     * @return ROLE_ADMIN を含めば true
     */
    public static boolean isAdmin(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().anyMatch(a -> ROLE_ADMIN.equals(a.getAuthority()));
    }
}
