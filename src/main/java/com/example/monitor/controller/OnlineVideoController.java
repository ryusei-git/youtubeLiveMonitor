package com.example.monitor.controller;

import com.example.monitor.dto.OnlineVideoResponse;
import com.example.monitor.dto.PageResponse;
import com.example.monitor.entity.OnlineVideo;
import com.example.monitor.repository.MonitoredChannelRepository;
import com.example.monitor.repository.OnlineVideoRepository;
import com.example.monitor.repository.VideoThumbnailRepository;
import com.example.monitor.service.OnlineVideoService;
import com.example.monitor.service.VideoCollectionTracker;
import com.example.monitor.util.PageRequestUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 利用者の動画ライブラリ（収集した外部動画）の一覧・詳細・サムネイルを返す API。
 *
 * <p>一覧と画像の両方で購読を照合し、URLの直打ちでも他人のライブラリを読ませない。
 */
@RestController @RequestMapping("/api/videos") @RequiredArgsConstructor
public class OnlineVideoController {
    /** 1 ページの件数の上限。一度に大量の行を読ませて応答を重くしないため。 */
    private static final int MAX_PAGE_SIZE = 100;
    /** 検索語の長さの上限。極端に長い語で LIKE 検索を重くさせないため。 */
    private static final int MAX_KEYWORD_LENGTH = 200;
    /** 画面の配信予定は直近 1 週間分だけを並べる。 */
    private static final Duration UPCOMING_WINDOW = Duration.ofDays(7);

    private final OnlineVideoRepository repository;
    private final VideoThumbnailRepository thumbnails;
    private final OnlineVideoService service;
    private final MonitoredChannelRepository channels;
    private final VideoCollectionTracker tracker;

    /**
     * ログイン中の利用者が管理者かを返す。
     *
     * <p>管理者の動画一覧（{@code videos.js}）が、一般の利用者を利用者用の画面へ移すかを決めるのに使う。
     * 同じ URL を管理者も使うため、サーバーで転送せず画面側で判定させている。
     *
     * @param auth ログイン中の利用者
     * @return {@code {"admin": 管理者なら true}}（200）
     */
    @GetMapping("/viewer")
    public Map<String, Boolean> viewer(Authentication auth) {
        return Map.of("admin", admin(auth));
    }

    /**
     * ライブラリに出せるチャンネルと、それぞれの新着動画の取得状態を返す。
     *
     * <p>画面のチャンネルの絞り込みの選択肢と、取得に失敗しているチャンネルの案内に使う。
     * 管理者はすべて、一般の利用者は購読しているチャンネルだけを返し、一覧と同じ購読の境界を守る。
     *
     * @param auth ログイン中の利用者
     * @return チャンネル名の順に並べた取得状態（200）
     */
    @GetMapping("/channels")
    public List<VideoCollectionTracker.Snapshot> channels(Authentication auth) {
        return channels.findLibraryChannels(admin(auth), auth.getName()).stream().map(tracker::snapshot).toList();
    }


    /**
     * 見てよい動画を、{@code section} で選んだ段の条件で絞り込んで返す。
     *
     * <p>{@code section} は画面（{@code my-app.js}）との約束で、値ごとに次の段を返す。
     * どの段も購読の境界・{@code channelId}・{@code keyword} で絞り込む。
     * 「確認済みの配信中」は、配信中の印があり、アプリ起動後の巡回で観測でき、そのチャンネルの判定が失敗していないもの。
     * <ul>
     *   <li>省略：ダッシュボードが使う従来の検索。種類が未判定の動画も含めて公開の新しい順に並べ、
     *       {@code liveOnly=true} なら確認済みの配信中だけに絞る。</li>
     *   <li>{@code now}：確認済みの配信中と、開始予定が今から 7 日以内の配信予定（待機所）。配信中を先に、
     *       続けて開始予定の早い順。開始予定を過ぎてもまだ始まらない待機所も含む。種類が未判定の動画は出さない。</li>
     *   <li>{@code streams}：配信済み（種類が配信）のうち、確認済みの配信中を除いたもの。配信中かを確かめられて
     *       いない配信はどの段からも消えないようここに出す。公開の新しい順。</li>
     *   <li>{@code uploads}：投稿動画（種類が投稿）。公開の新しい順。</li>
     * </ul>
     * {@code liveOnly} は {@code section} を省略したときだけ効く。
     *
     * @param auth      ログイン中の利用者。管理者はすべてのチャンネル、一般の利用者は購読しているチャンネルだけを見られる
     * @param page      ページ番号（0 始まり）
     * @param size      1 ページあたりの件数（1〜{@value #MAX_PAGE_SIZE}）
     * @param keyword   タイトル・チャンネル名に対する、大文字小文字を区別しない部分一致の検索語（{@value #MAX_KEYWORD_LENGTH} 文字まで）
     * @param channelId 絞り込むチャンネルの主キー。見られないチャンネルを指定しても何も返らない
     * @param liveOnly  {@code true} なら確認済みの配信中だけ（{@code section} 省略時のみ）
     * @param section   段（省略 / {@code now} / {@code streams} / {@code uploads}）
     * @return 条件に一致する動画（200）
     * @throws ResponseStatusException {@code page < 0}、{@code size} が 1〜{@value #MAX_PAGE_SIZE} の外、
     *         {@code keyword} が {@value #MAX_KEYWORD_LENGTH} 文字超、{@code section} が上のどれでもない場合（400）
     * @throws IllegalArgumentException {@code page × size} が int の範囲を超える場合
     *         （400。{@code GlobalExceptionHandler} が「ページ番号が大きすぎます」を返す）
     */
    @GetMapping
    public PageResponse<OnlineVideoResponse> list(Authentication auth,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "24") int size,
            @RequestParam(defaultValue = "") String keyword, @RequestParam(required = false) Long channelId,
            @RequestParam(defaultValue = "false") boolean liveOnly, @RequestParam(required = false) String section) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || keyword.length() > MAX_KEYWORD_LENGTH) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        boolean admin = admin(auth);
        String user = auth.getName();
        // 範囲は上で確かめ済み。ここで効くのはオフセット（page × size）の溢れだけで、放っておくと Spring Data が 500 になる例外を投げる
        var pageable = PageRequestUtils.bounded(page, size, MAX_PAGE_SIZE);
        // 省略時はダッシュボード（liveOnly=true）が使う従来の検索のまま動きを変えない
        var videos = section == null
                ? repository.search(admin, user, channelId, liveOnly, service.startedAt(), keyword, pageable)
                : switch (section) {
                    case "now" -> repository.searchNow(admin, user, channelId, service.startedAt(),
                            Instant.now().plus(UPCOMING_WINDOW), keyword, pageable);
                    case "streams" -> repository.searchStreams(admin, user, channelId, service.startedAt(), keyword, pageable);
                    case "uploads" -> repository.searchUploads(admin, user, channelId, keyword, pageable);
                    default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                };
        return PageResponse.from(videos.map(service::response));
    }

    /**
     * 見てよい動画を 1 件返す。
     *
     * <p>一覧と同じ購読の境界で照合し、URL の直打ちでも購読していない動画を返さない。
     *
     * @param id   動画の主キー（{@link OnlineVideoResponse#id()} の形）
     * @param auth ログイン中の利用者
     * @return 該当する動画（200）
     * @throws ResponseStatusException 無い・見る権限がない場合（404）
     */
    @GetMapping("/{id}")
    public OnlineVideoResponse detail(@PathVariable String id, Authentication auth) {
        return service.response(visible(id, auth));
    }

    /**
     * 保存済みのサムネイル画像を返す。
     *
     * <p>外部の画像 URL を画面に直接渡さず、取得して保存した画像をこの経路で配る。画像にも一覧と同じ購読の照合をかけ、
     * 購読していない動画の画像を URL の直打ちで読ませない。
     *
     * @param id   動画の主キー
     * @param auth ログイン中の利用者
     * @return 画像本体と、保存時の Content-Type（200）
     * @throws ResponseStatusException 動画が無い・見る権限がない・画像をまだ取得できていない場合（404）
     */
    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable String id, Authentication auth) {
        visible(id, auth);
        var thumbnail = thumbnails.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(thumbnail.getContentType())).body(thumbnail.getContent());
    }

    /**
     * このコントローラーが投げた 404・400 を、画面に出せる文言の応答にする。
     *
     * <p>存在しない画像や入力不正を、共通の想定外エラー処理へ渡さない。
     *
     * @param error 投げたステータス付きの例外
     * @return 同じステータスと、{@code error} に文言を入れた本文
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException error) {
        String message = error.getStatusCode().value() == 404 ? "動画またはサムネイルが見つかりません" : "指定された条件が不正です";
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", message));
    }

    private OnlineVideo visible(String id, Authentication auth) {
        return repository.findVisible(id, admin(auth), auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
