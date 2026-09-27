package com.example.monitor.security;

import com.example.monitor.util.OwnerOnlyFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;

/**
 * 「ログインしたままにする」の Cookie に署名する鍵を、ファイルから読む（無ければ作る）。
 *
 * <p>鍵をファイルに残すのは、起動のたびに作り直すと反映（再起動）のたびに全員の
 * 「ログインしたまま」が無効になり、機能の目的を果たせないため。逆に、全員をログインし直させたいときは
 * このファイルを消して再起動すればよい。
 *
 * <p>{@code .env} ではなく専用のファイルにするのは、設定を足さなくても初回の起動で動くようにするため。
 * 鍵が漏れると任意の利用者の Cookie を偽造できるので、本人だけが読める権限で書く
 * （Linux は 600、Windows は所有者だけの ACL。書き方と理由は {@link OwnerOnlyFiles#writeAtomically(Path, String)}）。
 *
 * <p>空の鍵ファイルは作り直す。以前の作りでは作成と書き込みの間で落ちると空のファイルが残り、
 * {@code TokenBasedRememberMeServices} が空の鍵を拒むので起動できなくなった。
 */
@Slf4j
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
                String existing = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (!existing.isEmpty()) {
                    return existing;
                }
                log.warn("remember-me の鍵ファイルが空なので作り直します: {}", file);
            }
            byte[] bytes = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(bytes);
            String key = Base64.getEncoder().encodeToString(bytes);
            OwnerOnlyFiles.writeAtomically(file, key);
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("remember-me の鍵ファイルを読み書きできません: " + file, e);
        }
    }
}
