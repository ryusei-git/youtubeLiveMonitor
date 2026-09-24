package com.example.monitor.service;

import com.example.monitor.entity.Recording;
import com.example.monitor.repository.RecordingRepository;
import com.example.monitor.util.TitleGenreExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@link Recording#genre} 列を足す前に作られた録画に、起動時にジャンルを埋める。
 *
 * <p>{@code ddl-auto: update} は列を足すだけで既存の行の値は入れないため、放っておくと
 * 古い録画がジャンルで絞り込めない。新しい録画は {@link RecordingHistoryService#recordStart}
 * で入るので、ここが相手にするのは列を足す前の行だけになる。
 *
 * <p><b>毎回の起動で対象を探し直す。</b>{@code 【】} の無いタイトルは {@code null} のまま残り、
 * 次の起動でもまた対象に上がるが、数千件のタイトルに正規表現を掛けるだけなので問題にならない。
 * 「埋め終わった」印を別に持つより単純なため、そうしている。
 *
 * <p>{@code cli} プロファイルで動かさないのは、CLI の実行のたびに走らせる必要が無いため
 * （{@link com.example.monitor.security.AdminUserInitializer} と同じ）。
 */
@Component
@Profile("!cli")
@RequiredArgsConstructor
@Slf4j
public class RecordingGenreBackfiller implements ApplicationRunner {

    private final RecordingRepository recordingRepository;

    /**
     * ジャンル未設定の録画にタイトルから求めたジャンルを書き込む。
     *
     * <p>{@code save(entity)} ではなく {@link RecordingRepository#updateGenre} で列を絞って書く。
     * 起動直後は録画の完了記録が別スレッドから走りうるため、全カラムを書き戻して
     * そちらの更新を上書きしないようにする。
     *
     * @param args 未使用（{@link ApplicationRunner} のシグネチャ上必要）
     */
    @Override
    public void run(ApplicationArguments args) {
        int updated = 0;
        for (Recording recording : recordingRepository.findByGenreIsNullAndVideoTitleIsNotNull()) {
            String genre = TitleGenreExtractor.extract(recording.getVideoTitle());
            if (genre != null) {
                updated += recordingRepository.updateGenre(recording.getId(), genre);
            }
        }
        if (updated > 0) {
            log.info("既存の録画 {} 件にジャンルを設定しました", updated);
        }
    }
}
