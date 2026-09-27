package com.example.monitor.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 本人（このプロセスを動かしている利用者）だけが読めるファイルを書く。
 *
 * <p>本番は Linux だが、作業端末は Windows で、ビルド・テスト・確認用の起動はそこで行う。
 * POSIX の権限（{@code rw-------}）を作成時の初期属性に渡すと、Windows（NTFS）は
 * {@code UnsupportedOperationException} を投げる（#441 の後、Windows ではアプリが起動せず、
 * Spring のコンテキストを作るテストがすべて落ちた）。そこで、POSIX が使えるファイルシステムでは
 * POSIX の権限、そうでなければ ACL で絞り、両方で動かす。
 *
 * <p>秘密のファイルを書く処理が複数の場所で必要になったときに、権限の絞り方や書き方が
 * ずれないよう 1 か所に置く。
 */
@Slf4j
public final class OwnerOnlyFiles {

    private OwnerOnlyFiles() {
    }

    /**
     * 本人だけが読める権限で {@code content} を {@code file} に書く（既にあれば置き換える）。
     *
     * <p>権限は中身を書く前に絞る。POSIX では作る時点で 600 にし、ACL では書く前に絞る。
     * 書いた後に絞ると、その間だけ他人が読める。
     *
     * <p>同じフォルダーの一時ファイルに書いてから {@code ATOMIC_MOVE} で置き換えるのは、
     * 途中で落ちても空のファイルや書きかけのファイルを残さないため
     * （{@code RememberMeKeyFile} は以前、作成と書き込みを別々に行っていたので、
     * その間で落ちると空のファイルが残り、次の起動で失敗する作りだった）。
     * 同じフォルダーに作るのは、別のファイルシステムをまたぐと {@code ATOMIC_MOVE} ができないため。
     *
     * @param file 書き込むファイル。親フォルダーが無ければ作る
     * @param content 書き込む中身（UTF-8 で書く）
     * @throws IOException フォルダーや一時ファイルを作れない、権限を絞れない、書き込めない、置き換えられないとき
     */
    public static void writeAtomically(Path file, String content) throws IOException {
        Path target = file.toAbsolutePath();
        Path dir = target.getParent();
        Files.createDirectories(dir);
        FileStore store = Files.getFileStore(dir);
        boolean posix = store.supportsFileAttributeView(PosixFileAttributeView.class);
        Path tmp = posix
                ? Files.createTempFile(dir, target.getFileName() + ".", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(dir, target.getFileName() + ".", ".tmp");
        try {
            if (!posix) {
                restrictToOwner(tmp, target, store);
            }
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 一時ファイルの ACL を、所有者 1 人だけの許可にする。
     *
     * <p>既存の ACL に足すのではなく置き換えるのは、フォルダーから継承する
     * SYSTEM・Administrators などの許可も外すため。
     *
     * <p>ACL も POSIX も無いファイルシステム（FAT など）では絞れないので、止めずに WARN を出して書く。
     * そのファイルシステムではどのみち誰でも読めるので、起動を止めても守れない。
     * ACL を持てるかはファイルストアで確かめる。Windows の JDK はファイルシステムに関わらず
     * {@link AclFileAttributeView} を返すので、それが {@code null} かどうかでは FAT を見分けられず、
     * 所有者を取るところで例外になって起動が止まる。
     *
     * @param tmp 権限を絞る一時ファイル
     * @param target 最終的に置き換える先（ログに出すため）
     * @param store 一時ファイルを置いたファイルストア（ACL を持てるかを確かめるため）
     * @throws IOException 所有者を取れない、ACL を書き換えられないとき
     */
    private static void restrictToOwner(Path tmp, Path target, FileStore store) throws IOException {
        AclFileAttributeView view = store.supportsFileAttributeView(AclFileAttributeView.class)
                ? Files.getFileAttributeView(tmp, AclFileAttributeView.class)
                : null;
        if (view == null) {
            log.warn("本人だけが読める権限にできないファイルシステムのため、権限を絞らずに書きます: {}", target);
            return;
        }
        AclEntry ownerOnly = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(view.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        view.setAcl(List.of(ownerOnly));
    }
}
