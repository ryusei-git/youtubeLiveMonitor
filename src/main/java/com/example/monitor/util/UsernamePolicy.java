package com.example.monitor.util;

import java.util.regex.Pattern;

/**
 * 利用者名の要件。
 *
 * <p>招待からの登録（{@code InvitationService}）と、管理者による利用者名の変更（{@code AppUserManagementService}、#807）で
 * 条件と文言がずれないよう、1 か所に置く（{@link PasswordPolicy} と同じ理由。片方だけ直すと、
 * 「登録では通らない名前に変えられる」といった抜け道になる）。中身は登録の検証をそのまま移したもの。
 */
public final class UsernamePolicy {

    /** 利用者名の最低文字数。 */
    private static final int MIN_LENGTH = 3;

    /** 利用者名の最大文字数（DB の列長と揃えている）。 */
    private static final int MAX_LENGTH = 64;

    /**
     * 利用者名に使わせない文字（制御文字 Cc・書式文字 Cf・空白 Z）。
     *
     * <p>改行を許すと、利用者名を出すすべてのログに偽の行を書き込める。ゼロ幅スペース（Cf）や
     * 全角空白（Z。{@link String#trim()} では前後から落ちない）を許すと、見た目が同じ別の利用者名を作れ、
     * 利用者一覧と監査ログで他人になりすませる。
     */
    private static final Pattern FORBIDDEN_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Z}]");

    private UsernamePolicy() {
    }

    /**
     * 入力された利用者名の前後の空白を落とし、要件を満たすか調べる。
     *
     * <p>前後の空白を落とす処理も要件と一緒にここへ置く。登録と変更のどちらかだけが落とすと、
     * 同じ入力でも登録と変更で保存される名前が変わるため。落とした後の値を返すので、呼び出し側は戻り値を保存する。
     *
     * @param username 入力された利用者名。{@code null} は空文字として扱う
     * @return 前後の空白を落とした利用者名
     * @throws IllegalArgumentException 要件を満たさない場合。メッセージはそのまま画面に出せる
     */
    public static String normalizeAndValidate(String username) {
        String name = username == null ? "" : username.trim();
        if (name.length() < MIN_LENGTH || name.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("利用者名は" + MIN_LENGTH + "〜" + MAX_LENGTH + "文字にしてください");
        }
        if (FORBIDDEN_CHARACTERS.matcher(name).find()) {
            throw new IllegalArgumentException("利用者名に空白・改行・見えない文字は使えません");
        }
        return name;
    }
}
