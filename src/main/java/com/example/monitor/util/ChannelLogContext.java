package com.example.monitor.util;

import org.slf4j.MDC;

import java.util.function.Supplier;

/**
 * 「今どのチャンネルを処理しているか」を MDC に載せ、ログをチャンネル別ファイルへ振り分けるための共通処理。
 *
 * <h2>なぜ独立クラスに切り出したのか</h2>
 * {@code logback-spring.xml} の SiftingAppender は MDC の {@code channelId} をキーに
 * {@code logs/channels/{チャンネルID}.log} へ出力先を切り替える。つまり<b>この印が立っていない間に
 * 出たログは、下位クラスが出したものも含めてチャンネル別ログに残らない</b>。
 *
 * <p>以前はこの印を監視ループ（{@code LiveStreamPollingScheduler}）だけが立てていればよかったが、
 * 配信状態の問い合わせを「プラットフォームごとにまとめて」行うようにした結果、
 * <b>1 件ずつ問い合わせる側（{@link com.example.monitor.platform.StreamPlatform#detectLiveStreams}
 * の既定実装）でも同じ印が要る</b>ようになった。キーを各クラスの private 定数のままにすると
 * 文字列が複製され、片方だけ変えたときに「そのチャンネルのログだけ消える」形で静かに壊れる。
 *
 * <h2>前の値を退避してから戻す理由</h2>
 * 呼び出しが入れ子になっても壊れないようにするため。単純に {@code MDC.remove} で終えると、
 * 内側の処理が終わった時点で外側が立てていた印まで消え、<b>以降のログが
 * チャンネル別ログから外れる</b>。呼び出し関係を意識せずに使えることを優先した。
 */
public final class ChannelLogContext {

    /** Logback の SiftingAppender がログの振り分け先を決めるために参照する MDC のキー。 */
    private static final String MDC_CHANNEL_ID_KEY = "channelId";

    private ChannelLogContext() {
    }

    /**
     * 指定したチャンネルのログとして扱う範囲で処理を実行し、その結果を返す。
     *
     * @param channelId 処理対象のチャンネル識別子
     * @param action    実行する処理
     * @param <T>       処理の戻り値の型
     * @return 処理の戻り値
     */
    public static <T> T callWithChannel(String channelId, Supplier<T> action) {
        String previous = MDC.get(MDC_CHANNEL_ID_KEY);
        MDC.put(MDC_CHANNEL_ID_KEY, channelId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                MDC.remove(MDC_CHANNEL_ID_KEY);
            } else {
                MDC.put(MDC_CHANNEL_ID_KEY, previous);
            }
        }
    }

    /**
     * 指定したチャンネルのログとして扱う範囲で処理を実行する。戻り値が要らない場合はこちらを使う。
     *
     * @param channelId 処理対象のチャンネル識別子
     * @param action    実行する処理
     */
    public static void runWithChannel(String channelId, Runnable action) {
        callWithChannel(channelId, () -> {
            action.run();
            return null;
        });
    }
}
