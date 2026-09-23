package com.example.monitor.service;
import com.example.monitor.entity.VideoCollectionQuota;
import com.example.monitor.repository.VideoCollectionQuotaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.io.IOException;

/** 検索APIを使わず、1回1単位の呼び出しだけを1日3000回までに制限する。 */
@Service @RequiredArgsConstructor
public class YouTubeCatalogQuota {
    private final VideoCollectionQuotaRepository repository;

    public synchronized void acquire() throws IOException {
        LocalDate today = LocalDate.now(ZoneId.of("America/Los_Angeles"));
        var state = repository.findById("youtube-library").orElseGet(VideoCollectionQuota::new);
        state.setId("youtube-library");
        if (!today.equals(state.getQuotaDate())) { state.setQuotaDate(today); state.setRequests(0); }
        if (state.getRequests() >= 3000) throw new IOException("新着動画取得の本日のAPI使用上限に達しました");
        state.setRequests(state.getRequests() + 1);
        repository.saveAndFlush(state);
    }
}
