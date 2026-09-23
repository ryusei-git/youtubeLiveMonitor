package com.example.monitor.service;

import com.example.monitor.dto.VideoSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * ダウンロードする前に、URL から動画の素性（動画 ID・チャンネル・タイトル）を {@code yt-dlp} で下調べする。
 *
 * <h2>なぜ公式 API を使わないのか</h2>
 * YouTube Data API でも同じ情報は取れるが<b>クォータを消費する</b>（1 日の上限は既定 10,000）。
 * {@code yt-dlp --print} は動画ページを直接読むため<b>クォータを一切消費しない</b>うえ、
 * ダウンロード本体で結局 {@code yt-dlp} を起動するので依存も増えない。
 * Twitch の VOD のように公式 API では引きにくいものも同じ手順で扱える。
 *
 * <h2>{@link StreamRecorder} と別物である理由</h2>
 * こちらは数秒で終わる問い合わせなので {@link ExternalCommandRunner}（起動して出力を読み切って
 * 終了コードを見る、という定型処理）に任せられる。録画・ダウンロード本体は数時間動き続けるため
 * 同じ仕組みには乗らない。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoSourceProbe {

    /**
     * 応答待ちの上限（秒）。
     *
     * <p>メタデータの取得だけなら通常は数秒で終わるが、{@code yt-dlp} は動画ページの取得を
     * 何度か再試行することがあるため余裕を持たせている。一方でこれは利用者が
     * 「ダウンロード」を押して待っている間の時間でもあるので、無制限にはしない。
     */
    private static final long COMMAND_TIMEOUT_SECONDS = 60;

    private final ExternalCommandRunner externalCommandRunner;

    /**
     * URL から動画のメタデータを取得する。
     *
     * @param url ダウンロード対象の動画 URL
     * @return 取得できたメタデータ。取得できなかった（存在しない URL・非公開・
     *         {@code yt-dlp} が未インストール等）場合は {@link Optional#empty()}
     */
    public Optional<VideoSource> probe(String url) {
        List<String> command = List.of(
                "yt-dlp",
                // 警告文が標準出力に混ざると解析対象の行が特定しづらくなる
                "--no-warnings",
                // 再生リスト付きの URL（&list=...）を貼られても、対象は1本だけにする
                "--no-playlist",
                // --print だけでもダウンロードはされないが、意図を明示しておく
                "--simulate",
                "--print", VideoSource.PRINT_TEMPLATE,
                url);

        Optional<VideoSource> source = externalCommandRunner
                .run(command, url, COMMAND_TIMEOUT_SECONDS)
                .flatMap(VideoSource::parse);

        if (source.isEmpty()) {
            log.warn("動画のメタデータを取得できませんでした: url={}", url);
        }
        return source;
    }
}
