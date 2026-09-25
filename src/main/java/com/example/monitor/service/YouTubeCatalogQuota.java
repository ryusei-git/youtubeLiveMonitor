package com.example.monitor.service;
import com.example.monitor.entity.VideoCollectionQuota;
import com.example.monitor.repository.VideoCollectionQuotaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.ZoneId;
import java.io.IOException;

/** 検索APIを使わず、1回1単位の呼び出しだけを1日3000回までに制限する。 */
@Service @RequiredArgsConstructor
public class YouTubeCatalogQuota {
    /** 1 日に使ってよい回数。1 日 10000 単位のうち、監視・通知に使う分を残すため。 */
    private static final int DAILY_REQUEST_LIMIT = 3000;
    /** 日付を切るタイムゾーン。YouTube Data API のクォータは太平洋時間の 0 時に戻るため。 */
    private static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");
    private final VideoCollectionQuotaRepository repository;

    public synchronized void acquire() throws IOException {
        LocalDate today = LocalDate.now(QUOTA_ZONE);
        var state = repository.findById("youtube-library").orElseGet(VideoCollectionQuota::new);
        state.setId("youtube-library");
        if (!today.equals(state.getQuotaDate())) {
            state.setQuotaDate(today);
            state.setRequests(0);
        }
        if (state.getRequests() >= DAILY_REQUEST_LIMIT) throw new IOException("新着動画取得の本日のAPI使用上限に達しました");
        state.setRequests(state.getRequests() + 1);
        repository.saveAndFlush(state);
    }
}
