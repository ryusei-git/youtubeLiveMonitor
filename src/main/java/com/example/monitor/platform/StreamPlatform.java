package com.example.monitor.platform;

import com.example.monitor.dto.LiveStreamDetails;
import com.example.monitor.dto.LiveStreamDetection;
import com.example.monitor.dto.VideoSource;
import com.example.monitor.util.ChannelLogContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.LoggerFactory;

/**
 * 配信プラットフォーム1つ分の窓口。プラットフォームごとに異なる処理はここに集約する。
 *
 * <p>洗い出しの結果、プラットフォームによって実装が変わるのは<b>ここにあるものだけ</b>だった
 * （配信中の判定・詳細の取得・入力の解釈と、URL 指定のダウンロードで要る
 * 「担当の判定」「メタデータからのチャンネル識別子の解決」）。録画の実行・通知の送信・
 * 履歴の記録・ディスク使用量の集計は、いずれもプラットフォームに依存しない作りになっていたため
 * 共通のまま使える。
 *
 * <p>新しいプラットフォームを足すときは {@link AbstractStreamPlatform} を継承し、
 * {@code @Component} を付ける。{@link StreamPlatformRegistry} が自動的に拾うため、
 * 登録用の一覧を書き足す必要はない。
 */
public interface StreamPlatform {

    /**
     * この実装が担当するプラットフォームを返す。
     *
     * @return プラットフォーム
     */
    Platform platform();

    /**
     * このプラットフォームを今すぐ使えるか。
     *
     * <p>外部サービスの認証情報を必要とするプラットフォームは、未設定のときに {@code false} を返す。
     * 画面はこれを見て「選べるが今は使えない」ことを先に伝えられる。<b>設定漏れに気づくのが
     * 登録ボタンを押した後の失敗メッセージだけ、という状態を避けるため。</b>
     *
     * <p>既定では {@code true}。YouTube は配信中の判定に認証情報が要らないため既定のままでよい
     * （API キーはハンドルの解決・チャンネル検索・通知本文の詳細の取得に使うが、配信中の判定には要らない。
     * 詳細が取れなくても {@link #fallbackDetails} で通知は送れる）。
     *
     * @return 使える状態なら {@code true}
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 登録画面の入力欄に出す説明を返す。
     *
     * <p>受け付ける形式は {@link #normalizeChannelInput} の実装と対になっているため、
     * 文言も同じ場所で管理する。片方だけ変えると「説明どおりに入力したのに弾かれる」ことになる。
     *
     * @return 入力欄に出す説明
     */
    String inputHint();

    /**
     * 利用者が入力した文字列（URL・ハンドル・IDなど）を、このプラットフォームの正規の
     * チャンネル識別子に整える。
     *
     * <p>YouTube のハンドル（{@code @foo}）や Twitch のログイン名のように、
     * 利用者が URL からコピーしがちな形式は、そのままでは監視に使えないことがある。
     * 登録時に必ずここを通して正規の識別子へ揃える。
     *
     * @param rawInput 利用者の入力
     * @return 正規化した識別子
     */
    String normalizeChannelInput(String rawInput);

    /**
     * 動画の URL がこのプラットフォームのものかを判定する。
     *
     * <p>URL 指定のダウンロード（{@code POST /api/downloads}）が、どの実装に処理を任せるかを
     * 決めるために使う（{@link StreamPlatformRegistry#findByUrl}）。
     * <b>呼び出し側でプラットフォームごとの分岐を書かないため</b>の仕組みで、
     * {@link #normalizeChannelInput} が入力の解釈を各実装に任せているのと同じ考え方。
     *
     * <p>判定は {@code yt-dlp} を起動する<b>前</b>に行う。対応していない URL を、
     * プロセスを起動する前に「対応していません」と返せるようにするため
     * （起動してから失敗させると、利用者には「動画情報を取得できませんでした」という
     * 原因の分からない理由しか返せない）。
     *
     * @param url 利用者が入力した動画の URL
     * @return このプラットフォームが担当する URL なら {@code true}
     */
    boolean supportsUrl(String url);

    /**
     * {@code yt-dlp} が取得した動画のメタデータから、このプラットフォームにおける
     * チャンネル識別子（{@link com.example.monitor.entity.MonitoredChannel#youtubeChannelId}
     * と突き合わせられる形）を求める。
     *
     * <p><b>メタデータのどの項目が使えるかはプラットフォームによって違う。</b>
     * YouTube は {@link VideoSource#channelId()} がそのまま使えるが、Twitch の VOD では
     * それが取得できず、代わりに得られる {@link VideoSource#uploaderId()}（ログイン名）は
     * DB に保存している不変のユーザー ID とは別物なので、API で解決し直す必要がある。
     * この違いを呼び出し側に持ち込まないためのメソッド。
     *
     * <p>求められなかった場合は {@link Optional#empty()} を返す。ダウンロードはチャンネルに
     * 紐づかなくても成立するため、<b>ここで例外を投げてダウンロード自体を失敗させない</b>こと。
     *
     * @param source {@code yt-dlp} が返した動画のメタデータ
     * @return チャンネル識別子。求められなかった場合は {@link Optional#empty()}
     */
    Optional<String> resolveChannelId(VideoSource source);

    /**
     * 登録するチャンネルの、プラットフォーム上の公式な名前を取る。
     *
     * <p>利用者が購読のときに表示名を空にした場合だけ使う（{@code MonitoredChannelService.findOrRegister}）。
     * 以前は識別子（{@code UC...} や Twitch の数値 ID）をそのままチャンネル名にしていたため、
     * URL を貼って名前を空のまま追加すると、マイチャンネル・アーカイブ・通知履歴・管理画面のすべてに ID が並び、
     * 利用者には直す手段が無かった。
     *
     * <p><b>取れなくても例外を投げず {@link Optional#empty()} を返すこと。</b>名前は表示のためだけのもので、
     * 取れないせいで購読そのものを失敗させるほどのものではないため（呼び出し側は識別子で代用する）。
     *
     * <p>既定では取らない。公式な名前を取る手段があるプラットフォームだけ上書きする。
     *
     * @param channelId {@link #normalizeChannelInput} で正規化済みの識別子
     * @return 公式な名前。取れなければ {@link Optional#empty()}
     */
    default Optional<String> fetchChannelTitle(String channelId) {
        return Optional.empty();
    }

    /**
     * 1チャンネルが配信中かどうかを調べる。
     *
     * <p><b>「配信していない」と「判定できなかった」を必ず区別すること。</b>
     * 通信エラーや仕様変更で判定できなかった場合に
     * {@link LiveStreamDetection#notLive()} を返すと、検知が壊れていてもアプリは
     * 平常運転に見えるサイレント故障になる。{@link AbstractStreamPlatform#safeDetect}
     * を通せばこの規約は自動的に守られる。
     *
     * <p><b>配信中と判定した場合は視聴 URL も必ず入れること</b>
     * （{@link LiveStreamDetection#watchUrl()}）。URL の組み立て方は
     * プラットフォームによって異なり、配信の識別子だけからは導けないことがあるため、
     * 応答を見ているこの時点でしか作れない。理由は
     * {@link LiveStreamDetection} の JavaDoc に詳しい。
     *
     * @param channelId 正規化済みのチャンネル識別子
     * @return 配信中／配信していない／判定できなかった、のいずれか
     */
    LiveStreamDetection detectLiveStream(String channelId);

    /**
     * 同時に投げる問い合わせの上限。
     *
     * <p>無制限に並べると、短時間に大量のアクセスを送ることになり
     * 相手側から遮断されうる。待ち時間を重ねるのが目的なので、
     * この程度で十分に効く（直列 8.2 秒 → 実測で 2 秒未満）。
     */
    int MAX_CONCURRENT_DETECTIONS = 6;

    /**
     * 複数チャンネルの配信状態をまとめて調べる。
     *
     * <p>既定では1件ずつ {@link #detectLiveStream(String)} を呼ぶ。
     * <b>まとめて問い合わせられるプラットフォームだけ、この実装を上書きすればよい。</b>
     * 例えば Twitch は1リクエストで100チャンネルまで問い合わせられるため上書きする価値があるが、
     * YouTube はチャンネルごとにページを取得するので既定のままでよい。
     *
     * <p>1件ずつ呼ぶ間は {@link ChannelLogContext} でチャンネル別ログへの振り分けを立てている。
     * <b>検知の処理は監視ループの外（このメソッドの中）で動くため、ここで印を立てないと
     * 「配信していません」のような検知時のログが {@code logs/channels/} に残らなくなる。</b>
     *
     * @param channelIds 正規化済みのチャンネル識別子
     * @return 識別子ごとの判定結果（引数の順序を保つ）
     */
    default Map<String, LiveStreamDetection> detectLiveStreams(List<String> channelIds) {
        Map<String, LiveStreamDetection> results = new LinkedHashMap<>();
        if (channelIds.size() <= 1) {
            // 1 件だけならスレッドを起こすほうが高くつく
            for (String channelId : channelIds) {
                results.put(channelId,
                        ChannelLogContext.callWithChannel(channelId, () -> detectLiveStream(channelId)));
            }
            return results;
        }

        // 1 件ずつ順番に取りに行くと、配信者が増えるほど巡回時間が線形に伸びる
        // （実測: YouTube 18 チャンネルで 8.2 秒。ほぼ全てが応答待ちの時間）。
        // 待っているだけの時間なので、仮想スレッドで重ねれば大幅に縮む。
        Semaphore gate = new Semaphore(Math.min(MAX_CONCURRENT_DETECTIONS, channelIds.size()));
        Map<String, Future<LiveStreamDetection>> pending = new LinkedHashMap<>();

        // try-with-resources を抜けるときに全タスクの完了を待つ
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String channelId : channelIds) {
                pending.put(channelId, executor.submit(() -> {
                    // 同時接続数に上限を設ける。無制限に並べると相手side から
                    // 過剰なアクセスとみなされる恐れがあるため
                    gate.acquire();
                    try {
                        return ChannelLogContext.callWithChannel(channelId, () -> detectLiveStream(channelId));
                    } finally {
                        gate.release();
                    }
                }));
            }
        }

        for (Map.Entry<String, Future<LiveStreamDetection>> entry : pending.entrySet()) {
            results.put(entry.getKey(), resultOf(entry.getKey(), entry.getValue()));
        }
        return results;
    }

    /**
     * 並行実行した検知の結果を取り出す。
     *
     * <p>取り出しに失敗しても<b>そのチャンネルを結果から落とさない</b>。
     * 落とすと呼び出し側からは「まだ一度も調べていない」のと区別が付かなくなるため、
     * 「判定できなかった」（{@link LiveStreamDetection#failed()}）として返す
     * （「配信していない」と「判定できなかった」を必ず分ける方針に合わせる）。
     *
     * @param channelId 対象のチャンネル ID
     * @param future    実行中だった検知
     * @return 検知結果。取り出せなければ判定失敗
     */
    private static LiveStreamDetection resultOf(String channelId, Future<LiveStreamDetection> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LiveStreamDetection.failed();
        } catch (ExecutionException e) {
            LoggerFactory.getLogger(StreamPlatform.class)
                    .warn("配信状態の検知が異常終了しました: channel={}", channelId, e.getCause());
            return LiveStreamDetection.failed();
        }
    }

    /**
     * 通知本文を組み立てるのに必要な配信の詳細を取得する。
     *
     * <p>ここでも {@link LiveStreamDetails#watchUrl} に視聴 URL を入れること。
     * 通知の埋め込みリンク先になるため、入れ忘れるとリンク切れの通知が配られる。
     *
     * <p>チャンネルの識別子も受け取るのは、配信 ID だけでは引けないプラットフォームがあるため
     * （Twitch の {@code /helix/streams} は配信 ID での絞り込みに対応していない）。
     *
     * @param channelId 配信元チャンネルの識別子（{@link #normalizeChannelInput} で正規化済みのもの）
     * @param videoId   配信の識別子
     * @return 取得できた詳細。取得できなかった場合は {@link Optional#empty()}
     */
    Optional<LiveStreamDetails> fetchDetails(String channelId, String videoId);

    /**
     * {@link #fetchDetails} で詳細が取れなかったときに、検知結果だけで通知本文の詳細を組み立てる。
     *
     * <p>詳細の取得に API キーやクォータが要るプラットフォームがある（YouTube の {@code videos.list} は
     * クォータ 1）。そこでは、キーの失効・クォータ切れ・障害で詳細が取れないと、通知の再試行の上限に達し、
     * その配信の通知が二度と送られなくなる。<b>検知結果だけで本文を作れるなら、通知が来ないより
     * それで送る方がよい。</b>
     *
     * <p>既定で空を返すのは、Twitch のように、詳細が取れないことが「配信が終わった・別の配信に
     * 切り替わった」を意味するプラットフォームがあるため。そこで検知結果から本文を作ると、
     * 終わった配信を通知してしまう。
     *
     * <p>視聴 URL には {@code detection.watchUrl()} を使うこと。{@link LiveStreamDetails#watchUrl} を
     * 入れ忘れると、リンク切れの通知になる。
     *
     * @param channelName 通知の送り主として出すチャンネル名（DB の表示名）
     * @param detection   配信中と判定された検知結果
     * @return 組み立てた詳細。検知結果からは作らないプラットフォームでは {@link Optional#empty()}
     */
    default Optional<LiveStreamDetails> fallbackDetails(String channelName, LiveStreamDetection detection) {
        return Optional.empty();
    }
}
