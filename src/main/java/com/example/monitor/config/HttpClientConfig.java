package com.example.monitor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * アプリ全体で共有する {@link HttpClient} を組み立てる。
 *
 * <p>{@link com.example.monitor.service.LiveStreamDetector} がフィールド初期化子で
 * 直接 {@code new} していたものを Bean 化した。理由はテスト容易性のため。
 * フィールド初期化子のままだとテスト時にモックへ差し替えられず、
 * 実際に YouTube へ通信しないと {@code findLiveVideoId} の分岐を検証できなかった。
 *
 * <h2>要求の上限を応答本文の受け取りまで効かせる</h2>
 * JDK の {@link HttpRequest.Builder#timeout(Duration)} が効くのは応答ヘッダーを受け取るまでで、
 * 本文の読み取りには効かない（JDK 21 の {@code MultiExchange} は、ヘッダーを受け取った時点でタイマーを止める）。
 * そのため、本文の途中で通信が止まると {@code send} が戻らない。巡回の中でそうなると、
 * 並行検知が全件の終了を待つので、巡回全体と「今すぐチェック」が再起動まで止まる。
 *
 * <p>呼び出し側（{@link com.example.monitor.service.LiveStreamDetector}・
 * {@link com.example.monitor.platform.twitch.TwitchApiClient}・
 * {@link com.example.monitor.platform.twitch.TwitchTokenProvider}・
 * {@link com.example.monitor.notification.DiscordNotifier} など）は、どれも {@code timeout} を
 * 「応答の上限」のつもりで付けている。そのため、ここで返す {@link HttpClient} を包み、
 * 一括して本文の受け取りまで同じ上限を効かせる。呼び出し側を 1 か所ずつ {@code sendAsync} に
 * 書き換えると、それぞれのテストの {@code httpClient.send} のモックまで書き換えることになるので、そうしなかった。
 *
 * <p>{@code BodyHandlers.ofInputStream()} は応答ヘッダーの時点で {@code send} が戻り、本文はその後に読む。
 * この場合の読み取りには上限が付かない。
 *
 * <p>包んだ後の上限は、要求 1 回の全体（接続・リダイレクトの追従・本文の受け取り）にかかる。
 * JDK のタイマーはリダイレクトのたびに掛け直していたので、リダイレクトを挟む要求は以前より早く
 * 打ち切られうるが、呼び出し側の上限（10〜15 秒）に対して小さい本文しか読まないので問題にならない。
 */
@Configuration
public class HttpClientConfig {

    /** 接続タイムアウト。1 チャンネルへの接続待ちで監視ループ全体が止まらないようにする。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * アプリ全体で使い回す {@link HttpClient} を生成する。
     *
     * @return 設定済みの {@link HttpClient}。要求の上限を応答本文の受け取りまで効かせるよう包んである
     */
    @Bean
    public HttpClient httpClient() {
        return new DeadlineHttpClient(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /**
     * {@link #send} で、要求の {@code timeout()} を応答本文の受け取りまで効かせる {@link HttpClient}。
     *
     * <p>{@code send} 以外はすべて中身へ委ねる。{@code close()} などの終了処理も委ねるのは、
     * {@link HttpClient} の既定の実装が中身を閉じずにそのまま戻るため
     * （委ねないと、Spring が Bean を破棄するときに中身の {@link HttpClient} が閉じられない）。
     */
    private static final class DeadlineHttpClient extends HttpClient {

        private final HttpClient delegate;

        private DeadlineHttpClient(HttpClient delegate) {
            this.delegate = delegate;
        }

        /**
         * 要求を送り、本文まで受け取った応答を返す。上限を超えたら要求を取り消して打ち切る。
         *
         * <p>{@code future.cancel(true)} は、JDK の {@code sendAsync} が返す future の上で要求そのものを取り消す
         * （JDK 自身の {@code send} も、割り込まれたときに同じ呼び方をしている）。止まった接続を抱え続けないため。
         * 例外の型は JDK の {@code send} と同じにする。呼び出し側は {@link IOException} を捕まえて
         * 「判定できなかった」「送れなかった」に変えているので、{@link IOException} の仲間はそのまま投げる。
         * {@link IllegalArgumentException}・{@link SecurityException} 以外の実行時例外やエラーも、
         * JDK の {@code send} と同じく {@link IOException} に包む。そのまま投げると、{@link IOException} だけを
         * 捕まえる呼び出し側（Twitch の API など）を素通りして巡回まで上がってしまうため。
         * 割り込み済みなら送らずに {@link InterruptedException} を投げるのも、JDK の {@code send} と同じ
         * （送ってすぐ取り消すだけの要求を出さないため）。
         */
        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            Optional<Duration> timeout = request.timeout();
            if (timeout.isEmpty()) {
                return delegate.send(request, responseBodyHandler);
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            CompletableFuture<HttpResponse<T>> future = delegate.sendAsync(request, responseBodyHandler);
            try {
                return future.get(timeout.get().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new HttpTimeoutException("応答を本文まで " + timeout.get().toSeconds() + " 秒以内に受け取れませんでした");
            } catch (InterruptedException e) {
                future.cancel(true);
                throw e;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                if (cause instanceof IllegalArgumentException || cause instanceof SecurityException) {
                    throw (RuntimeException) cause;
                }
                throw new IOException(cause.getMessage(), cause);
            }
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler) {
            return delegate.sendAsync(request, responseBodyHandler);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return delegate.sendAsync(request, responseBodyHandler, pushPromiseHandler);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return delegate.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return delegate.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return delegate.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return delegate.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return delegate.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return delegate.authenticator();
        }

        @Override
        public Version version() {
            return delegate.version();
        }

        @Override
        public Optional<Executor> executor() {
            return delegate.executor();
        }

        @Override
        public WebSocket.Builder newWebSocketBuilder() {
            return delegate.newWebSocketBuilder();
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public void shutdownNow() {
            delegate.shutdownNow();
        }

        @Override
        public boolean awaitTermination(Duration duration) throws InterruptedException {
            return delegate.awaitTermination(duration);
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
