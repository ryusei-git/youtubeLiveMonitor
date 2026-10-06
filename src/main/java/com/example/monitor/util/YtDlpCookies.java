package com.example.monitor.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * {@code yt-dlp} にログインした状態の Cookie を渡す引数（{@code --cookies}）を組み立てる。
 *
 * <p>2026-10-06 から、YouTube の ASMR 系の配信がログインなしでは {@code Sign in to confirm you’re not a bot} で
 * 断られ、録画が失敗するようになった（yt-dlp を最新にしても直らない）。yt-dlp の公式の案内どおり、
 * 録画専用のアカウントでログインしたブラウザから書き出した Cookie ファイルを渡す（Issue #812）。
 * ブラウザから直接読む {@code --cookies-from-browser} にしないのは、サービスが画面の無い端末でも動く前提のため。
 *
 * <p><b>ファイルがあるときだけ付ける。</b>既定の置き場所（{@code data/youtube-cookies.txt}）を決めておき、
 * 置くだけで効くようにしている。無いのに付けると、yt-dlp は読めないファイルを黙って読み飛ばしたうえ、
 * 終了時にそのパスへ空の Cookie ファイルを書き出す。次からは「ファイルがある」ことになり、利用者が
 * 置いたつもりのない空のファイルで動き続けるので、付けない。
 *
 * <p><b>元のファイルではなく、起動のたびに作る写しを渡す。</b>yt-dlp は終了するとき、渡されたファイルへ
 * Cookie を書き戻す。その書き方はファイルを空にしてから書く（一時ファイルからの置き換えではない）ので、
 * 同時に動く録画の 1 本が書き戻している間に別の 1 本が読むと、空や書きかけを読んで失敗しうる
 * （yt-dlp 2026.08.19 の {@code YoutubeDLCookieJar.save}・{@code YoutubeDL.close} で確かめた）。
 * 写しは元のファイルと同じフォルダーの {@code ytdlp-cookies/} に、本人だけが読める権限で置く
 * （{@link OwnerOnlyFiles}。Cookie はアカウントそのものなので、元のファイルと同じく他人に読ませない）。
 *
 * <p><b>写しは、使っている yt-dlp が OS 上にいなくなってから消す</b>（{@link #deleteUnusedCopies}）。
 * 終了を待って消す作りにしないのは、録画の yt-dlp がアプリの再起動をまたいで動き続けるため
 * （{@code docs/pitfalls.md}「外部プロセスの出力を JVM へのパイプにすると、再起動で yt-dlp が止まる」）。
 * 終了を待つ側のスレッドは再起動で失われるが、OS のプロセスを見る見回りなら再起動の前後で同じように消せる。
 *
 * <p>録画（{@link com.example.monitor.service.StreamRecorder}）、手動ダウンロード
 * （{@link com.example.monitor.service.VideoDownloadService}。端末に保存も同じコマンドを使う）、
 * ダウンロード前の下調べ（{@link com.example.monitor.service.VideoSourceProbe}）で同じ指定を使うため、
 * {@link YtDlpJsRuntime} と同じ理由で独立クラスにしている。
 *
 * <p><b>yt-dlp を起動する箇所を増やすときは、必ずここを通す。</b>付け忘れた経路だけログインなしで
 * YouTube を取得するので、録画は取れるのに下調べだけが bot の確認で断られる、といった食い違いが起きる。
 */
@Slf4j
public final class YtDlpCookies {

    /** 写しを置くフォルダーの名前（元の Cookie ファイルと同じフォルダーの下に作る）。 */
    private static final String COPY_DIRECTORY = "ytdlp-cookies";

    /**
     * これより新しい写しは、使っているプロセスが見当たらなくても消さない。
     * 写しを作ってから yt-dlp が起動するまでの間に見回りが走ると、起動前の写しを消してしまうため。
     */
    private static final Duration MIN_AGE = Duration.ofMinutes(1);

    private YtDlpCookies() {
    }

    /**
     * Cookie ファイルの写しを作り、{@code yt-dlp} に足す引数を返す。
     *
     * <p>写しを作るので、yt-dlp を起動する直前に呼ぶ。起動の何時間も前に作ると、使われる前に
     * {@link #deleteUnusedCopies} が消してしまう。
     *
     * <p>写しを作れないときは、引数を付けずに続ける。ログインが要らない動画まで取れなくするより、
     * 今までどおり試す方が損が小さいため。
     *
     * @param cookiesFile 元の Cookie ファイル（Netscape 形式）。相対パスはサービスの作業ディレクトリ基準。
     *                    空・{@code null}・ファイルが無いときは付けない
     * @return 足す引数（{@code --cookies <写しの絶対パス>}）。付けない場合は空のリスト
     */
    public static List<String> options(String cookiesFile) {
        if (cookiesFile == null || cookiesFile.isBlank()) {
            return List.of();
        }
        Path source = Path.of(cookiesFile.strip()).toAbsolutePath();
        if (!Files.isRegularFile(source)) {
            return List.of();
        }
        Path copy = copyDirectory(source).resolve(UUID.randomUUID() + ".txt");
        try {
            OwnerOnlyFiles.writeAtomically(copy, Files.readString(source, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Cookie ファイルの写しを作れないため、Cookie なしで yt-dlp を起動します: {}", source, e);
            return List.of();
        }
        return List.of("--cookies", copy.toString());
    }

    /**
     * 使っているプロセスが無い写しを消す。
     *
     * <p>写しは 1 回の起動で 1 つなので、そのパスをコマンドラインに含むプロセスが無ければ、もう誰も使わない。
     * コマンドラインを取れない OS（Windows の一部）では使用中の写しも消しうるが、yt-dlp は Cookie を
     * 起動時に読み込むので取得は続く。終了時に書き戻した写しは、次の見回りで消える。
     *
     * @param cookiesFile 元の Cookie ファイル（写しの置き場所を決めるため）。空・{@code null} なら何もしない
     * @param inUse       写しの絶対パスを渡すと、それを使うプロセスが動いているかを返す
     */
    public static void deleteUnusedCopies(String cookiesFile, Predicate<String> inUse) {
        if (cookiesFile == null || cookiesFile.isBlank()) {
            return;
        }
        Path directory = copyDirectory(Path.of(cookiesFile.strip()).toAbsolutePath());
        if (!Files.isDirectory(directory)) {
            return;
        }
        Instant threshold = Instant.now().minus(MIN_AGE);
        try (DirectoryStream<Path> copies = Files.newDirectoryStream(directory)) {
            for (Path copy : copies) {
                if (Files.getLastModifiedTime(copy).toInstant().isBefore(threshold) && !inUse.test(copy.toString())) {
                    Files.deleteIfExists(copy);
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            // 実行時例外も捕まえるのは、呼び出し元の後始末（録画の状態の補正）まで巻き込んで止めないため
            log.warn("使い終わった Cookie ファイルの写しを消せませんでした。次の見回りで再試行します: {}", directory, e);
        }
    }

    private static Path copyDirectory(Path source) {
        return source.resolveSibling(COPY_DIRECTORY);
    }
}
