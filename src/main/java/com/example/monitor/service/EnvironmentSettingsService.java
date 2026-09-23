package com.example.monitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 */
@Service
@Slf4j
public class EnvironmentSettingsService {

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
     * @throws IllegalStateException 書き込みに失敗した場合
     */
    public void updateEnvFile(Map<String, String> updates) {
        List<String> lines = readBaseLines();
        Set<String> remainingKeys = new LinkedHashSet<>(updates.keySet());

        List<String> result = new ArrayList<>(lines.size() + updates.size());
        for (String line : lines) {
            String key = extractKey(line);
            if (key != null && updates.containsKey(key)) {
                result.add(key + "=" + updates.get(key));
                remainingKeys.remove(key);
            } else {
                result.add(line);
            }
        }
        // 元のファイルに無かったキーは末尾に追記する
        for (String key : remainingKeys) {
            result.add(key + "=" + updates.get(key));
        }

        try {
            Files.write(envFile, result, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error(".envファイルの書き込みに失敗しました: {}", envFile, e);
            throw new IllegalStateException(".envファイルの書き込みに失敗しました: " + e.getMessage());
        }
    }

    /**
     * 書き換えの土台となる行を読み込む。
     *
     * @return {@code .env}、無ければ {@code .env.example}、それも無ければ空のリスト
     */
    private List<String> readBaseLines() {
        for (Path candidate : List.of(envFile, envExampleFile)) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return new ArrayList<>(Files.readAllLines(candidate, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    log.warn("既存の設定ファイルの読み込みに失敗したため、新規作成します: {}", candidate, e);
                }
            }
        }
        return new ArrayList<>();
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
