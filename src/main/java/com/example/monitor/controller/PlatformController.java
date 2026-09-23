package com.example.monitor.controller;

import com.example.monitor.dto.PlatformResponse;
import com.example.monitor.platform.StreamPlatformRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 対応している配信プラットフォームの一覧を返す API。
 *
 * <p>登録画面のプラットフォーム選択肢を作るために使う。<b>選択肢を画面側に決め打ちしない</b>
 * ための窓口で、ログ画面のレベル絞り込みと同じ考え方（存在するものだけを選ばせる）。
 * プラットフォームを増やしても画面の修正は要らない。
 */
@RestController
@RequestMapping("/api/platforms")
@RequiredArgsConstructor
public class PlatformController {

    private final StreamPlatformRegistry streamPlatformRegistry;

    /**
     * 対応しているプラットフォームを返す。
     *
     * @return プラットフォームの一覧
     */
    @GetMapping
    public List<PlatformResponse> listPlatforms() {
        return streamPlatformRegistry.all().stream()
                .map(PlatformResponse::from)
                .toList();
    }
}
