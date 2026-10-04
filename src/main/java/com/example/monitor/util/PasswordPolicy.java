package com.example.monitor.util;

import com.example.monitor.entity.AppUser;

import java.nio.charset.StandardCharsets;

/**
 * パスワードの要件。
 *
 * <p>招待からの登録と自分のパスワードの変更で条件と文言がずれないよう、1 か所に置く
 * （片方だけ直すと「登録では通ったパスワードに変えられない」といった食い違いになる）。
 *
 * <p>上限（{@link #MAX_BYTES}）もここで見る。ハッシュ化（BCrypt）の失敗としてではなく要件の違反として扱えば、
 * 登録・変更・再設定のどれでも日本語の文言で返り、変更と再設定では他の違反と同じく監査ログに残る。
 */
public final class PasswordPolicy {

    /** パスワードの最低文字数。 */
    public static final int MIN_LENGTH = 8;

    /**
     * パスワードの上限（UTF-8 のバイト数）。
     *
     * <p>BCrypt が扱えるのは先頭 72 バイトまでで、spring-security-crypto 7 はそれを超えるパスワードを
     * ハッシュ化の時点で {@code IllegalArgumentException}（英語の文言）にする。要件の段階で弾かないと、
     * 登録・変更・再設定で英語の文言がそのまま画面に出て、変更では監査ログにも残らない。
     * 文字数ではなくバイト数で数えるのは BCrypt がそう数えるため（全角の文字は 1 文字 3 バイト）。
     */
    public static final int MAX_BYTES = 72;

    /** 上限を超えたときの文言。全角は 3 バイトなので 72 / 3 = 24 文字。 */
    private static final String TOO_LONG_MESSAGE =
            "パスワードが長すぎます。半角の英数字・記号なら72文字、全角の文字なら24文字までにしてください";

    private PasswordPolicy() {
    }

    /**
     * パスワードが一般利用者の要件を満たすか調べる。
     *
     * <p>招待からの登録（{@code InvitationService}）はこちらを使う。招待で作られるのは一般利用者だけなので、
     * 権限で分ける必要がない。
     *
     * @param password パスワード
     * @throws IllegalArgumentException 要件を満たさない場合。メッセージはそのまま画面に出せる
     */
    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("パスワードは" + MIN_LENGTH + "文字以上にしてください");
        }
        if (exceedsMaxBytes(password)) {
            throw new IllegalArgumentException(TOO_LONG_MESSAGE);
        }
    }

    /**
     * パスワードが、その権限の利用者の要件を満たすか調べる。
     *
     * <p>管理者（{@link AppUser.Role#ADMIN}）だけは最低の文字数（{@link #MIN_LENGTH}）を見ず、空でないことだけを求める。
     * 利用者の決定（2026-10-04、#806）で、管理者のパスワードを {@code admin} のような短い値にできるようにした。
     * 管理者は 1 人で、本人が自分で管理するものなので、最低の文字数を押し付けないことにした。
     * 一般利用者には今までどおり {@link #validate(String)} の要件を掛ける（全員の決まりは弱めない）。
     *
     * <p>短いパスワードは推測されやすく、管理者はすべての設定と利用者を扱えるので、当てられたときの害は大きい。
     * それでも許すのは、ログインに回数制限（利用者名ごとに 5 回で 15 分、接続元 IP ごとに 20 回で 15 分。
     * {@code docs/login-attempt-limits.md}）があり、総当たりの試行が守りになっているため。
     * 上限（{@link #MAX_BYTES}）は BCrypt の制約なので、管理者にも掛ける。
     *
     * @param role     パスワードを決める利用者の権限
     * @param password パスワード
     * @throws IllegalArgumentException 要件を満たさない場合。メッセージはそのまま画面に出せる
     */
    public static void validate(AppUser.Role role, String password) {
        if (role != AppUser.Role.ADMIN) {
            validate(password);
            return;
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("パスワードを入力してください");
        }
        if (exceedsMaxBytes(password)) {
            throw new IllegalArgumentException(TOO_LONG_MESSAGE);
        }
    }

    /**
     * パスワードが BCrypt で扱える長さを超えているかを返す。
     *
     * <p>要件の全体（{@link #validate(String)}）とは別に公開するのは、初期管理者の作成（{@code AdminUserInitializer}）が
     * 上限だけを確かめるため。
     *
     * @param password パスワード。{@code null} なら超えていない扱い
     * @return UTF-8 で {@link #MAX_BYTES} バイトを超えていれば {@code true}
     */
    public static boolean exceedsMaxBytes(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
    }
}
