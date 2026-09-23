package com.example.monitor.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link Platform} から対応する {@link StreamPlatform} の実装を引くための窓口。
 *
 * <p>実装クラスは Spring が集めるため、<b>新しいプラットフォームを足すときに
 * ここへ書き足す必要はない</b>（{@code @Component} を付けた実装を作るだけでよい）。
 * 登録用の一覧を持たせると「クラスを消したのに一覧に残っていて起動時に落ちる」
 * という消し忘れが起きるため、意図的に自動収集にしている。
 */
@Component
@Slf4j
public class StreamPlatformRegistry {

    private final Map<Platform, StreamPlatform> platformsByType = new EnumMap<>(Platform.class);

    /**
     * @param platforms Spring が集めた {@link StreamPlatform} の実装すべて
     * @throws IllegalStateException 同じ {@link Platform} を担当する実装が複数ある場合
     */
    public StreamPlatformRegistry(List<StreamPlatform> platforms) {
        for (StreamPlatform platform : platforms) {
            StreamPlatform duplicated = platformsByType.put(platform.platform(), platform);
            if (duplicated != null) {
                throw new IllegalStateException(
                        "同じプラットフォームの実装が複数あります: " + platform.platform());
            }
        }
        log.info("対応プラットフォーム: {}", platformsByType.keySet());
    }

    /**
     * プラットフォームに対応する実装を返す。
     *
     * @param platform 対象のプラットフォーム
     * @return 対応する実装
     * @throws IllegalStateException 対応する実装が登録されていない場合
     */
    public StreamPlatform get(Platform platform) {
        StreamPlatform found = platformsByType.get(platform);
        if (found == null) {
            // 列挙子は足したが実装クラスを作っていない、という状態
            throw new IllegalStateException("対応する実装がありません: " + platform);
        }
        return found;
    }

    /**
     * 動画の URL から、それを担当する実装を引く。
     *
     * <p>URL 指定のダウンロードの入口。<b>ここで引けるようにしておくことで、
     * サービス層に {@code url.contains("youtube")} のような分岐を書かずに済む</b>
     * （分岐を書き始めると、プラットフォームが増えるたびにその箇所が膨らむ。
     * {@code MonitoredChannelService.register()} が入力の解釈を各実装へ任せているのと同じ考え方）。
     *
     * <p>どの実装も担当しない URL は利用者の入力誤りなので、
     * 対応しているプラットフォーム名を添えた {@link IllegalArgumentException} にする
     * （{@code GlobalExceptionHandler} が 400 として返す）。
     *
     * @param url 利用者が入力した動画の URL
     * @return その URL を担当する実装
     * @throws IllegalArgumentException どの実装も担当しない URL の場合
     */
    public StreamPlatform findByUrl(String url) {
        return platformsByType.values().stream()
                .filter(platform -> platform.supportsUrl(url))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "対応していないURLです（対応: " + supportedPlatformNames() + "）: " + url));
    }

    /**
     * 対応しているプラットフォームの表示名を並べた文字列を返す。エラーメッセージに添えるために使う。
     *
     * @return 表示名をカンマで並べた文字列
     */
    private String supportedPlatformNames() {
        return platformsByType.keySet().stream()
                .map(Platform::displayName)
                .collect(Collectors.joining("・"));
    }

    /**
     * 登録されている実装をすべて返す。画面の選択肢を作るのに使う。
     *
     * <p>{@link Platform}（列挙子）ではなく実装を返すのは、選択肢に表示名や
     * 「今すぐ使えるか」といった実装しか知らない情報が要るため。
     *
     * @return 対応済みのプラットフォーム実装。{@link Platform} の宣言順
     */
    public List<StreamPlatform> all() {
        return List.copyOf(platformsByType.values());
    }
}
