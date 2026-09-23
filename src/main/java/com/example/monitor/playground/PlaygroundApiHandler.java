package com.example.monitor.playground;

import java.io.IOException;
import java.util.Map;

/**
 * お試し実行できる API 1件分の実装。
 *
 * <p><b>これを実装したクラスに {@code @Component} を付けるだけで画面に現れる。</b>
 * Spring がこのインターフェースの実装をすべて集めて
 * {@link ApiPlaygroundService} に渡すため、登録用の一覧は用意していない
 * （一覧があると「クラスを消したのに一覧に残っていて起動時に落ちる」という
 * 消し忘れが起きるため。不要になったらクラスを削除するだけで完結させたい）。
 */
public interface PlaygroundApiHandler {

    /**
     * この API の定義（画面の一覧と入力フォームの元になる）を返す。
     *
     * @return 定義
     */
    ApiDefinition definition();

    /**
     * API を実行して、応答をそのまま返す。
     *
     * <p>戻り値は加工せず、YouTube Data API のレスポンスオブジェクトをそのまま返すこと。
     * 画面には JSON に変換して生のまま表示する（何が返ってくるかを確かめるのが
     * この機能の目的なので、こちらで整形すると本来の形が見えなくなる）。
     *
     * @param parameters 画面で入力された値（キーは {@link ApiParameter#name()}）
     * @return API の応答
     * @throws IOException API の呼び出しに失敗した場合
     */
    Object execute(Map<String, String> parameters) throws IOException;
}
