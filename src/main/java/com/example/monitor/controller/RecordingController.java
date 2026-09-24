package com.example.monitor.controller;

import com.example.monitor.dto.DiskUsageResponse;
import com.example.monitor.dto.OrphanedCleanupResponse;
import com.example.monitor.dto.PageResponse;
import com.example.monitor.dto.RecordingGenreCountResponse;
import com.example.monitor.dto.RecordingResponse;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.entity.RecordingMark;
import com.example.monitor.service.RecordingHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 録画履歴を参照する REST API。
 *
 * <p>実際の動画ファイルは REST API ではなく静的リソースとして配信する
 * （{@link com.example.monitor.config.RecordingResourceConfig}参照）。ここで返す
 * {@link RecordingResponse#filePath()} を {@code /recordings/} と連結すれば再生用 URL になる。
 */
@RestController
@RequestMapping("/api/recordings")
@RequiredArgsConstructor
public class RecordingController {

    /** 1 ページの件数の上限。極端な値で数千件を一度に読み込ませないため。 */
    static final int MAX_PAGE_SIZE = 100;

    private final RecordingHistoryService recordingHistoryService;

    /**
     * 録画履歴を条件で絞り込んで取得する。どの条件も省略でき、組み合わせられる。
     *
     * <p>件数・並び順は範囲外なら丸めずに 400 にする。画面は URL に条件を残すため、
     * 黙って丸めると URL と表示が食い違い、なぜその件数なのか分からなくなる。
     *
     * @param channelId 特定チャンネルに絞り込む場合はその主キー。省略すると全チャンネルが対象
     * @param keyword   配信タイトル・チャンネル名に対する部分一致の検索キーワード
     * @param status    絞り込む録画状態（{@code RECORDING} / {@code COMPLETED} / {@code PARTIAL} / {@code FAILED}）
     * @param sort      並び順（{@code newest}（既定） / {@code oldest} / {@code longest} / {@code largest}）
     * @param from      開始日（{@code yyyy-MM-dd}、この日を含む）
     * @param to        終了日（{@code yyyy-MM-dd}、この日を含む）
     * @param genre     ジャンル（完全一致）
     * @param watched   {@code unwatched}（未視聴だけ） / {@code watched}（視聴済みだけ）。省略時は絞らない
     * @param favorite  {@code true} ならお気に入りだけ
     * @param page      ページ番号（0 始まり）
     * @param size      1 ページあたりの件数（1〜{@value #MAX_PAGE_SIZE}）
     * @param authentication ログイン中の利用者。視聴済み・お気に入りはこの利用者の印だけを見る
     * @return 条件に一致する録画履歴
     * @throws IllegalArgumentException 並び順・期間・視聴状態・ページ指定が不正な場合（400）
     */
    @GetMapping
    public PageResponse<RecordingResponse> getRecordings(
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) RecordingStatus status,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String watched,
            @RequestParam(defaultValue = "false") boolean favorite,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {

        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page は 0 以上、size は 1〜" + MAX_PAGE_SIZE + " で指定してください");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("期間の開始は終了以前にしてください");
        }
        PageRequest pageRequest = PageRequest.of(page, size, toSort(sort));
        Boolean watchedFilter = toWatchedFilter(watched);
        String username = authentication.getName();

        Page<Recording> recordings = recordingHistoryService.search(
                username, channelId, keyword, status, from, to, genre, watchedFilter, favorite, pageRequest);
        // 印はページ分をまとめて 1 回で引く（行ごとに引くと件数ぶんクエリが飛ぶ）
        Map<Long, RecordingMark> marks = recordingHistoryService.findMarks(
                username, recordings.map(Recording::getId).getContent());

        // Page をそのまま返すと JSON 構造が Spring の実装依存になる（PageResponse の JavaDoc 参照）
        return PageResponse.from(recordings.map(r -> RecordingResponse.from(r, marks.get(r.getId()))));
    }

    /**
     * 視聴状態の指定を検索条件に変える。
     *
     * <p>知らない値は無視せず 400 にする。並び順と同じく、黙って「絞らない」にすると
     * URL と表示が食い違うため。
     *
     * @param watched {@code unwatched} / {@code watched} / {@code null}
     * @return 視聴済みだけなら {@code true}、未視聴だけなら {@code false}、絞らないなら {@code null}
     * @throws IllegalArgumentException 知らない値の場合（400）
     */
    private static Boolean toWatchedFilter(String watched) {
        if (watched == null) {
            return null;
        }
        return switch (watched) {
            case "watched" -> true;
            case "unwatched" -> false;
            default -> throw new IllegalArgumentException(
                    "watched は watched / unwatched のいずれかで指定してください: " + watched);
        };
    }

    /**
     * ジャンルごとの録画件数を、件数の多い順に取得する。一覧画面のジャンル選択の選択肢に使う。
     *
     * @return ジャンルと件数の一覧（ジャンルの無い録画は含まない）
     */
    @GetMapping("/genres")
    public List<RecordingGenreCountResponse> getGenres() {
        return recordingHistoryService.countByGenre();
    }

    /**
     * 並び順の名前を {@link Sort} に変える。
     *
     * <p>同じ値どうしの順番は開始時刻の新しい順 → 主キーの大きい順で決める。決めておかないと
     * ページをまたいだときに同じ録画が 2 回出たり抜けたりする。
     * 長さ・サイズが {@code null}（録画中・失敗）の録画は降順で末尾に来る。H2 は {@code null} を
     * 最小値として並べるため（Spring Data の {@code nullsLast()} は {@code @Query} では効かない）。
     *
     * @param sort 並び順の名前
     * @return 対応する並び順
     * @throws IllegalArgumentException 知らない名前の場合（400）
     */
    private static Sort toSort(String sort) {
        Sort newest = Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"));
        return switch (sort) {
            case "newest" -> newest;
            case "oldest" -> Sort.by(Sort.Order.asc("startedAt"), Sort.Order.asc("id"));
            case "longest" -> Sort.by(Sort.Order.desc("durationSeconds")).and(newest);
            case "largest" -> Sort.by(Sort.Order.desc("fileSizeBytes")).and(newest);
            default -> throw new IllegalArgumentException(
                    "sort は newest / oldest / longest / largest のいずれかで指定してください: " + sort);
        };
    }

    /**
     * 録画履歴を 1 件取得する。再生画面（{@code player.html}）が対象の情報を得るために使う。
     *
     * @param id             録画履歴の主キー
     * @param authentication ログイン中の利用者。視聴済み・お気に入りはこの利用者の印を返す
     * @return 該当する録画履歴
     * @throws com.example.monitor.exception.RecordingNotFoundException 指定 ID が存在しない場合（404）
     */
    @GetMapping("/{id}")
    public RecordingResponse getRecording(@PathVariable Long id, Authentication authentication) {
        Recording recording = recordingHistoryService.findById(id);
        RecordingMark mark = recordingHistoryService.findMarks(authentication.getName(), List.of(id)).get(id);
        return RecordingResponse.from(recording, mark);
    }

    /**
     * 録画履歴と、それに紐づく録画ファイルを削除する。
     *
     * @param id 削除対象の録画履歴の主キー
     * @return 本文なしの HTTP 204
     * @throws com.example.monitor.exception.RecordingNotFoundException   指定 ID が存在しない場合（404）
     * @throws com.example.monitor.exception.RecordingInProgressException 録画中の場合（409）
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRecording(@PathVariable Long id) {
        recordingHistoryService.deleteRecording(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code recordings/} ディレクトリの使用量を、チャンネル別に取得する。
     *
     * @return 合計使用量とチャンネル別の内訳
     */
    @GetMapping("/disk-usage")
    public DiskUsageResponse getDiskUsage() {
        return recordingHistoryService.calculateDiskUsage();
    }

    /**
     * 監視対象から削除済みのチャンネルの録画ファイルをまとめて削除する。
     *
     * <p>チャンネルを削除しても録画ファイル本体はディスクに残る設計のため、
     * それらをまとめて片付けるための操作。<b>ファイルは元に戻せない。</b>
     * 録画がまだ進行中のチャンネルは対象から外れる（結果の {@code skippedChannels} に入る）。
     *
     * @return 削除したチャンネル数・ファイル数・解放された容量
     */
    @DeleteMapping("/orphaned")
    public OrphanedCleanupResponse deleteOrphanedRecordings() {
        return recordingHistoryService.deleteOrphanedRecordings();
    }
}
