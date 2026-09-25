package com.example.monitor.service;
import com.example.monitor.entity.VideoCollectionQuota;
import com.example.monitor.repository.VideoCollectionQuotaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.ZoneId;
import java.io.IOException;

/**
 * 動画収集が使う YouTube Data API の呼び出し回数を数え、1 日の上限で止める。
 * <p>検索APIを使わず、1回1単位の呼び出しだけを1日3000回までに制限する。
 */
@Service @RequiredArgsConstructor
public class YouTubeCatalogQuota {
    /** 1 日に使ってよい回数。1 日 10000 単位のうち、監視・通知に使う分を残すため。 */
    private static final int DAILY_REQUEST_LIMIT = 3000;
    /** 日付を切るタイムゾーン。YouTube Data API のクォータは太平洋時間の 0 時に戻るため。 */
    private static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");
    private final VideoCollectionQuotaRepository repository;

    /**
     * YouTube Data API を 1 回呼ぶ前に、今日の使用回数を 1 増やす。
     * <p>数えた回数は DB に保存するので、再起動しても数え直しにならない。日付の切り方と上限の理由は {@code QUOTA_ZONE}・{@code DAILY_REQUEST_LIMIT} を参照。
     *
     * @throws IOException 本日の上限に達した場合（このときは回数を増やさない）
     */
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
