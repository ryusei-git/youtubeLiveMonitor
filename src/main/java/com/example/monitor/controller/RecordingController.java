package com.example.monitor.controller;

import com.example.monitor.dto.DiskUsageResponse;
import com.example.monitor.dto.PageResponse;
import com.example.monitor.dto.RecordingGenreCountResponse;
import com.example.monitor.dto.RecordingResponse;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.entity.RecordingMark;
import com.example.monitor.service.RecordingHistoryService;
import com.example.monitor.service.StreamRecorder;
import com.example.monitor.util.RecordingSearchParams;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 録画履歴を参照・削除し、録画中の録画を止める REST API。
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
    private final StreamRecorder streamRecorder;

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
        PageRequest pageRequest = PageRequest.of(page, size, RecordingSearchParams.toSort(sort));
        Boolean watchedFilter = RecordingSearchParams.toWatchedFilter(watched);
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
     * ジャンルごとの録画件数を、件数の多い順に取得する。一覧画面のジャンル選択の選択肢に使う。
     *
     * @return ジャンルと件数の一覧（ジャンルの無い録画は含まない）
     */
    @GetMapping("/genres")
    public List<RecordingGenreCountResponse> getGenres() {
        return recordingHistoryService.countByGenre();
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
     * <p>パスを数字だけに絞っているのは、廃止した {@code DELETE /api/recordings/orphaned}（#264）を
     * 叩かれたときに {@code orphaned} が {@code id} として解釈され、型変換の失敗で 500（ERROR ログ）に
     * ならないようにするため（実際に 500 になることを確かめた）。数字以外はこの削除に一致せず、
     * 同じパスの {@link #getRecording} だけが残るので 405 になる。
     *
     * @param id 削除対象の録画履歴の主キー
     * @return 本文なしの HTTP 204
     * @throws com.example.monitor.exception.RecordingNotFoundException   指定 ID が存在しない場合（404）
     * @throws com.example.monitor.exception.RecordingInProgressException 録画中の場合（409）
     */
    @DeleteMapping("/{id:\\d+}")
    public ResponseEntity<Void> deleteRecording(@PathVariable Long id) {
        recordingHistoryService.deleteRecording(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 録画中の録画を止める。止めた録画は削除せず、そこまでを「途中まで」（{@code PARTIAL}）として残す。
     *
     * <p>止め終わるまで最大 30 秒かかるため、受け付けたら 202 を返す（結果は一覧の状態で分かる。
     * 画面は録画中の録画がある間、一覧を 10 秒ごとに読み直している）。
     * 止められないときは例外にせず 409 を返す。「録画中でない」「プロセスが無い」はどちらも
     * 画面の表示と今の状態の食い違いで、サーバーの異常ではないため（{@code DiscoveryController#run} の 409 と同じ返し方）。
     * 理由は {@link StreamRecorder#stopRecording(Long)} が WARN でログに残す。
     *
     * @param id 録画履歴の主キー
     * @return 受け付けたら 202、止められなければ 409（本文は {@code {"error": "理由"}}）
     * @throws com.example.monitor.exception.RecordingNotFoundException 指定 ID が存在しない場合（404）
     */
    @PostMapping("/{id:\\d+}/stop")
    public ResponseEntity<Map<String, String>> stopRecording(@PathVariable Long id) {
        return switch (streamRecorder.stopRecording(id)) {
            case STOPPING -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("message", "録画を止めています。少し待つと一覧の状態が変わります"));
            case NOT_RECORDING -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "録画中ではないため止められません"));
            case NO_PROCESS -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "録画プロセスが見つかりませんでした。既に終わっている可能性があります（数分で一覧の状態が直ります）"));
        };
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
}
