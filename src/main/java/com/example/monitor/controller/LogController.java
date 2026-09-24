package com.example.monitor.controller;

import com.example.monitor.dto.LogViewResponse;
import com.example.monitor.service.ChannelLogReader;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ログを画面から参照するための REST API。
 *
 * <p>ファイルの中身をそのまま返すのではなく、時刻・レベル・出力元・本文に分解して返す。
 * 画面側で絞り込みや色分けができるようにするため。
 */
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {

    /**
     * 1 回に返す件数の上限。画面の入力欄（{@code logs.html} の {@code max="2000"}）と合わせている。
     *
     * <p>画面の {@code max} はブラウザでの入力チェックでしかなく、API を直接呼べば素通りする。
     * 上限が無いと、大きな値 1 つでファイル全体を手元に持つことになり、
     * 末尾の件数だけを持って読む {@link ChannelLogReader} の作りが意味を失う。
     */
    static final int MAX_LIMIT = 2000;

    private final ChannelLogReader channelLogReader;

    /**
     * ログファイルが存在するチャンネル ID の一覧を返す。画面のチャンネル選択に使う。
     *
     * @return チャンネル ID の一覧
     */
    @GetMapping("/channels")
    public List<String> listChannelsWithLogs() {
        return channelLogReader.listChannelsWithLogs();
    }

    /**
     * 特定チャンネルのログを新しい方から取得する。
     *
     * @param channelId YouTube のチャンネル ID
     * @param limit     取得する最大件数（1〜{@value #MAX_LIMIT}）
     * @param level     このレベルの行だけに絞り込む。省略すると絞り込まない
     * @return 解析済みのログと、そのログに実在するレベルの一覧
     * @throws IllegalArgumentException 件数の指定が範囲外の場合（400）
     */
    @GetMapping("/channels/{channelId}")
    public LogViewResponse getChannelLog(
            @PathVariable String channelId,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String level) {
        requireLimitInRange(limit);
        return channelLogReader.readChannelLog(channelId, limit, level);
    }

    /**
     * 特定のチャンネルに紐づかないシステムログを新しい方から取得する。
     *
     * @param limit 取得する最大件数（1〜{@value #MAX_LIMIT}）
     * @param level このレベルの行だけに絞り込む。省略すると絞り込まない
     * @return 解析済みのログと、そのログに実在するレベルの一覧
     * @throws IllegalArgumentException 件数の指定が範囲外の場合（400）
     */
    @GetMapping("/system")
    public LogViewResponse getSystemLog(
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String level) {
        requireLimitInRange(limit);
        return channelLogReader.readSystemLog(limit, level);
    }

    /**
     * 件数の指定が 1〜{@value #MAX_LIMIT} に収まっているか確かめる。
     *
     * <p>範囲外は {@link IllegalArgumentException} にして、ほかの API の入力チェックと同じく
     * {@code GlobalExceptionHandler} に 400 で返させる。
     *
     * @param limit 取得する最大件数
     */
    private static void requireLimitInRange(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("表示件数は1〜" + MAX_LIMIT + "で指定してください");
        }
    }
}
