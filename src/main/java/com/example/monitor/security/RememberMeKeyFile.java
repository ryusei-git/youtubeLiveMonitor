package com.example.monitor.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 「ログインしたままにする」の Cookie に署名する鍵を、ファイルから読む（無ければ作る）。
 *
 * <p>鍵をファイルに残すのは、起動のたびに作り直すと反映（再起動）のたびに全員の
 * 「ログインしたまま」が無効になり、機能の目的を果たせないため。逆に、全員をログインし直させたいときは
 * このファイルを消して再起動すればよい。
 *
 * <p>{@code .env} ではなく専用のファイルにするのは、設定を足さなくても初回の起動で動くようにするため。
 * 鍵が漏れると任意の利用者の Cookie を偽造できるので、作るときは本人だけが読める権限（600）にする。
 * 権限は umask に任せず、作る時点で指定する（作った直後に変えると、その間だけ他人が読める）。
 */
public final class RememberMeKeyFile {

    /** 鍵の長さ。HMAC ではなくハッシュの材料だが、推測できない長さとして 256 ビットにする。 */
    private static final int KEY_BYTES = 32;

    private RememberMeKeyFile() {
    }

    /**
     * 鍵を返す。ファイルがあればその中身（前後の空白を除く）、無ければ新しく作って書き込んだもの。
     *
     * @param file 鍵ファイルの場所
     * @return 署名の鍵
     */
    public static String loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                return Files.readString(file, StandardCharsets.UTF_8).strip();
            }
            byte[] bytes = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(bytes);
            String key = Base64.getEncoder().encodeToString(bytes);
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(file, key, StandardCharsets.UTF_8);
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("remember-me の鍵ファイルを読み書きできません: " + file, e);
        }
    }
}
