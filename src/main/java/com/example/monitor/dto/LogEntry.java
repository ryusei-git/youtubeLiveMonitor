package com.example.monitor.dto;

/**
 * ログファイル 1 行分を項目ごとに分解したもの。
 *
 * <p>画面側で「ERROR だけ絞り込む」「時刻で並べ替える」といった扱いができるよう、
 * 生のテキストではなく項目に分けた形で返す。
 *
 * <p>スタックトレースのようにパターンに当てはまらない行は、直前の行の
 * {@link #message} に改行付きで連結される。その場合 {@link #timestamp} などは
 * 連結先の行の値をそのまま引き継ぐ。
 *
 * @param timestamp  出力時刻（{@code yyyy-MM-dd HH:mm:ss} 形式）。解析できなかった行では {@code null}
 * @param level      ログレベル（{@code INFO}、{@code WARN} など）。解析できなかった行では {@code null}
 * @param loggerName 出力元クラス名（短縮形）。解析できなかった行では {@code null}
 * @param message    本文。スタックトレースを含む場合は複数行になる
 */
public record LogEntry(
        String timestamp,
        String level,
        String loggerName,
        String message
) {}
