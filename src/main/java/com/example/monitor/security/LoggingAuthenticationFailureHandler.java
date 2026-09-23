package com.example.monitor.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * ログイン失敗を必ずアプリログに残したうえで、既定のリダイレクト処理に委譲する。
 *
 * <p>失敗の記録が漏れると総当たり攻撃などの兆候に気づけなくなる
 * （{@code GlobalExceptionHandler} が利用者操作の失敗を必ず WARN で残しているのと同じ理由）。
 * DB へ保存して画面から検索・集計できる監査ログは設計書の段階2で扱うため、
 * ここではアプリログ（ファイル）への記録に留める。
 */
@Component
@Profile("!cli")
@Slf4j
public class LoggingAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    /** 失敗時に戻す先。{@code login.html} 側がこのクエリパラメータの有無でエラー表示を出す。 */
    private static final String FAILURE_URL = "/login.html?error";

    public LoggingAuthenticationFailureHandler() {
        super(FAILURE_URL);
    }

    /**
     * ログイン失敗を WARN で記録してから、既定のリダイレクト処理に委譲する。
     *
     * @param request   リクエスト
     * @param response  レスポンス
     * @param exception 認証に失敗した理由
     * @throws IOException      リダイレクト処理で発生しうる例外
     * @throws ServletException 親クラスの処理で発生しうる例外
     */
    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                         AuthenticationException exception) throws IOException, ServletException {
        // パスワードそのものはログに残さない（usernameパラメータのみ参照する）
        log.warn("ログインに失敗しました: user={}, reason={}", request.getParameter("username"), exception.getMessage());
        super.onAuthenticationFailure(request, response, exception);
    }
}
