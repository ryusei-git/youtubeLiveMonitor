package com.example.monitor.util;

import java.util.List;

/**
 * {@code yt-dlp} に JavaScript のランタイムを指定する引数（{@code --js-runtimes}）を組み立てる。
 *
 * <p>yt-dlp は YouTube の取得に JS ランタイムを使い、既定で探すのは deno だけである。
 * 無いと「No supported JavaScript runtime」と警告し、一部の形式（高画質のトラックなど）が
 * 取れなくなりうる。この端末には deno を入れず、既にある node を使うと決めた（Issue #212）。
 *
 * <p><b>node のパスは設定（{@code monitor.recording.js-runtime}）で渡し、コードに書かない。</b>
 * nvm で入れた node はパスに版が入り、版を上げるとパスが変わるため。
 * サービスの PATH に node が入っているとも限らないので、パスごと渡せる書式
 * （{@code node:/path/to/node}）をそのまま yt-dlp に渡す。
 *
 * <p>録画（{@link com.example.monitor.service.StreamRecorder}）、手動ダウンロード
 * （{@link com.example.monitor.service.VideoDownloadService}。端末に保存も同じコマンドを使う）、
 * ダウンロード前の下調べ（{@link com.example.monitor.service.VideoSourceProbe}）の 3 か所で
 * 同じ指定を使うため、{@link YtDlpFormatSelector} と同じ理由で独立クラスにしている。
 *
 * <p><b>yt-dlp を起動する箇所を増やすときは、必ずここを通す。</b>下調べだけ付け忘れていたことがある。
 * 付け忘れた経路だけ JS ランタイムなしで YouTube を取得するので、ダウンロード本体は取れるのに
 * 前段の下調べだけが失敗する、といった食い違いが起きる。
 */
public final class YtDlpJsRuntime {

    private YtDlpJsRuntime() {
    }

    /**
     * 設定値から {@code yt-dlp} に足す引数を返す。
     *
     * <p>空のときは何も足さない。既定を空にしているのは、JS ランタイムの無い環境でも
     * 今までどおり yt-dlp が動く（警告が出るだけ）ようにするため。
     *
     * @param runtime {@code --js-runtimes} に渡す値（{@code RUNTIME[:PATH]}）。空または {@code null} なら付けない
     * @return 足す引数。付けない場合は空のリスト
     */
    public static List<String> options(String runtime) {
        if (runtime == null || runtime.isBlank()) {
            return List.of();
        }
        return List.of("--js-runtimes", runtime.strip());
    }
}
