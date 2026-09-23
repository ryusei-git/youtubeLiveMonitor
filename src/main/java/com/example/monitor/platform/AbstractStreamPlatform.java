package com.example.monitor.platform;

import com.example.monitor.dto.LiveStreamDetection;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * {@link StreamPlatform} の共通処理をまとめた基底クラス。新しいプラットフォームはこれを継承する。
 *
 * <h2>このクラスが存在する理由</h2>
 * 単なるコードの共有ではなく、<b>このアプリで最も重要な不変条件を、実装者が知らなくても
 * 守らせるため</b>にある。
 *
 * <p>配信状態の判定では「配信していない」と「判定できなかった」を必ず区別しなければならない。
 * 両者を同じ結果にまとめると、YouTube 側の仕様変更などで検知が壊れていても
 * アプリは「誰も配信していない」平常運転に見えてしまう（実際に設計を見直した経緯がある）。
 *
 * <p>この規約はプラットフォームが増えるほど守り漏れやすい。そこで
 * {@link #safeDetect(String, Supplier)} を通す形にしておき、
 * 例外が出た場合は自動的に「判定できなかった」へ倒す。
 */
@Slf4j
public abstract class AbstractStreamPlatform implements StreamPlatform {

    /**
     * 判定処理を包んで、例外が出た場合に「判定できなかった」として返す。
     *
     * <p><b>ここで {@link LiveStreamDetection#notLive()} を返してはならない。</b>
     * 判定できなかったものを「配信していない」と記録すると、検知の故障が
     * 画面上は平常運転に見えるサイレント故障になる。
     *
     * @param channelId ログに出すチャンネル識別子
     * @param detection 実際の判定処理
     * @return 判定結果。例外が出た場合は {@link LiveStreamDetection#failed()}
     */
    protected LiveStreamDetection safeDetect(String channelId, Supplier<LiveStreamDetection> detection) {
        try {
            return detection.get();
        } catch (RuntimeException e) {
            log.error("配信状態の判定に失敗しました: platform={}, channel={}",
                    platform().displayName(), channelId, e);
            return LiveStreamDetection.failed();
        }
    }
}
