package com.example.monitor.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 利用者画面の入口（{@code /my} と {@code /my/**}）を、1 枚のページ {@code my.html} で返す（#146）。
 *
 * <p>利用者画面は 1 枚のページの中で表示を切り替える（画面を移っても再生を止めないため）。
 * それでも {@code /my/archive?genre=雑談} のような画面ごとの URL を、再読み込み・ブックマーク・
 * ログイン後の復帰で開けるようにしたい。そこでどの URL で来ても同じページを返し、
 * どの画面を出すかはページ側が URL を見て決める。
 *
 * <p><b>リダイレクトせず、その URL のまま中身を返す理由。</b>リダイレクトするとブラウザの URL が
 * {@code /my.html} に変わり、開いていた画面と検索条件が URL から消える。
 * その結果、ブックマーク・戻る・検索条件の URL 保持が壊れる。
 *
 * <p><b>{@code /my.html} へ forward せず、ここで HTML を読んで返す理由（#894）。</b>読み込みのときに
 * 白い画面が一瞬出ないよう、ダークテーマは最初の描画より前に決める必要がある。本番の CSP は
 * {@code script-src 'self'} でインラインのスクリプトを置けず、友人用の入口の許可リストのために新しい
 * JS ファイルも足せない。{@code common.js} は {@code body} の末尾でしか読めないので、スクリプトで
 * 先に切り替えられない。そこで、サーバーが Cookie を見て {@code <html data-theme="...">} を書いて返す。
 *
 * <p>利用者の旧画面の URL は、逆にリダイレクトで新しい画面の URL へ移す（#178）。ブックマークから来た人の
 * URL を新しいものに変え、以後の再読み込みとブックマークを新しい画面にするため。
 * {@code /videos.html} はここで移さない。管理者の動画一覧でもあるため、一般利用者だけを
 * {@code videos.js} が {@code /my/videos} へ移す。
 *
 * <p><b>{@code @Profile("!cli")} を付けていない理由。</b>依存する Bean が無いため、
 * CLI（Web サーバーを起動しない）でこの Bean が作られても起動は妨げない
 * （{@code docs/pitfalls.md} の事故は、CLI で作られない Bean への依存が原因だった）。
 */
@Controller
public class MyShellController {

    /** {@code my.html} の中で、Cookie の値に合わせて書き換える属性。 */
    private static final String DEVICE_THEME_ATTRIBUTE = "data-theme=\"device\"";

    /**
     * どの画面の URL でも、ブラウザの URL を変えずに {@code my.html} の中身を返す。
     *
     * <p>Cookie は利用者が書き換えられるので、決まった 2 つの値のときだけ属性を書き換え、値そのものは
     * HTML へ書かない（それ以外の値は {@code device} のまま）。{@code Cache-Control} は静的ファイルの
     * 設定（{@code spring.web.resources.cache.cachecontrol.no-cache}）と同じにする。forward をやめても、
     * 古い HTML をブラウザに残さないため。ファイルは小さいので、起動時に抱えず要求ごとに読む。
     *
     * @param theme Cookie {@code ylm_theme} の値（{@code myApplyTheme} が書く）。無ければ null
     * @return {@code my.html} の中身
     * @throws IOException {@code my.html} を読めなかったとき
     */
    @GetMapping({"/my", "/my/**"})
    public ResponseEntity<String> shell(@CookieValue(name = "ylm_theme", required = false) String theme)
            throws IOException {
        String html = new ClassPathResource("static/my.html").getContentAsString(StandardCharsets.UTF_8);
        if ("dark".equals(theme) || "light".equals(theme)) {
            html = html.replace(DEVICE_THEME_ATTRIBUTE, "data-theme=\"" + theme + "\"");
        }
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noCache())
                .body(html);
    }

    /**
     * 旧画面のマイチャンネルを新しい画面へ移す。旧画面は URL に検索条件を持たなかったので、クエリは引き継がない。
     *
     * @return {@code /my/channels} へのリダイレクト
     */
    @GetMapping("/my-channels.html")
    public String oldChannels() {
        return "redirect:/my/channels";
    }

    /**
     * 旧画面の録画を新しい画面（アーカイブ）へ移す。理由は {@link #oldChannels()} と同じ。
     *
     * @return {@code /my/archive} へのリダイレクト
     */
    @GetMapping("/my-recordings.html")
    public String oldRecordings() {
        return "redirect:/my/archive";
    }
}
