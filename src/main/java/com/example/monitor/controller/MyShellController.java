package com.example.monitor.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 利用者画面の入口（{@code /my} と {@code /my/**}）を、1 枚のページ {@code my.html} で返す（#146）。
 *
 * <p>利用者画面は 1 枚のページの中で表示を切り替える（画面を移っても再生を止めないため）。
 * それでも {@code /my/archive?genre=雑談} のような画面ごとの URL を、再読み込み・ブックマーク・
 * ログイン後の復帰で開けるようにしたい。そこでどの URL で来ても同じページを返し、
 * どの画面を出すかはページ側が URL を見て決める。
 *
 * <p><b>リダイレクトではなく forward にしている理由。</b>リダイレクトするとブラウザの URL が
 * {@code /my.html} に変わり、開いていた画面と検索条件が URL から消える。
 * その結果、ブックマーク・戻る・検索条件の URL 保持が壊れる。
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

    /**
     * どの画面の URL でも、ブラウザの URL を変えずに {@code my.html} の中身を返す。
     *
     * @return {@code my.html} への forward
     */
    @GetMapping({"/my", "/my/**"})
    public String shell() {
        return "forward:/my.html";
    }

    /**
     * 旧画面のマイチャンネルを新しい画面へ移す。旧画面は URL に検索条件を持たなかったので、クエリは引き継がない。
     * 静的ファイルより先にコントローラーが選ばれるため、旧画面のファイルが残っていても開かれない。
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
