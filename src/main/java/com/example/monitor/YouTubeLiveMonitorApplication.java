package com.example.monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * アプリケーションの起動クラス。1 つの jar で 2 つの動作モードを兼ねる。
 *
 * <h2>サービスモード（引数なし）</h2>
 * <pre>{@code java -jar app.jar}</pre>
 * Web サーバーと監視スケジューラを起動し、常駐する。通常の運用形態。
 *
 * <h2>CLI モード（引数あり）</h2>
 * <pre>{@code java -jar app.jar channel list}</pre>
 * Web サーバーを起動せず、指定されたコマンドを 1 回実行して終了する。
 * {@code cli} プロファイルが有効になり、監視スケジューラ
 * （{@link com.example.monitor.scheduler.LiveStreamPollingScheduler}）は起動しない。
 *
 * <p>サービスが常駐している最中でも CLI を実行できる。
 * H2 を {@code AUTO_SERVER=TRUE} で開いており、別プロセスから同じ DB ファイルへ
 * 安全に接続できるようにしてあるため。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class YouTubeLiveMonitorApplication {

    /** CLI モードで有効にする Spring プロファイル名。 */
    private static final String CLI_PROFILE = "cli";

    /**
     * 起動引数の有無で動作モードを切り替える。
     *
     * @param args コマンドライン引数。空ならサービスモード、指定があれば CLI モード
     */
    public static void main(String[] args) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(YouTubeLiveMonitorApplication.class);

        if (args.length == 0) {
            builder.run(args);
            return;
        }

        // CLI モードでは実行したコマンドの終了コードをプロセスの終了コードとして返す
        builder.web(WebApplicationType.NONE).profiles(CLI_PROFILE);
        ConfigurableApplicationContext context = builder.run(args);
        System.exit(SpringApplication.exit(context));
    }
}
