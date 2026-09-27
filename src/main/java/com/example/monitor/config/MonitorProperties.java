package com.example.monitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * このアプリ独自の設定値をまとめたもの。{@code application.yml} の {@code monitor} 以下に対応する。
 *
 * <p>実際の値は {@code .env} に書かれた環境変数から供給される
 * （{@code application.yml} 側で {@code ${YOUTUBE_API_KEY:}} のように参照している）。
 * つまり設定の優先順位は「環境変数 → application.yml の既定値」となる。
 *
 * @param youtube   YouTube 関連の設定
 * @param twitch    Twitch 関連の設定
 * @param discord   Discord 関連の設定
 * @param recording 配信録画関連の設定
 * @param admin     初期管理者作成関連の設定
 */
@ConfigurationProperties(prefix = "monitor")
public record MonitorProperties(
        @DefaultValue YouTubeProperties youtube,
        @DefaultValue TwitchProperties twitch,
        @DefaultValue DiscordProperties discord,
        @DefaultValue RecordingProperties recording,
        @DefaultValue AdminProperties admin
) {

    /**
     * YouTube 関連の設定。
     *
     * @param apiKey          YouTube Data API のキー。未設定でも起動はできるが、配信の詳細取得と名前検索が失敗する
     * @param intervalSeconds 監視の実行間隔（秒）。配信中かどうかの確認はクォータを消費しないため短くできる
     * @param search          検索（{@code search.list}）の 1 日の回数の上限
     */
    public record YouTubeProperties(
            @DefaultValue("") String apiKey,
            @DefaultValue("120") int intervalSeconds,
            @DefaultValue SearchProperties search
    ) {

        /**
         * 設定ファイルから値を入れるときに使う（コンストラクタが 2 つあると、
         * どちらで値を入れるかを Spring が決められないため明示する）。
         *
         * @param apiKey          YouTube Data API のキー
         * @param intervalSeconds 監視の実行間隔（秒）
         * @param search          検索の回数の上限
         */
        @ConstructorBinding
        public YouTubeProperties {
        }

        /**
         * 検索の上限を既定値のままにして作る。
         *
         * <p>検索の上限を足す前からある、コードで設定を組み立てる箇所（主にテスト）を
         * 書き換えずに済ませるため。
         *
         * @param apiKey          YouTube Data API のキー
         * @param intervalSeconds 監視の実行間隔（秒）
         */
        public YouTubeProperties(String apiKey, int intervalSeconds) {
            this(apiKey, intervalSeconds, new SearchProperties(52, 12, 10));
        }
    }

    /**
     * YouTube の検索（{@code search.list}）を 1 日に何回まで使うか。
     *
     * <p>既定値の理由（なぜ 52・12・10 か）は {@code YouTubeSearchBudget} の JavaDoc を参照。
     * その場の検索の上限は {@code dailyLimit - discoveryLimit}（発掘の枠を対話が食わないため、
     * 別の設定にせず差で決める）。
     *
     * @param dailyLimit     検索全体の 1 日の上限
     * @param discoveryLimit 定期の新人発掘に取っておく回数
     * @param perUserLimit   その場の検索を 1 人が 1 日に使える回数
     */
    public record SearchProperties(
            @DefaultValue("52") int dailyLimit,
            @DefaultValue("12") int discoveryLimit,
            @DefaultValue("10") int perUserLimit
    ) {}

    /**
     * Twitch 関連の設定。
     *
     * <p>YouTube が API キー1つで済むのに対し、Twitch は Client ID と Client Secret から
     * アクセストークンを取得する方式（OAuth のクライアントクレデンシャルフロー）を採る。
     * トークンには有効期限があるため、取得と再取得は
     * {@code TwitchTokenProvider} が受け持つ。
     *
     * <p>未設定でも起動はできる。その場合 Twitch チャンネルの監視だけが失敗し、
     * YouTube 側の監視には影響しない。
     *
     * @param clientId     Twitch Developer Console で発行した Client ID
     * @param clientSecret 同じく Client Secret
     */
    public record TwitchProperties(
            @DefaultValue("") String clientId,
            @DefaultValue("") String clientSecret
    ) {

        /**
         * Twitch の API を呼び出せる状態かどうかを返す。
         *
         * @return Client ID と Client Secret の両方が設定されていれば {@code true}
         */
        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }

    /**
     * Discord 関連の設定。
     *
     * @param webhookUrl 通知先の Webhook URL。未設定の場合、通知は送信されず起動時に警告が出る
     */
    public record DiscordProperties(
            @DefaultValue("") String webhookUrl
    ) {}

    /**
     * 配信録画関連の設定。
     *
     * <p>録画そのものは外部コマンド {@code yt-dlp} をプロセス起動して行う（Java 製で
     * ライブ配信を継続録画できるライブラリが存在しないため）。{@code yt-dlp} が
     * インストールされていない環境でも起動時エラーにはせず、録画開始時に失敗として扱う。
     *
     * @param directory 録画ファイルの保存先ディレクトリ（チャンネルIDごとにサブディレクトリを作る）
     * @param maxHeight 録画する動画の最大高さ（ピクセル）。画質を落としたくないという要望から、
     *                  既定値の {@code 0} は「上限なし（配信で提供される最高画質・音質をそのまま録画）」
     *                  を意味する。ディスク容量を優先したい場合のみ正の値を設定して制限する
     */
    public record RecordingProperties(
            @DefaultValue("recordings") String directory,
            @DefaultValue("0") int maxHeight
    ) {}

    /**
     * 起動時の初期管理者作成に使う設定（{@code docs/user-portal-design.md} 3.3）。
     *
     * <p>管理者が1人も存在しない状態で起動したときだけ使われる、初回限定の値。
     * パスワードが空のまま起動すると、ログインできる利用者が1人もいない状態になるため、
     * {@code AdminUserInitializer} がその場合を ERROR ログで警告する。
     *
     * @param username 初期管理者のログインID
     * @param password 初期管理者のパスワード（平文）。BCrypt でハッシュ化してから保存し、
     *                 この値自体はどこにも永続化しない
     */
    public record AdminProperties(
            @DefaultValue("admin") String username,
            @DefaultValue("") String password
    ) {}
}
