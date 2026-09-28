package com.example.monitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code .env} ファイルへの設定値の書き込みを担当する。
 *
 * <p>このアプリの設定（{@link com.example.monitor.config.MonitorProperties}）は
 * Spring Boot 起動時に一度だけ {@code .env} から読み込まれ、以降は実行中のプロセスへ
 * 反映する手段が無い（{@code @ConfigurationProperties} は再読み込みに対応していない）。
 * そのため、ダッシュボードの「現在の設定」から保存しても即座には効かず、保存後に
 * {@code bin/service.sh restart} が必要になる（このクラス自身は再起動を行わない。
 * サービスを自ら止めて起動し直す処理は、失敗した場合にアプリが完全に止まって
 * 復旧手段が無くなるリスクがあるため、意図的に人手の再起動に留めている）。
 *
 * <p>ファイルは行単位でしか扱わない（{@code KEY=value} 形式の行を書き換える、無ければ末尾に
 * 追記する）。コメント行や無関係な行はそのまま残すため、既存の {@code .env} に書かれた
 * 説明コメントはこの保存によって失われない。
 *
 * <p><b>読めない {@code .env} を作り直さない理由。</b>{@code .env} があるのに読めない
 * （UTF-8 でない文字を含む・読み取り権限が無い等）ときに {@code .env.example} や空のファイルを
 * 土台にして書き込むと、API キー・Webhook URL・{@code ADMIN_PASSWORD}・{@code SPRING_DATASOURCE_PASSWORD}
 * が消え、次の起動で H2 に接続できなくなってアプリが起動しない（{@code bin/backup.sh} は DB しか
 * 控えないので戻せない）。そのため {@code .env.example} を土台にするのは {@code .env} が存在しない
 * ときだけにし、読めなければ保存を断る。書き込みも同じディレクトリの一時ファイルに書いてからの
 * 置き換えにして、ディスク満杯などで途中で失敗しても元の {@code .env} を残す
 * （{@code Files.write} は切り詰めてから書くので、途中で失敗すると空の {@code .env} が残る）。
 *
 * <p><b>値の書き方を制限する理由。</b>{@code .env} は bash（{@code bin/api.sh}・{@code bin/backup.sh}・
 * {@code bin/preview.sh} が {@code set -a; . ./.env} で読み込む）と dotenv-java 3.2.0（アプリの起動時）の
 * 2 通りで読まれ、両方で同じ値にならなければならない。値に改行を入れると画面に無い任意のキーを足せ、
 * {@code $(...)} やバッククォートは bash が読み込んだ時点でコマンドとして実行される。
 * 単一引用符で囲む方法は使えない（dotenv-java 3.2.0 は二重引用符しか外さず、{@code KEY='abc'} は
 * アプリに引用符込みの {@code 'abc'} で渡る）。bash の二重引用符の中で特別な意味を持つのは
 * {@code "}・{@code $}・バッククォート・{@code \}（後ろが {@code $}・バッククォート・{@code "}・
 * {@code \}・改行のとき）だけなので、制御文字・{@code "}・{@code $}・バッククォートを含む値と、
 * {@code \} で終わる値・{@code \\} を含む値を断れば、二重引用符で囲んだ値は bash と dotenv-java で一致する。
 * {@code D:\} のように {@code \} で終わると、閉じる {@code "} が逃がされて bash が「unexpected EOF」で
 * 止まる（{@code D:/} と書けば Java でも同じ場所を指す）。dotenv-java は引用符の無い値の {@code #} 以降を
 * コメントとして捨てる（{@code DOTENV_ENTRY_REGEX} の {@code [^#]*}）ので、{@code #} を含む値も囲む。
 */
@Service
@Slf4j
public class EnvironmentSettingsService {

    /**
     * 引用符なしで書いてよい値。bash でも dotenv-java でも特別な意味を持たない文字だけにしている。
     * {@code bin/health-watch.sh} は {@code DISCORD_WEBHOOK_URL} を sed で取り出して引用符を外さないので、
     * Webhook URL・API キー・数値はこれまでどおり引用符なしで書く。
     */
    private static final Pattern UNQUOTED_SAFE = Pattern.compile("[A-Za-z0-9_@%+=:,./-]*");

    private final Path envFile;
    private final Path envExampleFile;

    /**
     * @param envFilePath        {@code .env} のパス
     * @param envExampleFilePath {@code .env} が存在しない場合に土台として使う {@code .env.example} のパス
     *                           （説明コメントを引き継ぐため、空のファイルから始めるより親切）
     */
    public EnvironmentSettingsService(
            @Value("${monitor.env-file:.env}") String envFilePath,
            @Value("${monitor.env-example-file:.env.example}") String envExampleFilePath) {
        this.envFile = Path.of(envFilePath);
        this.envExampleFile = Path.of(envExampleFilePath);
    }

    /**
     * {@code .env} の内容を、指定したキーだけ書き換えて保存する。
     *
     * <p>{@code .env} が存在しない場合は {@code .env.example} を土台にし、それも無ければ
     * 空のファイルから始める。指定したキーが元のファイルに既にあれば値だけ書き換え、
     * 無ければ末尾に追記する。
     *
     * @param updates 書き換えたいキーと値の組
     * @throws IllegalArgumentException 保存できない文字を含む値がある場合（.env は変えない）
     * @throws IllegalStateException    {@code .env} を読めない・書き込めない場合（.env は変えない）
     */
    public synchronized void updateEnvFile(Map<String, String> updates) {
        // 不正な値があればファイルに触れる前に断る
        Map<String, String> formattedLines = new LinkedHashMap<>();
        for (Map.Entry<String, String> update : updates.entrySet()) {
            formattedLines.put(update.getKey(), formatLine(update.getKey(), update.getValue()));
        }

        List<String> lines = readBaseLines();
        Set<String> remainingKeys = new LinkedHashSet<>(updates.keySet());

        List<String> result = new ArrayList<>(lines.size() + updates.size());
        for (String line : lines) {
            String key = extractKey(line);
            if (key != null && updates.containsKey(key)) {
                result.add(formattedLines.get(key));
                remainingKeys.remove(key);
            } else {
                result.add(line);
            }
        }
        // 元のファイルに無かったキーは末尾に追記する
        for (String key : remainingKeys) {
            result.add(formattedLines.get(key));
        }

        writeAtomically(result);
    }

    /**
     * {@code .env} に書く 1 行を組み立てる。
     *
     * @param key   キー名
     * @param value 値
     * @return {@code KEY=value} または {@code KEY="value"}
     * @throws IllegalArgumentException 保存できない文字を含む場合
     */
    private static String formatLine(String key, String value) {
        boolean unsavable = value.endsWith("\\") || value.contains("\\\\")
                || value.chars().anyMatch(c -> Character.isISOControl(c) || c == '"' || c == '$' || c == '`');
        if (unsavable) {
            // 値そのものは文言に入れない。GlobalExceptionHandler.clientError() が文言を WARN でログに書くため、
            // API キー等がログに残る
            throw new IllegalArgumentException(key + " に保存できない値です。改行などの制御文字・二重引用符・ドル記号・"
                    + "バッククォートを含む値と、バックスラッシュで終わる値・バックスラッシュが 2 つ続く値は保存できません");
        }
        if (UNQUOTED_SAFE.matcher(value).matches()) {
            return key + "=" + value;
        }
        // 空白・#・日本語・~・\（Windows のパス）などを含む値は二重引用符で囲む
        return key + "=\"" + value + "\"";
    }

    /**
     * 書き換えの土台となる行を読み込む。
     *
     * @return {@code .env}、それが存在しなければ {@code .env.example}、それも無ければ空のリスト
     * @throws IllegalStateException {@code .env} が存在するのに読めない場合
     */
    private List<String> readBaseLines() {
        if (Files.exists(envFile)) {
            if (!Files.isRegularFile(envFile)) {
                throw new IllegalStateException(".env が通常のファイルではないため保存を中止しました: " + envFile);
            }
            try {
                return new ArrayList<>(Files.readAllLines(envFile, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(
                        "既存の .env を読めないため保存を中止しました（UTF-8 で保存されているか確かめてください）: " + envFile, e);
            }
        }
        if (Files.isRegularFile(envExampleFile)) {
            try {
                return new ArrayList<>(Files.readAllLines(envExampleFile, StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.warn(".env.example を読めないため、空のファイルから作ります: {}", envExampleFile, e);
            }
        }
        return new ArrayList<>();
    }

    /**
     * 同じディレクトリの一時ファイルに書いてから置き換える。途中で失敗しても元の {@code .env} が残る。
     *
     * @param lines 書き込む行
     * @throws IllegalStateException 書き込み・置き換えに失敗した場合
     */
    private void writeAtomically(List<String> lines) {
        Path temp = null;
        try {
            // .env が別の場所へのシンボリックリンクでも、リンクを普通のファイルで置き換えずに実体を書き換える
            Path target = Files.exists(envFile) ? envFile.toRealPath() : envFile.toAbsolutePath();
            // ATOMIC_MOVE は同じファイルシステムの中でしか効かないので、同じディレクトリに作る
            temp = Files.createTempFile(target.getParent(), ".env-", ".tmp");
            Files.write(temp, lines, StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException deleteFailure) {
                    log.warn(".env の一時ファイルを消せませんでした: {}", temp, deleteFailure);
                }
            }
            throw new IllegalStateException(".envファイルの書き込みに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * 行が {@code KEY=value} 形式ならキー部分を取り出す。コメント行・空行・不正な行は {@code null}。
     *
     * @param line 対象の行
     * @return キー名。該当しなければ {@code null}
     */
    private String extractKey(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }
        int equalsIndex = trimmed.indexOf('=');
        return equalsIndex < 0 ? null : trimmed.substring(0, equalsIndex).strip();
    }
}
