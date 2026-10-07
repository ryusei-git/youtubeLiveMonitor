package com.example.monitor.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 端末の状態の画面（{@code /system.html}）が 1 回で読む、実行ユーザーのプロセスの一覧と
 * 左メニューのサービスの状態。
 *
 * <p>プロセスの一覧にほかのユーザーのプロセスを入れないのは、画面から止められるのが実行ユーザーの
 * プロセスだけで、止められないものを並べても選べない行が増えるだけのため。
 *
 * @param measuredAt    一覧を作った時刻（10 秒使い回すので、最大 10 秒古い）
 * @param hostName      端末名
 * @param uptimeSeconds 端末の稼働時間（秒）
 * @param user          実行ユーザー。取れなければ {@code null}
 * @param cores         論理コア数。{@code cpuPercent} が端末全体を 100% とした値なので、1 コア分を
 *                      読み取るため
 * @param services      左メニューのサービスの状態。先頭はこのサービス自身
 * @param processes     実行ユーザーのプロセス（PID の昇順）
 * @param stops         画面から止めたプロセスの記録（新しい順。途中のものと、終わってから 10 分
 *                      以内のもの、最大 20 件）。画面を読み直しても「停止中」と結果を出せるよう、
 *                      サーバーで持つ
 */
public record SystemProcessesResponse(
        LocalDateTime measuredAt,
        String hostName,
        long uptimeSeconds,
        String user,
        int cores,
        List<ServiceStatus> services,
        List<ProcessRow> processes,
        List<StopJob> stops
) {

    /**
     * 左メニューのサービス 1 件の状態。
     *
     * @param name   メニューに出す名前
     * @param url    トップ画面の URL。無ければ {@code null}
     * @param status 動いているか
     * @param launch 起動のしかた。無ければ {@code null}。止まっているときに起動し直せるよう画面に出す
     * @param self   この画面を動かしているサービス自身か（止めるとこの画面も使えなくなる）
     */
    public record ServiceStatus(String name, String url, AppState status, String launch, boolean self) {
    }

    /**
     * サービスが動いているか。
     *
     * <p>{@link #UNKNOWN} を分けるのは、作業フォルダーを読めない OS では「プロセスが無い」と
     * 「見分けられない」を区別できず、{@link #STOPPED} と出すと動いているサービスを止まっていると
     * 誤って伝えるため。
     */
    public enum AppState {
        /** そのサービスのプロセスが 1 つ以上ある。 */
        RUNNING,
        /** プロセスの作業フォルダーは読めたが、そのサービスのものが無い。 */
        STOPPED,
        /** プロセスの作業フォルダーを 1 件も読めず、見分けられない。 */
        UNKNOWN
    }

    /**
     * プロセス 1 つ。
     *
     * @param pid              プロセス ID
     * @param parentPid        親のプロセス ID
     * @param startTime        起動時刻（エポックミリ秒）。PID は使い回されるので、停止のときに画面で
     *                         見ていたのと同じプロセスかを確かめるため
     * @param name             プロセス名。Linux で 15 文字に切れた名前は、実行ファイルの名前で補う
     * @param commandLine      コマンドライン。読めなければ {@code null}
     * @param workingDirectory 作業フォルダー。読めなければ {@code null}
     * @param cpuPercent       直近 1 分の平均の CPU 使用率（端末全体を 100% とした値）。直近の
     *                         1 分ごとの記録の後に始まったプロセスは {@code null}。差を取る前回の
     *                         記録が要るので、アプリの起動から 2 回目の記録までは {@code null}。
     *                         記録の窓の端の扱いで、実際より大きく（100% を超えて）出ることがある
     * @param memoryBytes      実メモリ（RSS）。共有ライブラリなどの共有分を各プロセスで重ねて
     *                         数えるので、足し合わせると実際より大きくなる
     * @param upSeconds        起動からの経過時間（秒）
     * @param ports            待ち受けている TCP のポート（昇順、重複なし）。無い・読めなければ空
     * @param service          どのサービスのプロセスか（子孫を含む）。どれでもなければ {@code null}。
     *                         確認用インスタンスとその子孫も {@code null}
     * @param role             「この画面を動かしているプロセス」と「確認用インスタンス」の印。
     *                         どちらでもなければ {@code null}
     * @param recording        録画中の yt-dlp とその子孫なら、その録画。でなければ {@code null}
     * @param lockedReason     止めてはいけない理由（画面で選べない理由として出す）。止めてよければ
     *                         {@code null}
     * @param stopState        止めている途中の木に入っていれば {@link StopState#STOPPING} か
     *                         {@link StopState#KILLING}、それ以外は {@code null}。止めている途中の
     *                         プロセスを画面でもう一度選ばせないため
     */
    public record ProcessRow(
            int pid,
            int parentPid,
            long startTime,
            String name,
            String commandLine,
            String workingDirectory,
            Double cpuPercent,
            long memoryBytes,
            long upSeconds,
            List<Integer> ports,
            String service,
            Role role,
            RecordingInfo recording,
            String lockedReason,
            StopState stopState
    ) {
    }

    /** 画面で印を付けるプロセスの種類。 */
    public enum Role {
        /** この画面を動かしているアプリ自身。 */
        SELF,
        /** 確認用インスタンス（{@code bin/sandbox.sh}・{@code bin/preview.sh}）の java。 */
        SANDBOX
    }

    /**
     * 録画中のプロセスが録っている録画。エンティティ（{@code Recording}）を返さずに写す。
     *
     * @param id          録画の ID
     * @param channelName チャンネル名。チャンネルが削除された録画・登録外の録画では {@code null}
     * @param videoTitle  配信タイトル
     * @param startedAt   録画を始めた時刻
     * @param fileBytes   その動画の出力ファイル（断片を含む）の大きさの合計。読めなければ 0
     */
    public record RecordingInfo(long id, String channelName, String videoTitle, LocalDateTime startedAt,
                                long fileBytes) {
    }

    /**
     * 画面から止めたプロセスの状態。
     *
     * <p>「強制終了しても残った」を分けるのは、D 状態（I/O 待ちで止まらない）のプロセスは SIGKILL でも
     * 終わらず、「止めた」と出すと残っていることに気付けないため。
     */
    public enum StopState {
        /** SIGTERM を送り、終わるのを待っている。 */
        STOPPING,
        /** 10 秒たっても残っていたので、SIGKILL を送って終わるのを待っている。 */
        KILLING,
        /** SIGTERM で止まった。 */
        STOPPED,
        /** SIGKILL で止まった。 */
        KILLED,
        /** SIGKILL を送っても残った。 */
        FAILED
    }

    /**
     * 画面から止めたプロセス 1 つの記録。DB には残さず、メモリだけで持つ（再起動すると消える。
     * 後から追えるのは監査ログとアプリのログ）。
     *
     * @param id         記録の番号（アプリの起動から振り直す）
     * @param pid        選んだプロセスの ID
     * @param startTime  選んだプロセスの起動時刻（エポックミリ秒）
     * @param name       選んだプロセスの名前
     * @param children   実際に止めに行った子孫の数（止める前に集めた木から数える）
     * @param state      今の状態
     * @param startedAt  止め始めた時刻
     * @param finishedAt 止め終わった時刻。途中なら {@code null}
     * @param remaining  {@link StopState#FAILED} のときに残ったプロセスの ID。それ以外は空
     */
    public record StopJob(long id, int pid, long startTime, String name, int children, StopState state,
                          LocalDateTime startedAt, LocalDateTime finishedAt, List<Integer> remaining) {
    }
}
