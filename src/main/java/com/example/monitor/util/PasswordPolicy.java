package com.example.monitor.util;

/**
 * パスワードの要件。
 *
 * <p>招待からの登録と自分のパスワードの変更で条件と文言がずれないよう、1 か所に置く
 * （片方だけ直すと「登録では通ったパスワードに変えられない」といった食い違いになる）。
 */
public final class PasswordPolicy {

    /** パスワードの最低文字数。 */
    public static final int MIN_LENGTH = 8;

    private PasswordPolicy() {
    }

    /**
     * パスワードが要件を満たすか調べる。
     *
     * @param password パスワード
     * @throws IllegalArgumentException 要件を満たさない場合。メッセージはそのまま画面に出せる
     */
    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("パスワードは" + MIN_LENGTH + "文字以上にしてください");
        }
    }
}
