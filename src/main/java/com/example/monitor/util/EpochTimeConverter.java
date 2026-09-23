package com.example.monitor.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 外部 API が返すエポックミリ秒を、アプリ内で扱う日時に変換する共通処理。
 *
 * <p>アプリ内の日時は {@code LocalDateTime.now()}（システムのタイムゾーン）で
 * 生成されたものに統一している。外部 API から受け取った UTC 基準の値をそのまま
 * {@code LocalDateTime} に詰め替えると、他の日時と 9 時間ずれて混在してしまう
 * （実際にこの変換だけ UTC のままになっていた不具合があった）。
 * 変換の窓口を 1 箇所にまとめることで、同じ間違いを繰り返さないようにしている。
 */
public final class EpochTimeConverter {

    private EpochTimeConverter() {
    }

    /**
     * エポックミリ秒を、システムのタイムゾーンで解釈した日時に変換する。
     *
     * @param epochMillis エポック（1970-01-01T00:00:00Z）からの経過ミリ秒
     * @return システムのタイムゾーンで解釈した日時
     */
    public static LocalDateTime toSystemLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }
}
