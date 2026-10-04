package com.example.monitor.cli;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.AppUser.Role;
import com.example.monitor.entity.AuditAction;
import com.example.monitor.entity.AuditOutcome;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.service.AuditLogger;
import com.example.monitor.util.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code set-password} コマンド。利用者（主に管理者）のパスワードを、サーバーの端末から決め直す。
 *
 * <p>実行例（入力を求められるので、新しいパスワードを打つ）:
 * <pre>{@code
 * java -jar app.jar set-password -u admin -p
 * }</pre>
 *
 * <h2>なぜ CLI に置くか</h2>
 * 管理者がパスワードを忘れると、画面からは戻せない。{@code .env} の {@code ADMIN_PASSWORD} は、管理者が
 * 1 人もいないときの初回の作成にしか使わない（{@link com.example.monitor.security.AdminUserInitializer}）。
 * 再設定用のリンクは管理者には発行できず、DB 管理画面はログイン利用者のテーブルを扱わない。
 * このコマンドが無いと、H2 Shell で BCrypt のハッシュを手で作って差し替えるしかない。
 *
 * <h2>今のパスワードを聞かない・権限で絞らない理由</h2>
 * このコマンドを実行できるのは、サーバーの端末に入れる人だけ。その人は DB のファイルと {@code .env} を
 * 直接読み書きできるので、画面の変更（{@code PasswordChangeService}）のように今のパスワードで本人を確かめても
 * 守りにならない。
 *
 * <h2>パスワードを引数で受け取らない理由</h2>
 * 引数に書くと、シェルの履歴とプロセスの一覧（{@code ps}）に平文で残る。{@code -p} は値を取らず、実行後に
 * 入力させる（端末なら画面に出ない）。端末でないとき（パイプなど）は、picocli が標準入力から 1 行読む。
 *
 * <h2>エンコーダーを Bean から受け取らない理由</h2>
 * {@link PasswordEncoder} の Bean は、Web 専用の {@code SecurityConfig}（{@code @Profile("!cli")}）が作るので、
 * CLI には無い。ここで Bean を要求すると、このコマンドを実行したときに依存を解決できずに失敗する
 * （{@code docs/pitfalls.md}「{@code cli} プロファイルで作られない Bean に依存するコントローラーには
 * {@code @Profile("!cli")} を付ける」と同じ種類の事故）。
 * そこで {@code SecurityConfig#passwordEncoder()} と同じ設定（BCrypt・既定の強さ）をここで作る。
 * BCrypt のハッシュは強さを自分の中に持つので、Web 側の強さを変えても、ここで作ったハッシュは照合できる。
 * 方式そのもの（BCrypt 以外）に変えるときは、ここも合わせて変えること。
 *
 * <h2>監査ログ</h2>
 * {@link AuditAction#PASSWORD_CHANGE} で残す。操作者と IP は空欄（CLI にはログインも HTTP の要求も無い）にし、
 * 詳細に「CLI（set-password）」（失敗なら、その後ろに理由）と書いて、画面からの変更と見分ける。
 */
@Component
@Command(
        name = "set-password",
        mixinStandardHelpOptions = true,
        description = "利用者のパスワードを決め直す（管理者がパスワードを忘れたとき用）")
@RequiredArgsConstructor
public class SetPasswordCommand implements Callable<Integer> {

    /** {@code SecurityConfig#passwordEncoder()} と同じ設定。Bean から受け取らない理由はクラスの説明を参照。 */
    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    /** 監査ログの対象の種類。{@code PasswordChangeService} と揃える。 */
    private static final String AUDIT_TARGET_TYPE = "USER";

    /** 監査ログの詳細。画面からの変更と見分けるために書く（失敗なら、その後ろに理由を足す）。 */
    private static final String AUDIT_DETAIL = "CLI（set-password）";

    private final AppUserRepository appUserRepository;
    private final AuditLogger auditLogger;

    @Option(names = {"-u", "--user"}, required = true,
            description = "パスワードを決め直す利用者のログインID")
    private String username;

    // arity = "0" は省けない。interactive の暗黙の arity のままだと、picocli 4.7.6 は -p の後ろに離して書いた値は
    // 拒むが、「--password=値」「-p=値」と = でつないだ値は受け取り、入力を求めずにその値でパスワードを変えてしまう
    // （レビューで確かめた）。明示すると、どちらも「値を付けずに指定する」の引数エラーになる。
    @Option(names = {"-p", "--password"}, required = true, interactive = true, arity = "0",
            prompt = "新しいパスワード: ",
            description = "新しいパスワード。値は書かず、実行後に入力する（端末なら画面に出ない）")
    private char[] password;

    /**
     * パスワードを決め直す。
     *
     * <p>変更時刻も書くので、その利用者のログイン中のセッションは次の要求で失効し、
     * 「ログインしたままにする」の Cookie もパスワードのハッシュが変わるので使えなくなる（{@code SecurityConfig} の説明を参照）。
     *
     * @return 成功なら 0。利用者が見つからない、または新しいパスワードが要件を満たさない場合は 1
     */
    @Override
    public Integer call() {
        String newPassword = new String(password);
        Arrays.fill(password, '\0');

        Optional<AppUser> found = appUserRepository.findByUsername(username);
        if (found.isEmpty()) {
            System.err.println("利用者が見つかりません: " + username);
            System.err.println(adminNamesHint());
            return 1;
        }
        AppUser user = found.get();

        String hash;
        try {
            PasswordPolicy.validate(user.getRole(), newPassword);
            hash = PASSWORD_ENCODER.encode(newPassword);
        } catch (IllegalArgumentException e) {
            auditLogger.record(AuditAction.PASSWORD_CHANGE, AuditOutcome.FAILURE, null, null, null,
                    AUDIT_TARGET_TYPE, user.getUsername(), AUDIT_DETAIL + ": " + e.getMessage());
            System.err.println(e.getMessage());
            return 1;
        }

        // PasswordChangeService と同じくミリ秒に切り詰める（H2 の TIMESTAMP の丸めで値がずれないように）
        LocalDateTime changedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        if (appUserRepository.updatePassword(user.getId(), hash, changedAt) != 1) {
            System.err.println("利用者が見つかりません（実行中に削除された可能性があります）: " + username);
            return 1;
        }
        auditLogger.record(AuditAction.PASSWORD_CHANGE, AuditOutcome.SUCCESS, null, null, null,
                AUDIT_TARGET_TYPE, user.getUsername(), AUDIT_DETAIL);

        System.out.println("パスワードを変更しました: " + user.getUsername()
                + (user.getRole() == Role.ADMIN ? "（管理者）" : "（一般利用者）"));
        System.out.println("この利用者のログイン中の画面と「ログインしたままにする」は、すべての端末で無効になりました。");
        if (!user.isEnabled()) {
            System.out.println("注意: この利用者は無効化されています。ログインするには、管理画面の「利用者管理」で有効に戻してください。");
        }
        if (user.getRole() == Role.ADMIN) {
            System.out.println("bin/api.sh は .env の ADMIN_USERNAME / ADMIN_PASSWORD でログインします。"
                    + "この管理者を使っているなら、.env の ADMIN_PASSWORD も同じ値に書き換えてください（再起動は不要）。");
        }
        return 0;
    }

    /**
     * 利用者が見つからなかったときに、管理者のログイン ID を案内する文言を返す。
     *
     * <p>パスワードと一緒にログイン ID も忘れた管理者が、DB を覗かずに済むようにするため。
     * 端末に入れる人は DB を直接読めるので、ログイン ID を見せても新しく漏れるものは無い。
     *
     * @return 管理者のログイン ID の一覧、または管理者が 1 人もいないときの案内
     */
    private String adminNamesHint() {
        String admins = appUserRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.ADMIN)
                .map(AppUser::getUsername)
                .sorted()
                .collect(Collectors.joining(", "));
        return admins.isEmpty()
                ? "管理者が1人もいません。.env の ADMIN_USERNAME / ADMIN_PASSWORD を設定してサービスを起動すると作られます。"
                : "管理者のログインID: " + admins;
    }
}
