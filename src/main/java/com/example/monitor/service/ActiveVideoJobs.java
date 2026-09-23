package com.example.monitor.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自動録画（{@link StreamRecorder}）と手動ダウンロード（{@link VideoDownloadService}）が
 * 共有する、動画IDごとの実行中予約。
 *
 * <h2>なぜ2つの集合を別々に持ってはいけないのか</h2>
 * 以前は {@code StreamRecorder.activeRecordings} と {@code VideoDownloadService.activeDownloads} が
 * それぞれ独立した集合を持ち、必要なときに互いを参照していた。しかしこれでは
 * 「相手の集合を確認する」→「自分の集合に登録する」という2つの操作の間に隙間が生まれる。
 * 手動ダウンロードと自動録画がほぼ同時に同じ動画IDを扱おうとした場合、どちらも
 * 「相手はまだ手を付けていない」と判断してしまい、{@code yt-dlp} を二重に起動できてしまう
 * （実際の指摘：配信中のURLを手動ダウンロードした直後に、次の巡回で同じ配信の自動録画が
 * 始まり、2つのプロセスが同じ出力先に書き込みうる）。
 *
 * <p>予約する集合を1つに統合し、{@link #reserve(String)}の<b>1回の操作</b>で
 * 「確認」と「登録」を不可分にすることで、この隙間を無くしている
 * （{@code ConcurrentHashMap.newKeySet().add()} が確認と登録を同時に行う、という
 * 従来の考え方はそのまま。集合を1つにまとめただけ）。
 *
 * <h2>キーは動画ID</h2>
 * 出力先のパスをキーにしない。自動録画は {@code {チャンネルID}/} 配下、未紐づけの
 * 手動ダウンロードは {@code downloads/} 配下と保存先ディレクトリの決め方が異なるため、
 * パスをキーにすると別々の予約として扱われてしまい排他が成立しない。同じ動画IDであれば
 * 保存先が違っても二重取得に意味は無いので、動画IDをそのままキーにする
 * （{@link RecordingReconciler} が問い合わせるキーとも揃えている）。
 *
 * <h2>{@code util} ではなく {@code service} に置く理由</h2>
 * 予約状態という可変の状態を持つため、状態を持たない static メソッド用の {@code util}
 * パッケージには置かない（プロジェクトの方針）。Spring の Bean として1つのインスタンスを
 * 両サービスと {@link RecordingReconciler} で共有する。
 */
@Component
public class ActiveVideoJobs {

    /**
     * 予約中の動画ID。
     *
     * <p>{@link #reserve(String)} が追加の成否をその場で返すことが排他そのものになっている。
     */
    private final Set<String> reservedVideoIds = ConcurrentHashMap.newKeySet();

    /**
     * 動画IDの予約を試みる。
     *
     * <p>「予約されているかの確認」と「予約への登録」を1回の操作で行う。分けてしまうと、
     * その隙間に別スレッド（自動録画と手動ダウンロードの両方から呼ばれうる）が割り込み、
     * 双方が「まだ予約されていない」と判断して同じ動画IDに対する {@code yt-dlp} を
     * 二重に起動しうる。
     *
     * @param videoId 予約したい動画ID
     * @return 予約できた場合（＝この呼び出し以前は予約されていなかった場合） {@code true}。
     *         既に予約済みだった場合は {@code false}
     */
    public boolean reserve(String videoId) {
        return reservedVideoIds.add(videoId);
    }

    /**
     * 予約を解放する。
     *
     * <p>起動そのものに失敗した場合や、録画・ダウンロードが完了した場合に呼ぶ。
     * 呼び忘れると、その動画IDは以降永久に録画・ダウンロードできなくなる。
     *
     * <p><b>プロセスがまだ出力先に書き込んでいる間は呼んではならない。</b>解放した直後に
     * 同じ動画IDで新しい録画・ダウンロードが始まりうるため、呼ぶのは実際にプロセスが
     * 終了したことを確認した後にする（{@link StreamRecorder#startRecording} の
     * 録画履歴登録失敗時の後始末を参照）。
     *
     * @param videoId 解放したい動画ID
     */
    public void release(String videoId) {
        reservedVideoIds.remove(videoId);
    }

    /**
     * 指定した動画IDが現在予約中かどうかを返す。
     *
     * <p>{@link RecordingReconciler} が「まだ処理中のものを完成ファイルの有無だけで
     * 失敗と誤判定しない」ために使う。
     *
     * @param videoId 確認したい動画ID
     * @return 予約中であれば {@code true}
     */
    public boolean isActive(String videoId) {
        return reservedVideoIds.contains(videoId);
    }
}
