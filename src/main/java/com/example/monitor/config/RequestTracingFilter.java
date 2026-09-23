package com.example.monitor.config;

import com.example.monitor.util.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * リクエストごとに相関 ID を発行し、そのリクエストの処理中に出た全ログへ載せる。
 *
 * <h2>認証より前に動かしている理由</h2>
 * {@link Order} を最優先にして<b>Spring Security のフィルターチェーンより前</b>に置いている。
 * 後ろに置くと、認証・認可で弾かれたリクエスト（401 / 403）が
 * このフィルターに到達せず<b>相関 ID を持たない</b>。
 * 権限の無い操作の試行こそ監査で追いたい対象なので、そこが欠けては意味がない。
 *
 * <p>その代わり、この時点ではまだ誰のリクエストか分からない。利用者名が要る場面は認証後なので
 * {@code RequestContext.currentUsername()} で都度取得する（{@link RequestContext} の JavaDoc 参照）。
 *
 * <h2>応答ヘッダーにも載せる理由</h2>
 * 画面でエラーが出たときに利用者へ相関 ID を示せるようにするため。
 * 「何時ごろエラーが出た」ではなく ID で特定できれば、ログを遡らずに該当の処理だけを追える。
 */
@Component
@Profile("!cli")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTracingFilter extends OncePerRequestFilter {

    /** 応答に載せる相関 ID のヘッダー名。 */
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    /**
     * 相関 ID を発行し、MDC と応答ヘッダーへ載せた状態で後続の処理を実行する。
     *
     * @param request     リクエスト
     * @param response    レスポンス
     * @param filterChain 後続のフィルターチェーン
     * @throws ServletException 後続の処理が失敗した場合
     * @throws IOException      入出力に失敗した場合
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = RequestContext.newRequestId();
        // ヘッダーは後続の処理が応答を書き出す前に設定する必要がある
        response.setHeader(REQUEST_ID_HEADER, requestId);

        // filterChain.doFilter は検査例外を投げるため callWithRequestId には渡せない。
        // MDC のキーを書かずに済むよう put/restore を使う
        String previous = RequestContext.put(requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestContext.restore(previous);
        }
    }
}
