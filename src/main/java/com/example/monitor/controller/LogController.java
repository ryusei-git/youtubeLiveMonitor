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
     * @param limit     取得する最大件数
     * @param level     このレベルの行だけに絞り込む。省略すると絞り込まない
     * @return 解析済みのログと、そのログに実在するレベルの一覧
     */
    @GetMapping("/channels/{channelId}")
    public LogViewResponse getChannelLog(
            @PathVariable String channelId,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String level) {
        return channelLogReader.readChannelLog(channelId, limit, level);
    }

    /**
     * 特定のチャンネルに紐づかないシステムログを新しい方から取得する。
     *
     * @param limit 取得する最大件数
     * @param level このレベルの行だけに絞り込む。省略すると絞り込まない
     * @return 解析済みのログと、そのログに実在するレベルの一覧
     */
    @GetMapping("/system")
    public LogViewResponse getSystemLog(
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String level) {
        return channelLogReader.readSystemLog(limit, level);
    }
}
