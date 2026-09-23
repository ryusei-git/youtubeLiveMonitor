package com.example.monitor.playground;

import com.google.api.client.json.gson.GsonFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * お試し実行の受け付けと、消費クォータの集計を担当する。
 *
 * <h2>クォータを数えている理由</h2>
 * この画面は監視・通知と<b>同じ API キーを使う</b>。YouTube Data API の 1 日あたりの上限は
 * 既定 10,000 で、{@code search.list} は 1 回 100 消費する。試しているうちに上限へ達すると、
 * 本来の機能である配信検知後の詳細取得（{@code videos.list}）まで失敗するようになる。
 * 正確な残量は Google 側しか知らないが、<b>この画面でどれだけ使ったか</b>を出しておけば
 * 「試しすぎ」に気づける。
 */
@Service
@Slf4j
public class ApiPlaygroundService {

    /** 実行できる API。{@link PlaygroundApiHandler} の実装を Spring が集めたもの。 */
    private final Map<String, PlaygroundApiHandler> handlersById;

    /** 応答を JSON 文字列に変換するための変換器。{@code YouTubeApiConfig} と同じ実装を使う。 */
    private final GsonFactory jsonFactory = new GsonFactory();

    /**
     * エラーメッセージに紛れ込む API キーを伏せるための正規表現。
     *
     * <p>YouTube Data API は呼び出しに失敗すると、エラー本文に<b>リクエストURLをそのまま含める</b>。
     * このアプリは API キーをクエリパラメータで渡しているため、その URL には
     * {@code key=（APIキー）} が平文で入っている（実際に発生した）。
     * そのまま画面やログへ出すと、設定画面では values を一切返さないようにしている配慮
     * （{@code SettingsResponse} 参照）が台無しになるので、必ずここを通してから外へ出す。
     */
    private static final Pattern API_KEY_IN_URL = Pattern.compile("([?&]key=)[^&\\s\"]+");

    /** API キーを伏せた後に表示する文字列。 */
    private static final String REDACTED = "$1***";

    /** アプリ起動後にこの画面から消費したクォータの累計。再起動すると 0 に戻る。 */
    private final AtomicInteger sessionQuotaTotal = new AtomicInteger();

    /**
     * @param handlers Spring が集めた {@link PlaygroundApiHandler} の実装すべて
     */
    public ApiPlaygroundService(List<PlaygroundApiHandler> handlers) {
        this.handlersById = handlers.stream()
                .collect(java.util.stream.Collectors.toMap(
                        handler -> handler.definition().id(),
                        handler -> handler));
        log.info("APIお試し機能を有効にしました: {}件", handlersById.size());
    }

    /**
     * 実行できる API の定義を、クォータの安い順に返す。
     *
     * <p>安い順に並べるのは、うっかり高コストな {@code search.list} を先頭で
     * 選んでしまわないようにするため。
     *
     * @return API の定義一覧
     */
    public List<ApiDefinition> listApis() {
        return handlersById.values().stream()
                .map(PlaygroundApiHandler::definition)
                .sorted(Comparator.comparingInt(ApiDefinition::quotaCost)
                        .thenComparing(ApiDefinition::id))
                .toList();
    }

    /**
     * 現在のクォータ消費累計を返す。
     *
     * @return アプリ起動後にこの画面から消費したクォータ
     */
    public int sessionQuotaTotal() {
        return sessionQuotaTotal.get();
    }

    /**
     * API を実行する。
     *
     * <p>API 呼び出しが失敗しても例外は投げず、失敗として結果に詰めて返す
     * （理由は {@link ApiExecutionResult} の JavaDoc 参照）。
     *
     * <p><b>クォータは呼び出しが失敗しても消費したものとして数える。</b>
     * リクエストが Google に届いた時点で消費されるため、手元で成否を見て
     * 数え方を変えると実態から離れてしまう。
     *
     * @param request 実行要求
     * @return 実行結果
     * @throws IllegalArgumentException 指定された API が存在しない、または必須項目が空の場合
     */
    public ApiExecutionResult execute(ApiExecutionRequest request) {
        PlaygroundApiHandler handler = handlersById.get(request.apiId());
        if (handler == null) {
            throw new IllegalArgumentException("指定された API は存在しません: " + request.apiId());
        }

        ApiDefinition definition = handler.definition();
        Map<String, String> parameters =
                request.parameters() == null ? Map.of() : request.parameters();
        validateRequiredParameters(definition, parameters);

        int quotaCost = definition.quotaCost();
        int total = sessionQuotaTotal.addAndGet(quotaCost);

        long startedAt = System.nanoTime();
        try {
            Object response = handler.execute(parameters);
            long elapsed = elapsedMillis(startedAt);
            log.info("APIお試し実行: api={}, quota={}, 累計={}, {}ms",
                    definition.id(), quotaCost, total, elapsed);
            return new ApiExecutionResult(
                    true, jsonFactory.toPrettyString(response), null, quotaCost, total, elapsed);

        } catch (IOException | RuntimeException e) {
            long elapsed = elapsedMillis(startedAt);
            String safeMessage = redactApiKey(e.getMessage());
            // 例外オブジェクトごと渡すとスタックトレースに元のメッセージ（＝APIキー入りURL）が
            // そのまま出てしまうため、伏せ字にした文字列だけを記録する
            log.warn("APIお試し実行に失敗しました: api={}, quota={}, 累計={}, 理由={}",
                    definition.id(), quotaCost, total, safeMessage);
            return new ApiExecutionResult(
                    false, null, safeMessage, quotaCost, total, elapsed);
        }
    }

    /**
     * エラーメッセージに含まれる API キーを伏せ字にする。
     *
     * @param message 元のメッセージ
     * @return API キーを伏せたメッセージ。{@code null} が渡された場合は既定の文言
     */
    private String redactApiKey(String message) {
        if (message == null) {
            return "エラーの詳細を取得できませんでした";
        }
        return API_KEY_IN_URL.matcher(message).replaceAll(REDACTED);
    }

    /**
     * 必須の入力欄が埋まっているか確かめる。
     *
     * <p>空のまま API を呼ぶとクォータだけ消費して必ず失敗するため、送る前に弾く。
     *
     * @param definition 実行する API の定義
     * @param parameters 入力された値
     * @throws IllegalArgumentException 必須項目が空の場合
     */
    private void validateRequiredParameters(ApiDefinition definition, Map<String, String> parameters) {
        for (ApiParameter parameter : definition.parameters()) {
            if (!parameter.required()) {
                continue;
            }
            String value = parameters.get(parameter.name());
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("必須項目が入力されていません: " + parameter.label());
            }
        }
    }

    /**
     * 開始時刻からの経過ミリ秒を求める。
     *
     * @param startedAtNanos {@link System#nanoTime()} で取った開始時刻
     * @return 経過ミリ秒
     */
    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
