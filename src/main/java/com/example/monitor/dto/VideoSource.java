package com.example.monitor.dto;

import java.util.Optional;

/**
 * ダウンロード対象の動画 1 件の素性。{@code yt-dlp --print} が返したメタデータを表す。
 *
 * <h2>なぜ公式 API を使わないのか</h2>
 * URL からチャンネルや動画 ID を割り出すのに YouTube Data API を使うと<b>クォータを消費する</b>
 * （1 日の上限は既定 10,000）。{@code yt-dlp} は動画ページを直接読むため
 * <b>クォータを一切消費せずに</b>同じ情報が得られる。ダウンロードのために結局
 * {@code yt-dlp} を起動するので、余計な依存も増えない。
 *
 * <h2>取得できない項目は {@code null} で表す</h2>
 * {@code yt-dlp} は値が無い項目に<b>文字列 {@code NA} を出力する</b>（{@code null} ではない）。
 * これをそのまま持ち回ると、{@code "NA"} というチャンネル ID を DB と突き合わせにいく、
 * といった取り違えが起きる。<b>境界であるこのクラスで {@code null} に寄せて</b>、
 * 以降は「取得できなかった」ことを型の上で扱えるようにしている。
 * 実機で確認した限り、Twitch の VOD では {@code channel_id} が取得できない。
 *
 * <h2>出力テンプレートとこのクラスが対になっている理由</h2>
 * {@link #PRINT_TEMPLATE} と {@link #parse(String)} は<b>必ず同時に直す必要がある</b>。
 * 片方だけ変えると解析がずれ、値が 1 つずつ後ろの項目に入るという分かりにくい形で壊れる
 * （{@code logback-spring.xml} の出力形式と {@code ChannelLogReader} の解析パターンが
 * 対になっているのと同じ関係）。そのため両方をこのクラスに置いている。
 *
 * @param extractor  {@code yt-dlp} が使った抽出器の名前（{@code youtube} / {@code twitch:vod} など）。
 *                   不明なら {@code null}
 * @param videoId    動画の識別子。YouTube は {@code aqz-KE-bpKQ}、Twitch の VOD は
 *                   {@code v2879280908}（URL の数字に {@code v} が付く）
 * @param channelId  プラットフォームが発行するチャンネル ID（YouTube の {@code UC...}）。
 *                   取得できなければ {@code null}
 * @param uploaderId 投稿者の識別子。YouTube はハンドル（{@code @foo}）、Twitch はログイン名。
 *                   取得できなければ {@code null}
 * @param liveStatus {@code yt-dlp} が返す配信状態（{@code is_live} / {@code is_upcoming} /
 *                   {@code was_live} / {@code post_live} / {@code not_live}）。取得できなければ
 *                   {@code null}。手動ダウンロードがライブ配信・待機所を拒否する判定に使う
 *                   （{@link #isLiveOrUpcoming()}）
 * @param title      動画のタイトル。取得できなければ {@code null}
 */
public record VideoSource(
        String extractor,
        String videoId,
        String channelId,
        String uploaderId,
        String liveStatus,
        String title
) {

    /**
     * {@code yt-dlp --print} に渡す出力テンプレート。
     *
     * <p><b>自由入力の項目（タイトル）を必ず末尾に置く。</b>区切り文字と同じ
     * {@code |} を含むタイトルは珍しくないため、末尾以外に置くと以降の項目がずれる。
     * 末尾なら区切り文字ごと 1 項目として取り込めるので壊れない
     * （{@link #parse(String)} が分割数を制限しているのはこのため）。
     *
     * <p>同じ理由で、表示名（{@code uploader}）は取得していない。{@code Foo | Bar} のような
     * 名前がありえるうえ、チャンネルの特定には {@code channel_id} と {@code uploader_id} で足りる。
     * {@code live_status} は決まった語彙（{@code is_live} 等）しか返らないため、自由入力の
     * タイトルより手前に置いても区切り文字のずれは起きない。
     */
    public static final String PRINT_TEMPLATE =
            "%(extractor)s|%(id)s|%(channel_id)s|%(uploader_id)s|%(live_status)s|%(title)s";

    /** 項目の区切り文字。{@link #PRINT_TEMPLATE} と対になっている。 */
    private static final String FIELD_SEPARATOR = "\\|";

    /** {@link #PRINT_TEMPLATE} が出力する項目数。 */
    private static final int FIELD_COUNT = 6;

    /** 手動ダウンロードを拒否する {@link #liveStatus} の値。{@link #isLiveOrUpcoming()} 参照。 */
    private static final String LIVE_STATUS_IS_LIVE = "is_live";

    /** 手動ダウンロードを拒否する {@link #liveStatus} の値。{@link #isLiveOrUpcoming()} 参照。 */
    private static final String LIVE_STATUS_IS_UPCOMING = "is_upcoming";

    /** {@code yt-dlp} が「値が無い」ことを表すために出力する文字列。 */
    private static final String NOT_AVAILABLE = "NA";

    /**
     * {@code yt-dlp --print} の出力を解析する。
     *
     * <p>動画 ID だけは欠けていると保存先ファイル名も決められないため、
     * 取得できなかった場合は解析失敗（{@link Optional#empty()}）として扱う。
     * 他の項目は欠けていても「不明」のまま先へ進める。
     *
     * @param printedOutput {@code yt-dlp} の標準出力
     * @return 解析できたメタデータ。形式が想定と違う場合は {@link Optional#empty()}
     */
    public static Optional<VideoSource> parse(String printedOutput) {
        if (printedOutput == null || printedOutput.isBlank()) {
            return Optional.empty();
        }

        // --print は 1 行だけ出力するが、環境によっては前後に空行が付くため最初の実体行を使う
        String line = printedOutput.lines()
                .map(String::strip)
                .filter(candidate -> !candidate.isEmpty())
                .findFirst()
                .orElse("");

        String[] fields = line.split(FIELD_SEPARATOR, FIELD_COUNT);
        if (fields.length < FIELD_COUNT) {
            return Optional.empty();
        }

        String videoId = valueOrNull(fields[1]);
        if (videoId == null) {
            return Optional.empty();
        }

        return Optional.of(new VideoSource(
                valueOrNull(fields[0]),
                videoId,
                valueOrNull(fields[2]),
                valueOrNull(fields[3]),
                valueOrNull(fields[4]),
                valueOrNull(fields[5])));
    }

    /**
     * 配信中、または配信開始前の待機所かどうかを返す。
     *
     * <p>手動ダウンロードはVOD（録画済み動画）向けの機能で、ライブ配信の取得は自動録画
     * （{@code StreamRecorder}）の担当と責務を分けている。配信直後でまだ処理中の
     * {@code post_live} や、既に終わった配信の {@code was_live} はここでは拒否しない
     * ——これらは自動録画と処理が重なる可能性はあるが、その重複の排除は
     * {@code ActiveVideoJobs} を介した予約機構が担う層であり、この判定の役割ではない
     * （docs/review-fix-tasks.md の「4-2 の設計」参照）。{@link #liveStatus} が
     * {@code null}（取得できなかった、または元々ライブ配信の概念が無い動画）の場合も拒否しない。
     *
     * @return {@link #liveStatus} が {@code is_live} または {@code is_upcoming} なら {@code true}
     */
    public boolean isLiveOrUpcoming() {
        return LIVE_STATUS_IS_LIVE.equals(liveStatus) || LIVE_STATUS_IS_UPCOMING.equals(liveStatus);
    }

    /**
     * {@code yt-dlp} が出力した 1 項目を、値が無い場合は {@code null} にして返す。
     *
     * @param field 出力された項目
     * @return 値。取得できていなければ {@code null}
     */
    private static String valueOrNull(String field) {
        String trimmed = field.strip();
        if (trimmed.isEmpty() || NOT_AVAILABLE.equals(trimmed)) {
            return null;
        }
        return trimmed;
    }
}
