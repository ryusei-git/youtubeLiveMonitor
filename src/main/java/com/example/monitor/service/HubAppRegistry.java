package com.example.monitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * ダッシュボード（{@code /system.html}）の左のメニューに並べる、利用者が作ったほかのサービスの一覧。
 * プロセスがどのサービスのものかを、作業フォルダーで見分けるのにも使う。
 *
 * <p><b>一覧はファイル（JSON）で渡す。</b>1 つのサービスに 4 項目あり、起動のしかたには空白や引用符が入る。
 * {@code .env} は {@code bin/*.sh} が bash で読むので、1 キーに詰めると引用の決まりにぶつかる。値は端末ごとに
 * 違うのでリポジトリにも入れない。{@code YtDlpCookies} と同じく既定の置き場所（{@code data/hub-apps.json}）を
 * 決め、置くだけで効くようにした。DB にしないのは、確認用インスタンス（{@code bin/sandbox.sh}）では DB が
 * 起動のたびに作り直され、確かめるたびに登録し直すことになるため。
 *
 * <p><b>起動時に 1 回だけ読む</b>（変えたら再起動が要る）。ファイルが無いとき・読めないときは並べずに続け、
 * 1 件だけおかしいときはその 1 件だけを飛ばす。ダッシュボードの飾りのために監視と録画を止めないため。
 *
 * <p><b>YouTube Live Monitor 自身とそのフォルダーは書かない。</b>この画面を動かしているので常に先頭に出す。
 * フォルダーを書くと、作業ツリーの下で動く確認用インスタンスまでサービスに数えてしまう。
 *
 * <p>ファイルの例（{@code url}・{@code launch} は省いてよい。{@code folder} は絶対パスで書く）:
 * <pre>{@code
 * [
 *   {"name": "キャッチ！", "url": "http://ryusei:3000/", "folder": "/home/ryusei/catch", "launch": "npm start"},
 *   {"name": "Grok Discord Bot", "folder": "/home/ryusei/grok-bot", "launch": "systemctl --user start grok-bot"}
 * ]
 * }</pre>
 */
@Component
@Profile("!cli")
@Slf4j
public class HubAppRegistry {

    /** ファイルの順のまま（不変）。 */
    private final List<HubApp> apps;

    /**
     * @param appsFile 一覧のファイル（JSON）。相対パスはサービスの作業ディレクトリ基準
     */
    public HubAppRegistry(@Value("${monitor.hub.apps-file:data/hub-apps.json}") String appsFile) {
        this.apps = load(Path.of(appsFile));
    }

    /**
     * 並べるサービスを、ファイルに書かれた順で返す（利用者が並べたい順に書くため）。
     *
     * @return サービスの一覧（不変）。ファイルが無い・読めないときは空
     */
    public List<HubApp> apps() {
        return apps;
    }

    /**
     * 作業フォルダーがどのサービスのものかを返す。
     *
     * <p>入れ子のフォルダーは内側のサービスにする（フォルダーの要素数が最も多いもの）。
     * {@link Path#startsWith(Path)} は要素単位で比べるので、{@code /home/a/catch2} は {@code /home/a/catch} の
     * 中にならない。
     *
     * @param workingDirectory プロセスの作業フォルダー
     * @return 当てはまるサービス。どれのフォルダーの中でもなければ空
     */
    public Optional<HubApp> appFor(Path workingDirectory) {
        Path directory = workingDirectory.toAbsolutePath().normalize();
        return apps.stream()
                .filter(app -> directory.startsWith(app.folder()))
                .max(Comparator.comparingInt(app -> app.folder().getNameCount()));
    }

    /**
     * 一覧のファイルを読み、使える件だけを返す。
     *
     * <p>テストから呼ぶため、パッケージプライベートにしている（{@code private} に戻さない）。
     *
     * <p>{@code url} は {@code http://}・{@code https://}・{@code /}（{@code //} は除く）で始まるものだけを
     * 受け付ける。左のメニューのリンクになるので、{@code javascript:} などを押せるようにしないため。
     *
     * @param file 一覧のファイル
     * @return 使える件（ファイルの順、不変）。ファイルが無い・読めないときは空
     */
    static List<HubApp> load(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        Entry[] entries;
        try (InputStream in = Files.newInputStream(file)) {
            entries = JsonMapper.shared().readValue(in, Entry[].class);
        } catch (IOException | JacksonException e) {
            log.warn("ダッシュボードに並べるサービスの一覧を読めませんでした。並べずに続けます: {}", file, e);
            return List.of();
        }
        if (entries == null) {
            // 中身が null だけのファイル。for で NullPointerException になり起動が止まるので、空として扱う
            return List.of();
        }
        List<HubApp> loaded = new ArrayList<>();
        for (Entry entry : entries) {
            String problem = problem(entry);
            if (problem != null) {
                // 要素が null の件は name を呼ぶと NullPointerException で起動が止まるので、null のまま出す
                String name = entry == null ? null : entry.name();
                log.warn("ダッシュボードに並べるサービスを飛ばします（{}）: {}", problem, name);
                continue;
            }
            loaded.add(new HubApp(entry.name(), blankToNull(entry.url()), Path.of(entry.folder()).normalize(),
                    blankToNull(entry.launch())));
        }
        if (!loaded.isEmpty()) {
            log.info("ダッシュボードに並べるサービスを {} 件読みました: {}", loaded.size(), file);
        }
        return List.copyOf(loaded);
    }

    /**
     * 1 件を使えない理由を返す。
     *
     * <p>要素が {@code null} の件（{@code [null]} や末尾の余分な {@code null}）は、Jackson が配列にそのまま入れる
     * ので、名前が空として扱う。
     *
     * @param entry ファイルの 1 件
     * @return 使えない理由（ログに出す）。使えるときは {@code null}
     */
    private static String problem(Entry entry) {
        if (entry == null || entry.name() == null || entry.name().isBlank()) {
            return "名前が空";
        }
        if (!isAbsolutePath(entry.folder())) {
            return "フォルダーは絶対パスで書く";
        }
        String url = blankToNull(entry.url());
        if (url != null && ((!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("/"))
                || url.startsWith("//"))) {
            return "URL は http(s):// か / で始める";
        }
        return null;
    }

    private static boolean isAbsolutePath(String folder) {
        if (folder == null || folder.isBlank()) {
            return false;
        }
        try {
            return Path.of(folder).isAbsolute();
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 並べるサービス 1 件。
     *
     * @param name   メニューに出す名前
     * @param url    トップ画面の URL。無ければ {@code null}（メニューをリンクにしない）
     * @param folder サービスのフォルダー（絶対パスを正規化したもの）。作業フォルダーがこの中のプロセスを
     *               このサービスのものとみなす
     * @param launch 起動のしかた。無ければ {@code null}。停止のダイアログに出し、止めた後に起動し直せる
     *               ようにする
     */
    public record HubApp(String name, String url, Path folder, String launch) {
    }

    /** ファイルの 1 件をそのまま受ける形（確かめる前なので、どの項目も {@code null} がありうる）。 */
    private record Entry(String name, String url, String folder, String launch) {
    }
}
