package com.example.monitor.security;

import com.example.monitor.service.UserSubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 録画ファイル（{@code /recordings/**}）を見てよいかを、URL のチャンネル識別子で判定する。
 *
 * <h2>なぜ URL の規則だけでは決められないのか</h2>
 * 録画ファイルは {@code /recordings/{チャンネルID}/{動画ID}.mp4} に置かれる。
 * 管理者は全部見てよいが、<b>一般利用者は自分が購読しているチャンネルのものだけ</b>。
 * これは「誰が」「どのチャンネルを」購読しているかを見ないと決まらないため、
 * {@code requestMatchers(...).hasRole(...)} のような静的なルールでは表せない。
 *
 * <h2>静的リソース配信を置き換えない</h2>
 * 判定だけをここで行い、配信そのものは {@code RecordingResourceConfig} の
 * 静的リソース機構に任せる。自前でバイト列を返すコントローラを書くと、
 * {@code <video>} のシークに必要な HTTP Range 対応とパストラバーサル対策を
 * 作り直すことになる（{@code docs/pitfalls.md}「録画ファイルの配信は自前のストリーミング処理を書かない」参照）。
 */
@Component
@RequiredArgsConstructor
public class RecordingFileAuthorizationManager
        implements AuthorizationManager<RequestAuthorizationContext> {

    /** 録画ファイルの URL の接頭辞。 */
    private static final String PREFIX = "/recordings/";

    /** 管理者の権限名。 */
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    /**
     * 購読の確認先。
     *
     * <p>{@code @Lazy} を付けているのは、セキュリティ設定とサービス層が
     * 起動時に互いを必要として循環参照になるのを避けるため。
     */
    @Lazy
    private final UserSubscriptionService userSubscriptionService;

    /**
     * Spring Security 6 ではまだ抽象メソッドなので実装が要るが、6.4 で非推奨になり、
     * フィルターは {@link #authorize} を呼ぶ。判定は {@link #authorize} に置き、ここは委ねるだけにする
     * （7 系〈Spring Boot 4.x、#320〉で消える）。
     */
    @Override
    @Deprecated
    public AuthorizationDecision check(Supplier<Authentication> authentication,
                                       RequestAuthorizationContext context) {
        return authorize(authentication, context);
    }

    @Override
    public AuthorizationDecision authorize(Supplier<Authentication> authentication,
                                           RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated()) {
            return new AuthorizationDecision(false);
        }

        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(granted -> ADMIN_AUTHORITY.equals(granted.getAuthority()));
        if (isAdmin) {
            return new AuthorizationDecision(true);
        }

        String channelId = channelIdFrom(context.getRequest().getRequestURI());
        if (channelId == null) {
            // チャンネルの階層に無いファイル（未紐づけのダウンロード置き場など）は管理者だけ
            return new AuthorizationDecision(false);
        }
        return new AuthorizationDecision(userSubscriptionService.canAccessChannelRecordings(channelId));
    }

    /**
     * URL からチャンネル識別子を取り出す。
     *
     * @param uri リクエストの URI
     * @return チャンネル識別子。取り出せなければ {@code null}
     */
    private static String channelIdFrom(String uri) {
        if (uri == null || !uri.startsWith(PREFIX)) {
            return null;
        }
        String rest = uri.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        // ディレクトリを挟まない（＝チャンネルに属さない）ファイルは対象外
        return slash <= 0 ? null : rest.substring(0, slash);
    }
}
