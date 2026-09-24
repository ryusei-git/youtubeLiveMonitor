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
}
