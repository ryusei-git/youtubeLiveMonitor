package com.example.monitor.dto;

import com.example.monitor.platform.Platform;
import com.example.monitor.platform.StreamPlatform;

/**
 * 登録画面のプラットフォーム選択肢 1 件分。
 *
 * <p>選択肢を画面側に決め打ちで書くと、プラットフォームを増やしたときに画面の修正を
 * 忘れて「増やしたのに選べない」状態になる。サーバーが持っている一覧をそのまま返す。
 *
 * @param name      プログラムが扱う名前（{@code YOUTUBE} / {@code TWITCH}）。登録時にそのまま送り返す
 * @param label     画面に出す表示名
 * @param available 今すぐ使える状態か。認証情報が未設定なら {@code false}
 * @param inputHint 入力欄に出す説明。受け付ける形式がプラットフォームごとに違うため
 */
public record PlatformResponse(
        Platform name,
        String label,
        boolean available,
        String inputHint
) {

    /**
     * プラットフォーム実装から選択肢を組み立てる。
     *
     * @param streamPlatform 対象のプラットフォーム実装
     * @return 変換後のレスポンス
     */
    public static PlatformResponse from(StreamPlatform streamPlatform) {
        return new PlatformResponse(
                streamPlatform.platform(),
                streamPlatform.platform().displayName(),
                streamPlatform.isAvailable(),
                streamPlatform.inputHint());
    }
}
