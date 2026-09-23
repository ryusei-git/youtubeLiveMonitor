package com.example.monitor.platform;

/**
 * 監視できる配信プラットフォームの種類。
 *
 * <p>{@link com.example.monitor.entity.MonitoredChannel} に保存され、そのチャンネルを
 * どの {@link StreamPlatform} の実装で扱うかを決める。
 *
 * <p><b>列挙子の名前は DB に文字列として保存される</b>（{@code @Enumerated(EnumType.STRING)}）。
 * 既存データとの互換が壊れるため、一度使い始めた名前は変更しないこと。
 */
public enum Platform {

    /** YouTube。このアプリが最初から対応しているプラットフォーム。 */
    YOUTUBE("YouTube"),

    /** Twitch。 */
    TWITCH("Twitch");

    private final String displayName;

    Platform(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 画面やログに出す表示名を返す。
     *
     * @return 表示名
     */
    public String displayName() {
        return displayName;
    }
}
