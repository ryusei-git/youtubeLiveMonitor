package com.example.monitor.controller;

import com.example.monitor.dto.ChannelRegistrationRequest;
import com.example.monitor.dto.ChannelSearchResult;
import com.example.monitor.dto.MonitoredChannelResponse;
import com.example.monitor.dto.RecordTitleFilterRequest;
import com.example.monitor.dto.RecordToggleRequest;
import com.example.monitor.dto.UpcomingStreamResponse;
import com.example.monitor.entity.MonitoredChannel;
import com.example.monitor.service.MonitoredChannelService;
import com.example.monitor.service.YouTubeApiClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 監視対象チャンネルを操作する REST API。
 *
 * <p>同じ操作は CLI（{@code java -jar app.jar channel ...}）からも行える。
 * どちらも {@link MonitoredChannelService} を経由するため、動作は一致する。
 */
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class MonitoredChannelController {

    private final MonitoredChannelService monitoredChannelService;
    private final YouTubeApiClient youTubeApiClient;

    /**
     * 監視対象チャンネルの一覧を返す。
     *
     * @return 登録済みチャンネルの一覧
     */
    @GetMapping
    public List<MonitoredChannelResponse> listChannels() {
        Map<Long, Long> recordingCounts = monitoredChannelService.countPlayableRecordingsByChannel();
        Map<Long, Long> subscriberCounts = monitoredChannelService.countSubscribersByChannel();
        return monitoredChannelService.findAll().stream()
                .map(channel -> MonitoredChannelResponse.from(
                        channel,
                        recordingCounts.getOrDefault(channel.getId(), 0L),
                        subscriberCounts.getOrDefault(channel.getId(), 0L)))
                .toList();
    }

    /**
     * 開始予定時刻の早い順に配信予定の一覧を返す。
     *
     * <p>どの予定を返すか（直近 7 日以内など）は {@link UpcomingStreamResponse#listWithinWindow} が決める。
     * 利用者向けの {@code GET /api/my/upcoming} と選び方をそろえるため。
     *
     * @return 配信予定の一覧
     */
    @GetMapping("/upcoming")
    public List<UpcomingStreamResponse> listUpcomingStreams() {
        return UpcomingStreamResponse.listWithinWindow(monitoredChannelService.findAll());
    }

    /**
     * チャンネルを監視対象に追加する。
     *
     * <p>チャンネル ID が分かっている場合の通常の登録手段。YouTube への問い合わせを伴わないため
     * クォータを消費しない。
     *
     * @param request 登録するチャンネルの情報
     * @return 登録された監視対象（HTTP 201）
     */
    @PostMapping
    public ResponseEntity<MonitoredChannelResponse> addChannel(@Valid @RequestBody ChannelRegistrationRequest request) {
        MonitoredChannel registered = monitoredChannelService.register(
                request.platformOrDefault(), request.youtubeChannelId(), request.channelName(),
                request.recordEnabled(), request.recordTitleKeywords());
        return ResponseEntity.status(HttpStatus.CREATED).body(MonitoredChannelResponse.from(registered));
    }

    /**
     * チャンネルの自動録画 ON/OFF を切り替える。
     *
     * @param id      監視対象の主キー
     * @param request 切り替え内容
     * @return 本文なしの HTTP 204
     */
    @PutMapping("/{id}/record")
    public ResponseEntity<Void> setRecordEnabled(@PathVariable Long id, @RequestBody RecordToggleRequest request) {
        monitoredChannelService.setRecordEnabled(id, request.enabled());
        return ResponseEntity.noContent().build();
    }

    /**
     * チャンネルの録画タイトルフィルターを更新する。
     *
     * @param id      監視対象の主キー
     * @param request 絞り込みキーワード
     * @return 本文なしの HTTP 204
     */
    @PutMapping("/{id}/record-title-filter")
    public ResponseEntity<Void> setRecordTitleKeywords(@PathVariable Long id,
                                                        @RequestBody RecordTitleFilterRequest request) {
        monitoredChannelService.setRecordTitleKeywords(id, request.titleKeywords());
        return ResponseEntity.noContent().build();
    }

    /**
     * 監視対象からチャンネルを削除する。紐づく通知履歴も一緒に削除される。
     *
     * @param id 監視対象の主キー（一覧取得の {@code id}。YouTube のチャンネル ID ではない）
     * @return 本文なしの HTTP 204
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> removeChannel(@PathVariable Long id) {
        monitoredChannelService.remove(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * チャンネル名からチャンネルを検索する。<b>YouTube 専用。</b>
     *
     * <p>チャンネル ID が分からないときだけ使うこと。
     * 1 回あたり 100 クォータを消費するため、定期的に呼ぶ用途には向かない。
     *
     * <p>Twitch には用意していない。Twitch の登録に必要なのは URL に現れるログイン名だけで、
     * 利用者はチャンネルページを開けば必ず目にしているため、検索する必要がないため。
     *
     * @param name 検索したいチャンネル名（部分一致）
     * @return 見つかった候補
     */
    @GetMapping("/search")
    public List<ChannelSearchResult> searchChannelsByName(@RequestParam String name) {
        return youTubeApiClient.searchChannelsByName(name);
    }
}
