package com.example.monitor.service;

import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.util.TitleCategoryClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * {@link Recording#categories} を、起動時に今の分類の定義に合わせて埋め直す。
 *
 * <p>{@link RecordingGenreBackfiller} と違い、値の入っている行も見直す。ジャンルは {@code 【】} の
 * 中身で決まって変わらないが、分類は {@link TitleCategoryClassifier} の定義を変えると結果が変わるため。
 * 「定義の版」を別に持つより、全行を判定し直して違う行だけ書く方が単純で、途中で止まっても次の起動で
 * 続きが直る。数千件のタイトルに正規表現を掛けるだけなので、起動のたびに走っても問題にならない。
 *
 * <p>{@code cli} プロファイルで動かさない理由は {@link RecordingGenreBackfiller} と同じ。
 */
@Component
@Profile("!cli")
@RequiredArgsConstructor
@Slf4j
public class RecordingCategoryBackfiller implements ApplicationRunner {

    private final RecordingRepository recordingRepository;
    private final RecordingHistoryService recordingHistoryService;

    /**
     * 分類が今の定義と違う録画を書き直し、再生できる録画の分類ごとの件数をログに出す。
     *
     * <p>{@code save(entity)} ではなく {@link RecordingRepository#updateCategories} で列を絞って書く。
     * 起動直後は録画の完了記録が別スレッドから走りうるため、全カラムを書き戻して
     * そちらの更新を上書きしないようにする。
     *
     * <p>ログに題名は出さない（件数だけで、定義の変更が効いたかは分かる）。
     *
     * @param args 未使用（{@link ApplicationRunner} のシグネチャ上必要）
     */
    @Override
    public void run(ApplicationArguments args) {
        int updated = 0;
        for (RecordingRepository.CategorySource source : recordingRepository.findCategorySources()) {
            String categories = TitleCategoryClassifier.classify(source.getVideoTitle());
            if (!categories.equals(source.getCategories())) {
                updated += recordingRepository.updateCategories(source.getId(), categories);
            }
        }
        if (updated > 0) {
            log.info("録画 {} 件の分類を埋め直しました", updated);
        }
        long playable = recordingRepository.countByStatus(RecordingStatus.COMPLETED)
                + recordingRepository.countByStatus(RecordingStatus.PARTIAL);
        String counts = recordingHistoryService.countPlayableByCategory().stream()
                .map(category -> category.label() + "=" + category.count())
                .collect(Collectors.joining(" "));
        log.info("録画の分類: 全 {} 件 {}", playable, counts);
    }
}
