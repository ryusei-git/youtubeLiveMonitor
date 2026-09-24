package com.example.monitor.controller;

import com.example.monitor.dto.PageResponse;
import com.example.monitor.dto.RecordingFavoriteRequest;
import com.example.monitor.dto.RecordingGenreCountResponse;
import com.example.monitor.dto.RecordingMarkResponse;
import com.example.monitor.dto.RecordingResponse;
import com.example.monitor.dto.RecordingWatchedRequest;
import com.example.monitor.entity.Recording;
import com.example.monitor.entity.Recording.RecordingStatus;
import com.example.monitor.entity.RecordingMark;
import com.example.monitor.service.RecordingHistoryService;
import com.example.monitor.service.RecordingMarkService;
import com.example.monitor.service.UserSubscriptionService;
import com.example.monitor.util.PageRequestUtils;
import com.example.monitor.util.RecordingSearchParams;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * ログイン中の利用者が見られる録画を返す API。
 *
 * <p>管理者向けの {@code /api/recordings} とは<b>別の窓口</b>として分けている。
 * あちらは全チャンネルの録画を返し、削除もできる。こちらは
 * <b>自分が購読しているチャンネルの録画だけ</b>を返し、削除はできない。
 * 検索条件・入力チェック・応答の形は管理者側と揃えている（同じ画面部品を両方で使えるように）。
 *
 * <p>購読していない録画の 1 件取得・印の変更は 404 にする
 * （{@link UserSubscriptionService#findMyRecording(Long)} 参照）。
 *
 * <p>対象の利用者はパスから指定できず、常にログイン中の本人になる
 * （{@link MyChannelController} と同じ考え方）。依存するサービスはどれもプロファイルを問わず
 * 作られるので、{@code @Profile("!cli")} は付けていない（{@link MyChannelController} と同じ）。
 */
@RestController
@RequestMapping("/api/my/recordings")
@RequiredArgsConstructor
public class MyRecordingController {

    /** 1 ページの件数の上限。管理者側（{@code RecordingController.MAX_PAGE_SIZE}）と揃える。 */
    private static final int MAX_PAGE_SIZE = RecordingController.MAX_PAGE_SIZE;

    private final UserSubscriptionService userSubscriptionService;
    private final RecordingHistoryService recordingHistoryService;
    private final RecordingMarkService recordingMarkService;

    /**
     * 自分が購読しているチャンネルの録画を条件で絞り込んで返す。
     * パラメータは管理者の {@code GET /api/recordings} と同じで、{@code playableOnly} だけが追加。
     *
     * @param channelId    購読チャンネルの主キー。購読していないチャンネルを指定しても何も返らない
     * @param keyword      配信タイトル・チャンネル名に対する部分一致の検索キーワード（200 文字まで）
     * @param status       絞り込む録画状態
     * @param sort         並び順（{@code newest}（既定） / {@code oldest} / {@code longest} / {@code largest}）
     * @param from         開始日（{@code yyyy-MM-dd}、この日を含む）
     * @param to           終了日（{@code yyyy-MM-dd}、この日を含む）
     * @param genre        ジャンル（完全一致）
     * @param watched      {@code unwatched} / {@code watched}。省略時は絞らない
     * @param favorite     {@code true} ならお気に入りだけ
     * @param playableOnly 再生可能な録画（完了・途中まで）だけに絞るか
     * @param page         ページ番号（0 始まり）
     * @param size         1 ページあたりの件数（1〜{@value #MAX_PAGE_SIZE}）
     * @param authentication ログイン中の利用者
     * @return 条件に一致する録画
     * @throws IllegalArgumentException 並び順・期間・視聴状態・ページ指定・検索語が不正な場合（400）
     */
    @GetMapping
    public PageResponse<RecordingResponse> listMyRecordings(
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) RecordingStatus status,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String watched,
            @RequestParam(defaultValue = "false") boolean favorite,
            @RequestParam(defaultValue = "false") boolean playableOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        if (keyword != null && keyword.length() > 200) {
            throw new IllegalArgumentException("検索語は200文字以内で指定してください");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("期間の開始は終了以前にしてください");
        }
        PageRequest pageRequest = PageRequestUtils.bounded(page, size, MAX_PAGE_SIZE)
                .withSort(RecordingSearchParams.toSort(sort));
        Boolean watchedFilter = RecordingSearchParams.toWatchedFilter(watched);
        String username = authentication.getName();

        Page<Recording> recordings = recordingHistoryService.search(username, true, channelId, keyword,
                status, from, to, genre, watchedFilter, favorite, playableOnly, pageRequest);
        // 印はページ分をまとめて 1 回で引く（行ごとに引くと件数ぶんクエリが飛ぶ）
        Map<Long, RecordingMark> marks = recordingHistoryService.findMarks(
                username, recordings.map(Recording::getId).getContent());
        return PageResponse.from(recordings.map(r -> RecordingResponse.from(r, marks.get(r.getId()))));
    }

    /**
     * 購読しているチャンネルの録画に限って、ジャンルごとの件数を返す。一覧のジャンル選択の選択肢に使う。
     *
     * @param authentication ログイン中の利用者
     * @return ジャンルと件数の一覧（ジャンルの無い録画は含まない）
     */
    @GetMapping("/genres")
    public List<RecordingGenreCountResponse> getGenres(Authentication authentication) {
        return recordingHistoryService.countSubscribedByGenre(authentication.getName());
    }

    /**
     * 購読しているチャンネルの録画を 1 件取得する。再生画面が対象の情報を得るために使う。
     *
     * @param id             録画の主キー
     * @param authentication ログイン中の利用者。視聴済み・お気に入りはこの利用者の印を返す
     * @return 該当する録画
     * @throws com.example.monitor.exception.RecordingNotFoundException 無い・購読していない場合（404）
     */
    @GetMapping("/{id}")
    public RecordingResponse getRecording(@PathVariable Long id, Authentication authentication) {
        Recording recording = userSubscriptionService.findMyRecording(id);
        RecordingMark mark = recordingHistoryService.findMarks(authentication.getName(), List.of(id)).get(id);
        return RecordingResponse.from(recording, mark);
    }

    /**
     * 視聴済みを切り替える。本文と戻り値は管理者側（{@link RecordingMarkController}）と同じ。
     *
     * @param id      録画の主キー
     * @param request 視聴済みにするか
     * @return 変更後の印
     * @throws com.example.monitor.exception.RecordingNotFoundException 無い・購読していない場合（404）
     */
    @PutMapping("/{id}/watched")
    public RecordingMarkResponse setWatched(@PathVariable Long id, @RequestBody RecordingWatchedRequest request) {
        userSubscriptionService.findMyRecording(id);
        return recordingMarkService.setWatched(id, request.watched());
    }

    /**
     * お気に入りを切り替える。本文と戻り値は管理者側（{@link RecordingMarkController}）と同じ。
     *
     * @param id      録画の主キー
     * @param request お気に入りにするか
     * @return 変更後の印
     * @throws com.example.monitor.exception.RecordingNotFoundException 無い・購読していない場合（404）
     */
    @PutMapping("/{id}/favorite")
    public RecordingMarkResponse setFavorite(@PathVariable Long id, @RequestBody RecordingFavoriteRequest request) {
        userSubscriptionService.findMyRecording(id);
        return recordingMarkService.setFavorite(id, request.favorite());
    }

    /**
     * 数値・日付・状態への変換失敗を共通の500処理へ渡さず、内部の型名を含めない400にする。
     * @param exception パラメーターの変換失敗
     * @return 入力誤りの応答
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleInvalidPage(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "数値・日付・状態・真偽値の指定が正しくありません"));
    }
}
