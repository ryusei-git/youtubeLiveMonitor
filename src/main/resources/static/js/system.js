// @ts-check
// 画面固有の状態をグローバルへ漏らさないため、全体を即時実行関数で包む（audit.js と同じ）。
// 各画面のスクリプトは <script> で読み込まれ、既定では common.js と同じスコープを共有するため。
// 状態を持たない計算だけは、node のテスト（static-script.cjs）が名前で取り出せるよう外に置く。
// すべての js を 1 つのスコープで型検査するので、名前はほかの js と重ならないよう system で始める。

/**
 * グラフの縦軸の上端にする、切りのよい数。目盛りの文字を「4.37 MB/s」のような半端な数に
 * せず、一目で読めるようにするため。
 * @param {number} value 系列の最大値（目盛りの単位に直した数）
 * @returns {number} value 以上で最も小さい 1・2・5 × 10 の累乗。value が 0 以下なら 1
 *                   （上端を 0 にすると、値を上端で割る縦位置が出せないため）
 */
function systemNiceMax(value) {
    if (!(value > 0)) return 1;
    const power = 10 ** Math.floor(Math.log10(value));
    return ([1, 2, 5].find((m) => m * power >= value) ?? 10) * power;
}

/**
 * グラフの時刻の目盛りを置く時刻。計測の時刻（秒まで半端）ではなく「21:00」「21:30」のような
 * 切りのよい時刻に置き、目盛りから時刻を読み取りやすくするため。
 * @param {number} startMillis 左端（ミリ秒）。ちょうど切りのよい時刻でも含めない
 * @param {number} endMillis   右端（ミリ秒）
 * @param {number} stepMinutes 間隔（分）。60 の約数
 * @returns {number[]} 端末の時計で分が stepMinutes の倍数ちょうど（秒 0）になる時刻（ミリ秒）。
 *                     古い順
 */
function systemTimeTicks(startMillis, endMillis, stepMinutes) {
    const tick = new Date(startMillis);
    tick.setMinutes(Math.floor(tick.getMinutes() / stepMinutes) * stepMinutes, 0, 0);
    /** @type {number[]} */
    const ticks = [];
    while (tick.getTime() <= endMillis) {
        if (tick.getTime() > startMillis) ticks.push(tick.getTime());
        tick.setMinutes(tick.getMinutes() + stepMinutes);
    }
    return ticks;
}

/**
 * プロセスの表の行の分け方。同じ名前の子（chrome の下の chrome など）を親の行にたたむ。
 *
 * <p>いちばん上の親でまとめないのは、実行ユーザーのプロセスはほぼすべて systemd --user の子孫で、
 * 全部が 1 行に集まって使えないため（レビュー #13）。名前が変わったところで行を分けるので、
 * chrome の下の cat や、sh の下の sleep は別の行になる。
 *
 * @param {Array<{pid: number, parentPid: number, name: string}>} processes プロセス（行の並び順）
 * @returns {Array<{head: number, members: number[]}>} 行ごとの、頭の PID と、たたむプロセスの PID
 *          （先頭は head、残りは processes の順）。行は頭の processes の順
 */
function systemGroupRows(processes) {
    const byPid = new Map(processes.map((p) => [p.pid, p]));
    const heads = processes.map((p) => {
        let current = p;
        // 辿る回数に上限を置く。読み出しの時刻のずれで親子が輪になった一覧でも止まるように
        for (let step = 0; step < processes.length; step++) {
            const parent = byPid.get(current.parentPid);
            if (!parent || parent === current || parent.name !== current.name) return current.pid;
            current = parent;
        }
        return p.pid;
    });
    /** @type {Map<number, number[]>} */
    const rows = new Map();
    processes.forEach((p, i) => { if (heads[i] === p.pid) rows.set(p.pid, [p.pid]); });
    processes.forEach((p, i) => { if (heads[i] !== p.pid) rows.get(heads[i])?.push(p.pid); });
    return [...rows].map(([head, members]) => ({ head, members }));
}

(() => {
    /**
     * 左メニューのサービス 1 件（Java の {@code SystemProcessesResponse.ServiceStatus}）。
     * @typedef {object} HubServiceStatus
     * @property {string} name メニューに出す名前
     * @property {string|null} url トップ画面の URL。無ければ null
     * @property {"RUNNING"|"STOPPED"|"UNKNOWN"} status 動いているか
     * @property {string|null} launch 起動のしかた。無ければ null
     * @property {boolean} self この画面を動かしているサービス自身か
     */
    /**
     * {@code GET /api/system/processes} の応答のうち、この画面で使う分。
     * @typedef {object} HostProcesses
     * @property {string} hostName 端末名
     * @property {number} uptimeSeconds 端末の稼働時間（秒）
     * @property {string|null} user 実行ユーザー。取れなければ null
     * @property {number} cores 論理コア数
     * @property {HubServiceStatus[]} services 左メニューのサービス。先頭はこのサービス自身
     * @property {HostProcess[]} processes 実行ユーザーのプロセス（PID の昇順）
     * @property {ProcessStopJob[]} stops 画面から止めたプロセスの記録（途中のものと、終わって 10 分
     *                                   以内のもの。新しい順）
     */
    /**
     * プロセス 1 つ（Java の {@code SystemProcessesResponse.ProcessRow}）。分からない値は null。
     * @typedef {object} HostProcess
     * @property {number}                pid              プロセス ID
     * @property {number}                parentPid        親のプロセス ID
     * @property {number}                startTime        起動時刻（エポックミリ秒）
     * @property {string}                name             プロセス名
     * @property {string|null}           commandLine      コマンドライン
     * @property {string|null}           workingDirectory 作業フォルダー
     * @property {number|null}           cpuPercent       直近 1 分の平均の CPU 使用率（端末全体が
     *                                                     100%）
     * @property {number}                memoryBytes      実メモリ（RSS）
     * @property {number}                upSeconds        起動からの秒数
     * @property {number[]}              ports            待ち受けている TCP のポート（昇順）
     * @property {string|null}           service          どのサービスのプロセスか
     * @property {"SELF"|"SANDBOX"|null} role             この画面・確認用インスタンスの印
     * @property {HostRecording|null}    recording        録画中なら、その録画
     * @property {string|null}           lockedReason     選べない理由
     * @property {"STOPPING"|"KILLING"|null} stopState    止めている途中なら、その段階
     */
    /**
     * 録画中のプロセスが録っている録画（Java の {@code SystemProcessesResponse.RecordingInfo}）。
     * @typedef {object} HostRecording
     * @property {number}      id          録画の ID
     * @property {string|null} channelName チャンネル名。チャンネルを削除した録画などでは null
     * @property {string}      videoTitle  配信タイトル
     * @property {string}      startedAt   録画を始めた時刻
     * @property {number}      fileBytes   ここまでの出力ファイルの大きさの合計
     */
    /**
     * 画面から止めたプロセス 1 つの記録（Java の {@code SystemProcessesResponse.StopJob}）。
     * @typedef {object} ProcessStopJob
     * @property {number}      id         記録の番号
     * @property {number}      pid        選んだプロセスの ID
     * @property {number}      startTime  選んだプロセスの起動時刻（エポックミリ秒）
     * @property {string}      name       選んだプロセスの名前
     * @property {number}      children   一緒に止めた子孫の数
     * @property {"STOPPING"|"KILLING"|"STOPPED"|"KILLED"|"FAILED"} state 今の段階
     * @property {string|null} finishedAt 止め終わった時刻。途中なら null
     * @property {number[]}    remaining  強制終了しても残ったプロセスの ID
     */
    /** @typedef {"success"|"info"|"warning"|"error"|"progress"} FlashKind お知らせの種類 */
    /**
     * お知らせ 1 件。
     * @typedef {object} Flash
     * @property {FlashKind}          kind  種類
     * @property {string}             html  文の HTML
     * @property {(() => void)|null}  retry 「もう一度」で呼ぶ処理。ボタンを出さないなら null
     * @property {HTMLElement}        node  描いた要素
     * @property {number}             timer 自動で消すタイマー。消さないなら 0
     */
    /**
     * 結果を待っている停止 1 つ。
     * @typedef {object} PendingStop
     * @property {string}      flash お知らせの key
     * @property {boolean}     own   この画面で止め始めたか。読み込む前から途中だった停止は、
     *                               終わってもフォーカスを動かさない（キーボードの位置を勝手に
     *                               変えないため）
     * @property {string|null} next  終わったときにフォーカスを移す行の key。無ければ検索欄
     * @property {number}      since 待ち始めた時刻（ミリ秒）。これより前に頼んだ一覧には記録が無い
     *                               ことがあるので、記録が消えたとは見なさない
     * @property {ProcessStopJob["state"]|null} state お知らせに出した段階。同じ段階なら出し直さない
     *                               （✕ で消した進行中のお知らせが、読み直しのたびに戻らないため）
     */
    /**
     * 止まるのを待っている録画 1 つ。
     * @typedef {object} PendingRecording
     * @property {string}      subject   お知らせに出す「「タイトル」の録画」の HTML
     * @property {number}      startedAt 停止を頼んだ時刻（ミリ秒）
     * @property {string|null} next      止まったときにフォーカスを移す行の key。無ければ検索欄
     */
    /**
     * 確認ダイアログの中身。
     * @typedef {object} StopModalContent
     * @property {string} title       題
     * @property {string} confirm     確定のボタンの文字
     * @property {string} body        本文の HTML
     * @property {string} describedBy aria-describedby に入れる id（本文にある方）
     */
    /**
     * 表の 1 行。同じ名前の子をたたんだまとまり（systemGroupRows）に、表に出す値を足したもの。
     * 開いた子の行は、その子 1 つだけのまとまりにする。
     * @typedef {object} ProcessGroup
     * @property {string}        key     行の key（頭の {@code ${pid}:${startTime}}）
     * @property {HostProcess[]} members 頭とたたんだ子（先頭が頭）
     * @property {number|null}   cpu     CPU の合計。1 つでも分からなければ null
     * @property {number}        memory  実メモリの合計
     * @property {number}        seconds 頭の起動からの秒数
     */
    /**
     * 描く 1 行。
     * @typedef {object} ProcessRowView
     * @property {ProcessGroup} group  行の値
     * @property {boolean}      member 開いた子の行か
     */
    /** @typedef {"cpu"|"memory"|"seconds"} ProcessSort 並べ替えの列 */
    /**
     * {@code GET /api/dashboard/resources} の応答のうち、この画面で使う分（Java の
     * {@code ResourceSnapshotResponse}）。分からない値は 0 でなく null で届く。
     * @typedef {object} HostResources
     * @property {string} measuredAt 計測した時刻
     * @property {{cpuPercent: number|null, cores: number, memoryTotalBytes: number,
     *   memoryUsedBytes: number, memoryAvailableBytes: number, swapTotalBytes: number,
     *   swapUsedBytes: number, diskPath: string, diskTotalBytes: number|null,
     *   diskFreeBytes: number|null, networkReceiveBytesPerSecond: number|null,
     *   networkSendBytesPerSecond: number|null}} system 端末全体
     * @property {{cpuPercent: number|null, memoryBytes: number}} registered 左メニューのサービスの合計
     * @property {number} recordingsBytes 録画フォルダーの実ファイルの合計
     * @property {{reserveBytes: number, growthBytesPerHour: number|null,
     *   hoursLeft: number|null}} diskOutlook 新しい録画を始めなくなるまでの見込み
     * @property {ResourceWarning[]} warnings 目安を超えている項目。cpu・memory・swap・disk・core の順
     */
    /** @typedef {{key: string, message: string}} ResourceWarning 目安を超えている項目 1 つ */
    /**
     * 帯と内訳の 1 区間。
     * @typedef {object} UsagePart
     * @property {string}      name      内訳の名前
     * @property {string}      className 帯と印の色（service / other）
     * @property {number|null} percent   使用率（%）。計測できなければ null
     * @property {string}      amount    内訳に出す値
     */
    /**
     * {@code GET /api/dashboard/resources/history} の 1 点のうち、グラフで使う分（Java の
     * {@code ResourceHistoryPoint}）。計測できなかった値は 0 でなく null で届く。
     * @typedef {object} HistoryPoint
     * @property {string}      at                           記録した時刻
     * @property {number|null} systemCpuPercent             端末全体の CPU 使用率
     * @property {number}      systemMemoryUsedPercent      端末全体のメモリ使用率
     * @property {number|null} registeredCpuPercent         左メニューのサービスの CPU の合計
     * @property {number}      registeredMemoryBytes        左メニューのサービスの実メモリの合計
     * @property {number|null} diskFreeBytes                録画の保存先があるディスクの空き
     * @property {number}      recordingsBytes              録画フォルダーの実ファイルの合計
     * @property {number|null} networkReceiveBytesPerSecond 受信量（毎秒）
     * @property {number|null} networkSendBytesPerSecond    送信量（毎秒）
     */
    /**
     * 積み上げのグラフの 1 層。
     * @typedef {object} ChartLayer
     * @property {string}             label  吹き出しの名前
     * @property {string}             color  面と末尾の点の色（CSS の値）
     * @property {string}             edge   上端の線の色（CSS の値）
     * @property {Array<number|null>} values 点ごとの使用率（%）。計測できなかった点は null
     */
    /**
     * グラフの横軸と、その範囲に入る点。
     * @typedef {object} ChartFrame
     * @property {number}         start  左端（ミリ秒）
     * @property {number}         end    右端（ミリ秒）
     * @property {HistoryPoint[]} points 範囲の中の点（古い順）
     * @property {number[]}       xs     点ごとの横位置（0〜1000）
     */
    /** @typedef {{x: number, text: string}} ChartPoint 吹き出しを出す 1 点（横位置 0〜1000・文） */
    /** @typedef {{html: string, points: ChartPoint[]}} ChartView 描いたグラフと、その点 */

    /**
     * 最終更新を「更新できていません」に変えるまでの時間（ミリ秒）。10 秒ごとの読み直しが
     * 1 回失敗しただけでは出さず、30 秒（3 回分）読めなかったら出す。一時の失敗（通信の瞬断
     * など）で毎回警告を出すと、警告に慣れて本当に止まったときに気付けなくなるため
     * （レビュー #7）。
     */
    const STALE_MILLIS = 30_000;

    /** 警告のアイコン（三角に !）。最終更新が古いことを、色だけでなく形でも伝えるため。 */
    const WARNING_ICON = '<svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor"'
        + ' stroke-width="1.5" stroke-linejoin="round" stroke-linecap="round" aria-hidden="true">'
        + '<path d="M8 1.8l6.5 11.7h-13zM8 6.3v3.2M8 11.5v.2"/></svg>';

    /**
     * 新しいタブで開くことを示すアイコン。押す前に、この画面から離れないことが分かるように
     * する（レビュー #55）。
     */
    const EXTERNAL_ICON = '<svg class="navExternal" viewBox="0 0 16 16" fill="none"'
        + ' stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" stroke-linecap="round"'
        + ' aria-hidden="true"><path d="M9.5 1.5h5v5M14.5 1.5L8 8M12.5 9.5v5h-11v-11h5"/></svg>';

    /**
     * サービスの状態ごとの名前・印・形。色だけで見分けさせず、チェック・横棒・点線の丸と
     * 形でも分ける（色覚の違いがあっても読めるように。レビュー #35）。
     * @type {Record<HubServiceStatus["status"], {label: string, className: string, shape: string}>}
     */
    const SERVICE_STATE = {
        RUNNING: {
            label: "稼働中", className: "is-running",
            shape: '<circle cx="8" cy="8" r="6.5"/><path d="M5 8l2 2 4-4"/>',
        },
        STOPPED: {
            label: "停止済み", className: "is-stopped",
            shape: '<circle cx="8" cy="8" r="6.5"/><path d="M5 8h6"/>',
        },
        UNKNOWN: {
            label: "状態不明", className: "is-unknown",
            shape: '<circle cx="8" cy="8" r="6.5" stroke-dasharray="2 2"/>',
        },
    };

    /**
     * 注意の key ごとに、色を付ける枠。1 つの枠に 2 つ当たれば、warnings の並びで先の方を出す。
     * 基準は JS で決めず、サーバーの warnings の key だけで決める。同じ基準を 2 か所に持つと、
     * 片方だけ直したときに枠の色と注意の文が食い違うため。
     * @type {Record<string, string>}
     */
    const WARNING_PANEL = { cpu: "cpu", core: "cpu", memory: "memory", swap: "memory", disk: "disk" };

    /** @type {Record<string, string>} 読み上げで、どの枠の注意・グラフかを言うための名前 */
    const PANEL_OWNER = {
        cpu: "CPU の", memory: "メモリの", disk: "ディスクの", network: "ネットワークの",
    };

    /** グラフを描く枠。HTML の id は {@code <key>Chart} */
    const CHART_KEYS = ["cpu", "memory", "disk", "network"];

    /**
     * グラフの横軸の幅（ミリ秒）。記録の長さで幅を変えない。変えると、起動して数分の記録が
     * 枠いっぱいに伸び、3 時間の推移と同じ形に見えるため（レビュー #22）。
     */
    const CHART_SPAN_MILLIS = 3 * 60 * 60 * 1000;

    /** 吹き出しの区切り（全角の空白）。半角だけでは、名前と値の切れ目と見分けにくいため */
    const TIP_SEPARATOR = "　";

    /**
     * CPU の注意の基準（%。サーバーの {@code CPU_WARNING_PERCENT} と同じ）。帯の印と、注意が
     * 出る手前の色に使う。注意は 5 分続いてから出るので、その間も今の値が高いことは見せるため。
     */
    const CPU_MARK = 85;
    /** メモリとディスクの注意の基準（%）。帯の印だけに使う */
    const USAGE_MARK = 90;

    /** 表の 1 ページの行の数（開いた子の行は数えない） */
    const PAGE_SIZE = 10;

    /** @type {Record<"SELF"|"SANDBOX", string>} 役割のバッジの文字 */
    const ROLE_LABEL = { SELF: "この画面", SANDBOX: "確認用" };

    /** @type {Record<ProcessSort, (group: ProcessGroup) => number|null>} 並べ替えに使う値 */
    const SORT_VALUE = { cpu: (g) => g.cpu, memory: (g) => g.memory, seconds: (g) => g.seconds };

    /** 選べない行の鍵のアイコン */
    const LOCK_ICON = '<svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor"'
        + ' stroke-width="1.5" stroke-linejoin="round" aria-hidden="true">'
        + '<rect x="3" y="7" width="10" height="7.5" rx="1"/>'
        + '<path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2"/></svg>';

    /** @param {string} path 線 @returns {string} 線で描くアイコン（ページ送り・お知らせ） */
    const lineIcon = (path) => '<svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor"'
        + ' stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
        + `<path d="${path}"/></svg>`;

    /** 丸の輪郭（お知らせのアイコンの外枠） */
    const CIRCLE_PATH = "M8 1.5a6.5 6.5 0 1 1 0 13a6.5 6.5 0 1 1 0-13z";

    /**
     * お知らせと注意の種類ごとのアイコン。成功と失敗を色だけで見分けさせず、形も変える。
     * @type {Record<FlashKind, string>}
     */
    const FLASH_ICON = {
        success: lineIcon(`${CIRCLE_PATH}M5 8l2 2 4-4`),
        info: lineIcon(`${CIRCLE_PATH}M8 7.5v4M8 4.8v.2`),
        warning: WARNING_ICON,
        error: lineIcon(`${CIRCLE_PATH}M5.8 5.8l4.4 4.4M10.2 5.8l-4.4 4.4`),
        progress: '<span class="spinner" aria-hidden="true"></span>',
    };

    /** お知らせを閉じる ✕ */
    const CLOSE_ICON = lineIcon("M4 4l8 8M12 4l-8 8");

    /**
     * 成功・情報・注意のお知らせを自動で消すまでの時間（ミリ秒）。失敗と進行中は消さない。
     * 止まらなかったことや、まだ止めている途中であることを、読む前に消して見落とさせないため
     * （レビュー #4）。
     */
    const FLASH_MILLIS = 60_000;

    /**
     * 並べるお知らせの数。超えたら、自動で消える種類の古いものから消す。続けて止めても結果を
     * 1 件ずつ残しつつ、表を覆い隠すほど積まないため（レビュー #10）。
     */
    const FLASH_LIMIT = 3;

    /** @type {Set<FlashKind>} 自動で消し、数が多いときに先に消すお知らせの種類 */
    const TRANSIENT_FLASH = new Set(["success", "info", "warning"]);

    /** ふだんの読み直しの間隔（ミリ秒）。計測は 1 分ごとなので、10 秒の遅れで出せば足りる */
    const REFRESH_MILLIS = 10_000;

    /**
     * 止めている途中の読み直しの間隔（ミリ秒）。サーバーの一覧は 10 秒使い回すので読み直しは軽く、
     * 停止の記録だけは毎回新しいため、10 秒待たずに結果を出せる。
     */
    const STOPPING_REFRESH_MILLIS = 2_000;

    /**
     * 録画の停止を頼んでから、止められなかったとみなすまでの時間（ミリ秒）。録画は止め終わるまで
     * 最大 30 秒かかり（common.js の stopRecording）、プロセスの一覧はさらに最大 10 秒古いので、
     * その分を待ってから失敗と出す。
     */
    const RECORDING_STOP_MILLIS = 45_000;

    /** @type {Record<"STOPPING"|"KILLING", string>} 止めている途中の行に出す文字 */
    const STOP_STATUS_LABEL = { STOPPING: "停止中", KILLING: "強制終了中" };

    /** @type {HostResources | null} {@code /api/dashboard/resources} の応答。未読は null*/
    let resources = null;
    /** @type {Set<string>} 前回の描画で出ていた注意の key。新しく出た注意だけを読み上げるため */
    let shownWarningKeys = new Set();
    /** @type {HostProcesses | null} {@code /api/system/processes} の応答。未読なら null */
    let system = null;
    /** @type {HistoryPoint[] | null} {@code /api/dashboard/resources/history} の応答。未読は null*/
    let history = null;
    /** 推移を読めたときの今の値の measuredAt。変わったときだけ読み直す（理由は loadHistory） */
    let historyMeasuredAt = "";
    /** @type {Record<string, string>} グラフごとに最後に描いた HTML。変わったときだけ描き直す */
    const chartHtml = {};
    /** @type {Record<string, ChartPoint[]>} グラフごとの点。マウスを重ねた位置に近い点を探すため */
    const chartPoints = {};
    /** 2 つの API を両方読めた時刻（ミリ秒）。まだなら 0 */
    let lastSuccessAt = 0;
    /** 1 回でも読み込みを終えたか。最初の読み込みの最中は、まだ失敗とは言えないので警告しない */
    let settled = false;
    /** 読み込み中か。自動の読み直しの途中で「表示を更新」を押されても、同じ要求を重ねない */
    let loading = false;
    /** 左メニューに最後に描いた HTML。変わったときだけ描き直す（理由は renderNav） */
    let lastNavHtml = "";

    // ---- プロセスの表の状態 ----
    /** @type {"all"|"services"} 出すプロセス（すべて・サービスだけ） */
    let view = "all";
    /** @type {ProcessSort} 並べ替えの列。開いた直後は CPU の多い順 */
    let sort = "cpu";
    /** @type {"desc"|"asc"} 並べ替えの向き */
    let direction = "desc";
    /** 検索語（前後の空白を除いたもの） */
    let filter = "";
    /** 今のページ（1 から） */
    let page = 1;
    /** @type {string|null} 選んだ行の key。選んでいなければ null */
    let selected = null;
    /**
     * @type {string[]} 行の頭の key の並び。並べ直すのは見出しと「表示を更新」を押したときだけで、
     * 10 秒ごとの読み直しでは変えない（読み直しのたびに行が動くと、押そうとした行が逃げるため。
     * レビュー #6）
     */
    let order = [];
    /** @type {Set<string>} 子の行を開いた行の key */
    const expanded = new Set();
    /** 「表示を更新」が押されたか。読み終えて描くときに並べ直す */
    let resortRequested = false;
    /** @type {HTMLButtonElement|null} 選べない理由の吹き出しを開いている鍵 */
    let openLock = null;
    /** 件数の書き換えを待つタイマー（理由は scheduleFilterCount） */
    let filterCountTimer = 0;
    /**
     * @type {WeakMap<Element, string>} 要素ごとに最後に書いた HTML。同じ値を書き直すと、選んだ
     * 文字やフォーカスが消えるため、変わったときだけ書く（setHtml）
     */
    const drawnHtml = new WeakMap();

    // ---- 停止とお知らせの状態 ----
    /** @type {Map<string, Flash>} お知らせ（key ごとに 1 件。並びは出した順） */
    const flashes = new Map();
    /** @type {Map<number, PendingStop>} 結果を待っている停止（停止の記録の id ごと） */
    const pendingStops = new Map();
    /** @type {Map<number, PendingRecording>} 止まるのを待っている録画（録画の id ごと） */
    const pendingRecordings = new Map();
    /**
     * 読み込んだときに途中だった停止を拾ったか。拾うのは最初の 1 回だけにし、読み込む前に
     * 終わっていた停止の結果は出さない（いま操作した結果と取り違えさせないため）
     */
    let stopsSeeded = false;
    /** @type {HostProcess|null} 確認ダイアログで止めようとしているプロセス。開いていなければ null*/
    let modalTarget = null;
    /** @type {string[]} 確認ダイアログで止める範囲の key（選んだプロセスと子孫） */
    let modalTree = [];
    /** 確認ダイアログの背景で mousedown が起きたか（理由は stopModal の click） */
    let pressedBackdrop = false;
    /** 今の読み直しの間隔（ミリ秒） */
    let pollMillis = REFRESH_MILLIS;
    /** 今の読み直しを止める関数（startVisibleRefresh の戻り値） */
    let stopPolling = () => {};

    const sideNav = el("sideNav");
    const navToggle = buttonEl("navToggle");
    const navScrim = el("navScrim");
    const main = el("mainContent");
    const refreshButton = buttonEl("refreshButton");
    const stopButton = buttonEl("stopButton");
    const filterInput = inputEl("processFilter");
    const rowsBox = el("processRows");
    const tableWrap = el("tableWrap");
    const pagination = el("pagination");
    const flashbar = el("flashbar");
    const stopModal = /** @type {HTMLDialogElement} */ (el("stopModal"));
    const modalBody = el("modalBody");
    const modalCancel = buttonEl("modalCancel");
    const modalConfirm = buttonEl("modalConfirm");
    const selectionBar = el("selectionBar");
    const selectionStop = buttonEl("selectionStop");
    /** 選んだ行の帯（#selectionBar）を画面の下に出す幅。system.css の @media と同じ値にする */
    const barWidth = window.matchMedia("(max-width: 600px)");
    /** 左メニューを帯の下に重ねて開け閉めする幅。system.css の @media と同じ値にする */
    const narrow = window.matchMedia("(max-width: 760px)");

    /**
     * 端末の稼働時間を、桁に合わせて 2 つまでの単位で出す。日をまたいで動いている端末で秒まで
     * 並べても読む必要が無く、10 秒ごとに数字が動いて目を引くだけのため。
     * @param {number} seconds 秒
     * @returns {string} 「N 秒」「N 分」「N 時間 M 分」「N 日 M 時間」
     */
    function formatElapsed(seconds) {
        if (seconds < 60) return `${Math.floor(seconds)} 秒`;
        const minutes = Math.floor(seconds / 60);
        if (minutes < 60) return `${minutes} 分`;
        const hours = Math.floor(minutes / 60);
        if (hours < 24) return `${hours} 時間 ${minutes % 60} 分`;
        return `${Math.floor(hours / 24)} 日 ${hours % 24} 時間`;
    }

    /**
     * 時刻を HH:mm（seconds が true なら HH:mm:ss）にする。日付を出さないのは、1 分ごとの
     * 計測の値を見る画面で、今日の値かを疑う場面が無いため。
     * @param {string|number} value     サーバーの日時（ISO 形式。時差なしの LocalDateTime）か、
     *                                  ミリ秒（グラフの目盛り）
     * @param {boolean}       [seconds] 秒まで出すか
     * @returns {string} 整形した時刻
     */
    function formatClock(value, seconds = false) {
        const date = new Date(value);
        const parts = [date.getHours(), date.getMinutes(), ...(seconds ? [date.getSeconds()] : [])];
        return parts.map((n) => String(n).padStart(2, "0")).join(":");
    }

    /** @returns {boolean} 「更新できていません」を出す間か（理由は STALE_MILLIS） */
    const isStale = () => settled && (lastSuccessAt === 0 || Date.now() - lastSuccessAt > STALE_MILLIS);

    /**
     * 見出しの端末名・稼働時間・最終更新を描く。
     *
     * <p>最終更新は、画面が読みに行った時刻ではなく計測した時刻（{@code measuredAt}）を出す。
     * 値は 1 分ごとに計測するので、読みに行った時刻を出すと、10 秒ごとに新しくなったように
     * 見えて、値も新しいと誤解させるため（レビュー #40）。読めなくなって古くなったときは、
     * 警告と「更新できていません」を前に付け、見ている値がいつのものかは残す（レビュー #7）。
     */
    function renderHeader() {
        el("hostName").textContent = system ? system.hostName : "-";
        el("uptime").textContent = system ? formatElapsed(system.uptimeSeconds) : "-";
        const updatedAt = el("updatedAt");
        const measured = resources ? formatClock(resources.measuredAt, true) : "";
        const stale = isStale();
        updatedAt.classList.toggle("is-stale", stale);
        if (stale) {
            const message = measured ? `更新できていません · ${measured}` : "更新できていません";
            updatedAt.innerHTML = WARNING_ICON + escapeHtml(message);
        } else {
            updatedAt.textContent = measured || "-";
        }
    }

    /**
     * 相対の URL（{@code /index.html} など）を、開く先が分かるように絶対の URL にする。
     * サーバーは URL の書き出しだけを確かめるので、{@code http://ryusei:99999/}（ポートの打ち間違い）
     * のような解釈できない URL も届く。そのとき例外で左メニューの全件を描けなくならないよう、
     * 書かれたままを返す。
     * @param {string} url サービスの URL
     * @returns {string} 絶対の URL。解釈できなければ url のまま
     */
    function absoluteUrl(url) {
        try {
            return new URL(url, location.href).href;
        } catch {
            return url;
        }
    }

    /**
     * 左メニューのサービスを描く。
     *
     * <p>描いた HTML が前と同じなら描き直さない。10 秒ごとに描き直すと、キーボードでリンクに
     * フォーカスを置いている間にリンクが作り直され、フォーカスが消えるため。
     * 停止済みのサービスもリンクを残すのは、状態の判定（作業フォルダーでの照合）が外れて
     * いても、画面を開いて確かめられるようにするため（レビュー #55）。
     */
    function renderNav() {
        const html = system ? system.services.map((service) => {
            const state = SERVICE_STATE[service.status];
            const icon = `<svg class="navStatus ${state.className}" viewBox="0 0 16 16" fill="none"`
                + ' stroke="currentColor" stroke-width="1.5" stroke-linecap="round"'
                + ` stroke-linejoin="round" role="img" aria-label="${state.label}">`
                + `${state.shape}</svg>`;
            const name = `<span class="navName">${escapeHtml(service.name)}</span>`;
            if (!service.url) {
                return `<li><span class="navItem" title="${state.label} · 画面の無いサービスです">`
                    + `${icon}${name}</span></li>`;
            }
            const title = `${state.label} · ${absoluteUrl(service.url)} を開きます`;
            return `<li><a class="navItem" href="${escapeHtml(service.url)}" target="_blank"`
                + ` rel="noopener noreferrer" title="${escapeHtml(title)}">`
                + `${icon}${name}${EXTERNAL_ICON}</a></li>`;
        }).join("") : '<li><span class="navItem">取得できませんでした</span></li>';
        if (html === lastNavHtml) return;
        el("serviceNav").innerHTML = html;
        lastNavHtml = html;
    }

    /**
     * GB の数（小数 1 桁）。{@code formatFileSize} と同じ 1024 の換算にし、ほかの画面の容量と
     * 数字が食い違わないようにする。枠の中は単位を GB にそろえ、桁の違う数字を並べないため
     * {@code formatFileSize} は使わない。
     * @param {number} bytes バイト数
     * @returns {string} GB の数
     */
    const gb = (bytes) => (bytes / 1024 ** 3).toFixed(1);

    /**
     * 毎秒のバイト数を出す単位を、受信と送信の大きい方で決める。2 つを同じ単位にそろえ、
     * 「1.01 MB/s と 30.00 KB/s」のように単位の違う数字を見比べさせないため（レビュー #21）。
     * @param {number} max 大きい方の値（毎秒のバイト数）
     * @returns {{size: number, label: string, digits: number}} 割る数・単位・小数の桁
     */
    function rateUnit(max) {
        if (max >= 1024 * 1024) return { size: 1024 * 1024, label: "MB/s", digits: 2 };
        if (max >= 1024) return { size: 1024, label: "KB/s", digits: 2 };
        return { size: 1, label: "B/s", digits: 0 };
    }

    /**
     * 毎秒のバイト数を、並べる値の大きい方で決めた単位にそろえて書く（理由は rateUnit）。
     * @param {Array<number|null>} values 毎秒のバイト数。計測できなかった値は null
     * @returns {string[]} 単位付きの値。null は "-"
     */
    function formatRates(values) {
        const unit = rateUnit(Math.max(0, ...values.map((bytes) => bytes ?? 0)));
        return values.map((bytes) => (bytes === null
            ? "-" : `${(bytes / unit.size).toFixed(unit.digits)} ${unit.label}`));
    }

    /**
     * @param {string} text    値
     * @param {string} [state] 値の色（is-error / is-pending）
     * @param {string} [after] 値の横に置く HTML（単位の位置の量・値が無い理由）
     * @returns {string} 大きな値の HTML
     */
    const valueLarge = (text, state = "", after = "") =>
        `<div class="valueLarge ${state}">${escapeHtml(text)}${after}</div>`;
    /** @param {string} text 量 @returns {string} 単位の位置に出す量の HTML */
    const valueUnit = (text) => `<span class="valueUnit">${escapeHtml(text)}</span>`;
    /** @param {string} text 理由 @returns {string} 値が無い理由の HTML */
    const valueNote = (text) => `<span class="valueNote">${escapeHtml(text)}</span>`;

    /**
     * @param {number} percent     位置（%）
     * @param {string} [className] is-limit なら録画の下限の印
     * @returns {string} 帯の印の HTML
     */
    const meterMark = (percent, className = "") =>
        `<span class="meterMark ${className}" style="left:${percent}%"></span>`;

    /**
     * @param {Array<{name: string, value: string, marker?: string, className?: string}>} items
     *        名前・値・名前の前の印の HTML・値の色
     * @returns {string} 内訳の HTML
     */
    function factsHtml(items) {
        return `<dl class="facts">${items.map((item) => `<div><dt>${item.marker ?? ""}`
            + `${escapeHtml(item.name)}</dt><dd class="${item.className ?? ""}">`
            + `${escapeHtml(item.value)}</dd></div>`).join("")}</dl>`;
    }

    /**
     * CPU・メモリ・ディスクの今の値（大きな使用率・帯・内訳）を、3 つで同じ形に描く。
     *
     * <p>内訳の区間が 1 つでも計測できないときは、帯を色分けしない 1 本にする。全体から
     * 引いて「そのほか」を出すと NaN になるうえ、全体を「そのほか」と読ませるため。
     *
     * @param {number}                    percent 使用率（%）
     * @param {string}                    unit    単位の位置に出す量。無ければ空
     * @param {UsagePart[]}               parts   帯と内訳の区間（帯の左から）
     * @param {string}                    marks   帯の印の HTML
     * @param {string}                    state   大きな値の色（is-error / is-pending）。無ければ空
     * @param {ResourceWarning|undefined} warning この枠の注意
     * @param {Array<{name: string, value: string, className?: string}>} [extra]
     *                                            内訳の後に足す項目
     * @returns {string} 差し込む HTML
     */
    function usageHtml(percent, unit, parts, marks, state, warning, extra = []) {
        const known = parts.every((part) => part.percent !== null);
        const breakdown = known
            ? parts.map((part) => `${part.name} ${formatPercent(part.percent)}`).join("、")
            : "内訳は計測できませんでした";
        const caution = warning ? `。注意: ${warning.message}` : "";
        const label = `使用率 ${formatPercent(percent)}（${breakdown}）${caution}`;
        /** @type {Array<{className: string, percent: number|null}>} */
        const segments = known ? parts : [{ className: "other", percent }];
        const bars = segments.filter((part) => (part.percent ?? 0) > 0)
            .map((part) => `<span class="seg ${part.className}" style="width:${part.percent}%"></span>`)
            .join("");
        return valueLarge(formatPercent(percent), state, unit ? valueUnit(unit) : "")
            + `<div class="meterWrap"><div class="meter" role="img" aria-label="${escapeHtml(label)}">`
            + `${bars}</div>${marks}</div>`
            + factsHtml([...parts.map((part) => ({
                name: part.name, value: part.amount,
                marker: `<span class="marker ${part.className}"></span>`,
            })), ...extra]);
    }

    /**
     * CPU の今の値。
     *
     * <p>全体の値が null になるのは、前回の記録が無い起動直後と、記録の間が 1 秒未満のときだけ
     * （前回の記録との差から出すため）。測るのに失敗したと読ませないよう「次の計測を待って
     * います」と出す（レビュー #16）。
     *
     * @param {HostResources|null}        r       計測の値。未読なら null
     * @param {ResourceWarning|undefined} warning この枠の注意
     * @returns {string} 差し込む HTML
     */
    function cpuHtml(r, warning) {
        if (!r) return valueLarge("-");
        const total = r.system.cpuPercent;
        if (total === null) return valueLarge("-", "", valueNote("次の計測を待っています"));
        // サービスの値は、分からないプロセスが 1 つでもあると null。全体とプロセスの測り方が
        // 違い、引くと負になりうるので 0 で止める
        const service = r.registered.cpuPercent;
        const other = service === null ? null : Math.max(total - service, 0);
        const state = warning ? "is-error" : total > CPU_MARK ? "is-pending" : "";
        return usageHtml(total, "", [
            {
                name: "サービス", className: "service", percent: service,
                amount: formatPercent(service),
            },
            { name: "そのほか", className: "other", percent: other, amount: formatPercent(other) },
        ], meterMark(CPU_MARK), state, warning);
    }

    /**
     * @param {HostResources|null}        r       計測の値。未読なら null
     * @param {ResourceWarning|undefined} warning この枠の注意
     * @returns {string} メモリの今の値の HTML
     */
    function memoryHtml(r, warning) {
        if (!r) return valueLarge("-");
        const { memoryTotalBytes: total, memoryUsedBytes: used } = r.system;
        const serviceBytes = r.registered.memoryBytes;
        const percent = used / total * 100;
        const service = serviceBytes / total * 100;
        return usageHtml(percent, `${gb(used)} / ${gb(total)} GB`, [
            {
                name: "サービス", className: "service", percent: service,
                amount: `${gb(serviceBytes)} GB`,
            },
            {
                name: "そのほか", className: "other", percent: Math.max(percent - service, 0),
                amount: `${gb(Math.max(used - serviceBytes, 0))} GB`,
            },
        ], meterMark(USAGE_MARK), warning ? "is-error" : "", warning);
    }

    /**
     * ディスクの今の値。録画の下限に届くまでの時間は、増えているときと、すでに下回っている
     * ときだけ出す（サーバーが値を入れるのがそのときだけ。常に出す文字を減らすため。
     * レビュー #20）。
     *
     * @param {HostResources|null}        r       計測の値。未読なら null
     * @param {ResourceWarning|undefined} warning この枠の注意
     * @returns {string} 差し込む HTML
     */
    function diskHtml(r, warning) {
        if (!r) return valueLarge("-");
        const { diskTotalBytes: total, diskFreeBytes: free } = r.system;
        if (total === null || free === null) {
            return valueLarge("-", "", valueNote("計測できませんでした"));
        }
        const used = total - free;
        const percent = used / total * 100;
        const recordings = r.recordingsBytes / total * 100;
        const outlook = r.diskOutlook;
        // 設定 0 は空きを確かめない（録画を止めない）ので、下限の印を出さない
        const limit = outlook.reserveBytes > 0
            ? meterMark((total - outlook.reserveBytes) / total * 100, "is-limit") : "";
        /** @type {Array<{name: string, value: string, className?: string}>} */
        const extra = [];
        if (outlook.growthBytesPerHour !== null) {
            extra.push({ name: "増加", value: `${gb(Math.max(outlook.growthBytesPerHour, 0))} GB/時` });
        }
        if (outlook.hoursLeft === 0) {
            extra.push({
                name: "録画できるのは", value: "新しい録画は始まりません", className: "is-error",
            });
        } else if (outlook.hoursLeft !== null) {
            extra.push({ name: "録画できるのは", value: `あと 約 ${outlook.hoursLeft} 時間` });
        }
        return usageHtml(percent, `${gb(used)} / ${gb(total)} GB`, [
            {
                name: "録画", className: "service", percent: recordings,
                amount: `${gb(r.recordingsBytes)} GB`,
            },
            {
                name: "そのほか", className: "other", percent: Math.max(percent - recordings, 0),
                amount: `${gb(Math.max(used - r.recordingsBytes, 0))} GB`,
            },
        ], meterMark(USAGE_MARK) + limit, warning ? "is-error" : "", warning, extra);
    }

    /**
     * ネットワークの今の値。大きな値は置かず、受信と送信を内訳の形で並べる。使用率に当たる
     * 上限が無く、大きな値にすると、ほかの 3 枠の使用率と同じ物差しで見比べさせるため
     * （レビュー #42）。null は CPU と同じく次の計測を待っている間だけ。
     *
     * @param {HostResources|null} r 計測の値。未読なら null
     * @returns {string} 差し込む HTML
     */
    function networkHtml(r) {
        const receive = r ? r.system.networkReceiveBytesPerSecond : null;
        const send = r ? r.system.networkSendBytesPerSecond : null;
        const values = receive !== null && send !== null
            ? formatRates([receive, send]) : ["-", "-"];
        const waiting = r && (receive === null || send === null)
            ? valueNote("次の計測を待っています") : "";
        return factsHtml([
            { name: "受信", value: values[0], marker: '<span class="lineMarker receive"></span>' },
            { name: "送信", value: values[1], marker: '<span class="lineMarker send"></span>' },
        ]) + waiting;
    }

    /**
     * 枠の右に出す、注意の短い理由。見出しの行に収まる長さにし、全文（message）は帯の
     * 読み上げと {@code #liveStatus} に回す（常に出す文字を減らすため。レビュー #15）。
     *
     * @param {string}        key 注意の key
     * @param {HostResources} r   計測の値
     * @returns {string} 短い理由
     */
    function shortReason(key, r) {
        const s = r.system;
        // 空きは切り捨てる。四捨五入すると 9.96% が「空き 10.0%」になり、「10% を下回って
        // います」の注意と食い違うため
        /** @type {(bytes: number, total: number) => string} */
        const free = (bytes, total) => `空き ${formatPercent(Math.floor(bytes / total * 1000) / 10)}`;
        if (key === "cpu") return "85% 超えが 5 分";
        if (key === "core") return "1 コアを 5 分使い切り";
        if (key === "memory") return free(s.memoryAvailableBytes, s.memoryTotalBytes);
        // スワップは、この注意が出ている間だけ値を見せる（常に出す文字を減らすため。レビュー #17）
        if (key === "swap") return `スワップ ${(s.swapUsedBytes / s.swapTotalBytes * 100).toFixed(0)}%`;
        // disk は「空きが 10% 未満」と「録画の下限に近い・下回った」のどちらか。両方に当たるとき
        // サーバーは 10% の方だけを出すので、ここも 10% の方を先に見る
        if (s.diskTotalBytes !== null && s.diskFreeBytes !== null
            && s.diskFreeBytes < s.diskTotalBytes * 0.1) {
            return free(s.diskFreeBytes, s.diskTotalBytes);
        }
        const hoursLeft = r.diskOutlook.hoursLeft;
        return hoursLeft === 0 ? "録画を始めません" : `あと 約 ${hoursLeft} 時間`;
    }

    /**
     * 4 つの枠に今の値・内訳・注意を描く。読み直しに失敗したときは前の値のまま描く（古いことは
     * 見出しの「更新できていません」で知らせる）。
     *
     * <p>注意は枠の色だけでなく、右の理由の文字と帯の読み上げでも伝える（色を見分けにくい人や
     * 読み上げで使う人にも届くように。レビュー #15）。読み上げの {@code #liveStatus} には、
     * 前回の描画に無かった注意だけを入れる。10 秒ごとに同じ注意を読み上げ続けないため。
     */
    function renderResources() {
        const r = resources;
        const warnings = r ? r.warnings : [];
        /** @type {Record<string, ResourceWarning|undefined>} */
        const warned = {};
        for (const warning of warnings) {
            const panel = WARNING_PANEL[warning.key];
            if (panel && !warned[panel]) warned[panel] = warning;
        }
        const panels = {
            cpu: cpuHtml(r, warned.cpu),
            memory: memoryHtml(r, warned.memory),
            disk: diskHtml(r, warned.disk),
            network: networkHtml(r),
        };
        for (const [key, html] of Object.entries(panels)) {
            const warning = warned[key];
            el(`${key}Panel`).classList.toggle("is-warned", Boolean(warning));
            const status = el(`${key}Status`);
            status.hidden = !warning;
            status.innerHTML = warning && r
                ? WARNING_ICON + escapeHtml(shortReason(warning.key, r)) : "";
            el(`${key}Now`).innerHTML = html;
        }
        if (r) {
            el("cpuCores").textContent = String(r.system.cores);
            el("diskPath").textContent = r.system.diskPath;
            el("diskReserve").textContent = `${Math.round(r.diskOutlook.reserveBytes / 1024 ** 3)} GB`;
        }
        el("liveStatus").textContent = warnings
            .filter((warning) => WARNING_PANEL[warning.key] && !shownWarningKeys.has(warning.key))
            .map((warning) => `${PANEL_OWNER[WARNING_PANEL[warning.key]]}注意: ${warning.message}`)
            .join(" ");
        shownWarningKeys = new Set(warnings.map((warning) => warning.key));
    }

    /**
     * @param {number} value 値
     * @param {number} max   縦軸の上端の値
     * @returns {number} 縦位置（0〜100。上端が 0）。範囲の外の値は上端・下端に止める。SVG は
     *                   overflow: visible なので、止めないと線がグラフの外（時刻の文字の上）に出る
     *                   （下限の設定がディスクの合計を超えると、録画の下限は負の % になる）
     */
    const yAt = (value, max) => 100 - Math.min(Math.max(value / max, 0), 1) * 100;

    /**
     * @param {number} at    時刻（ミリ秒）
     * @param {number} start 左端の時刻（ミリ秒）
     * @returns {number} 横位置（0〜1000）
     */
    const xAt = (at, start) => (at - start) / CHART_SPAN_MILLIS * 1000;

    /**
     * 横軸を、今の値を計測した時刻を右端、その 3 時間前を左端にして決め、範囲の外の点を捨てる。
     * 記録の無い左側は詰めずに空け、再起動した位置（そこより前の推移は消えている）を見せる。
     * @param {HistoryPoint[]} points     推移（古い順）
     * @param {string}         measuredAt 今の値を計測した時刻
     * @returns {ChartFrame} 横軸と範囲の中の点
     */
    function chartFrame(points, measuredAt) {
        const end = new Date(measuredAt).getTime();
        const start = end - CHART_SPAN_MILLIS;
        const inRange = points.map((point) => ({ point, at: new Date(point.at).getTime() }))
            .filter(({ at }) => at >= start && at <= end);
        return {
            start, end,
            points: inRange.map(({ point }) => point),
            xs: inRange.map(({ at }) => xAt(at, start)),
        };
    }

    /**
     * @param {number[]}           xs 横位置
     * @param {Array<number|null>} ys 縦位置。null の点で線を切る（計測できなかった時間を 0 と
     *                                読ませないため。レビュー #16）
     * @returns {string} path の d
     */
    const linePath = (xs, ys) => ys.map((y, i) => {
        if (y === null) return "";
        return `${i > 0 && ys[i - 1] !== null ? "L" : "M"}${xs[i].toFixed(1)} ${y.toFixed(2)}`;
    }).join("");

    /**
     * 基準の横の破線（注意の基準。レビュー #41。録画の下限。レビュー #20）。面の後に描き、面に
     * 隠れないようにする。
     * @param {number} y     縦位置（0〜100）
     * @param {string} color 色（CSS の値）
     * @param {string} dash  破線の刻み
     * @returns {string} SVG の線
     */
    const refLine = (y, color, dash) => `<line x1="0" x2="1000" y1="${y.toFixed(2)}"`
        + ` y2="${y.toFixed(2)}" style="stroke:${color}" stroke-dasharray="${dash}"`
        + ' stroke-width="1" vector-effect="non-scaling-stroke"/>';

    /**
     * 系列の末尾の点。最後の値が null なら出さない（計測できなかった今の値を、点の位置で
     * 読ませないため。レビュー #16）。
     * @param {ChartFrame}  frame 横軸と範囲の中の点
     * @param {number|null} value 末尾の縦の値（積み上げなら層の上端）
     * @param {number}      max   縦軸の上端の値
     * @param {string}      color 色（CSS の値）
     * @returns {string} 点の HTML
     */
    function endDot(frame, value, max, color) {
        if (value === null) return "";
        const x = frame.xs[frame.xs.length - 1] / 10;
        return `<span class="endDot" style="left:${x}%;top:${yAt(value, max)}%;`
            + `background:${color}"></span>`;
    }

    /**
     * @param {Array<number|null>} values 値
     * @returns {number} 最大の値の位置。計測できた値が無ければ -1
     */
    function peakIndex(values) {
        let peak = -1;
        values.forEach((value, i) => {
            if (value !== null && (peak < 0 || value > (values[peak] ?? 0))) peak = i;
        });
        return peak;
    }

    /**
     * 線と面を伸び縮みする SVG（横 0〜1000・縦 0〜100）で描き、目盛りの文字を HTML で重ねる。
     * 高さを決めて枠の幅いっぱいに伸ばしても、文字が潰れたり大きくなりすぎたりしないため
     * （common.js の lineChart は図を丸ごと伸ばすので、文字も一緒に伸びる）。
     *
     * <p>時刻の文字は 1 時間ごとと両端の 2 つを持ち、CSS が画面の幅でどちらかを出す。狭い画面で
     * 1 時間ごとの文字を並べると重なって読めないため。
     *
     * @param {ChartFrame} frame  横軸と範囲の中の点
     * @param {string}     label  読み上げの名前（レビュー #52）
     * @param {number}     max    縦軸の上端の値
     * @param {Array<{value: number, text: string}>} ticks
     *                            縦軸の目盛り。上端の値のものは、狭い画面でも出す
     * @param {string}     svg    面・線・基準の線の SVG
     * @param {string}     dots   末尾の点の HTML
     * @param {(index: number) => string} tip
     *                            点ごとの吹き出しの、時刻より後ろの文
     * @returns {ChartView} 描いたグラフ
     */
    function chartShell(frame, label, max, ticks, svg, dots, tip) {
        /** @type {(x: string, y: string, x2: string, y2: string) => string} */
        const gridLine = (x, y, x2, y2) => `<line class="gridLine" x1="${x}" y1="${y}" x2="${x2}"`
            + ` y2="${y2}" vector-effect="non-scaling-stroke"/>`;
        const grid = systemTimeTicks(frame.start, frame.end, 30).map((at) => {
            const x = xAt(at, frame.start).toFixed(1);
            return gridLine(x, "0", x, "100");
        }).join("") + gridLine("0", "0", "1000", "0") + gridLine("0", "100", "1000", "100");
        const yTicks = ticks.map((tick) => (tick.value >= max
            ? `<span class="yTick is-max">${escapeHtml(tick.text)}</span>`
            : `<span class="yTick" style="top:${yAt(tick.value, max)}%">`
                + `${escapeHtml(tick.text)}</span>`)).join("");
        const xTicks = systemTimeTicks(frame.start, frame.end, 60)
            .map((at) => `<span class="xTick" style="left:${xAt(at, frame.start) / 10}%">`
                + `${formatClock(at)}</span>`).join("")
            + `<span class="xTick is-edge" style="left:0">${formatClock(frame.start)}</span>`
            + `<span class="xTick is-edge" style="right:0">${formatClock(frame.end)}</span>`;
        return {
            html: `<figure class="miniChart" role="img" aria-label="${escapeHtml(label)}">`
                + '<div class="plot"><svg viewBox="0 0 1000 100" preserveAspectRatio="none"'
                + ` aria-hidden="true">${grid}${svg}</svg>${yTicks}${dots}`
                + '<span class="chartCursor" hidden></span>'
                + '<span class="chartTip" aria-hidden="true" hidden></span></div>'
                + `<div class="xTicks">${xTicks}</div></figure>`,
            points: frame.xs.map((x, i) => ({
                x, text: `${formatClock(frame.points[i].at)}${TIP_SEPARATOR}${tip(i)}`,
            })),
        };
    }

    /**
     * @param {ChartFrame} frame 横軸と範囲の中の点
     * @returns {string} 読み上げの名前の、期間の部分
     */
    const chartRange = (frame) => `${formatClock(frame.start)}〜${formatClock(frame.end)}`;

    /**
     * 積み上げた面のグラフ（CPU・メモリ・ディスク）。下から「サービス」（ディスクは「録画」）
     * 「そのほか」と重ね、上端が全体になる。枠の帯と同じ色・同じ並びにし、今の値（帯）と
     * 推移（面）を同じ読み方で読めるようにする。
     *
     * <p>どれか 1 層でも計測できなかった点は、全層を null にして面を切る。片方だけ描くと、
     * 描いた層の上端を全体と読ませるため（レビュー #16）。
     *
     * @param {ChartFrame}   frame  横軸と範囲の中の点
     * @param {string}       owner  読み上げの名前の頭（「CPU の」など）
     * @param {ChartLayer[]} layers 下から重ねる層
     * @param {number}       mark   注意の基準（%）。破線と目盛りの文字を出す
     * @param {number|null}  limit  録画の下限（%）。目盛りの文字の無い破線を出す。無ければ null
     * @returns {ChartView} 描いたグラフ
     */
    function areaChart(frame, owner, layers, mark, limit = null) {
        const { xs } = frame;
        const complete = xs.map((_, i) => layers.every((layer) => layer.values[i] !== null));
        const values = layers.map((layer) => layer.values.map((v, i) => (complete[i] ? v : null)));
        /** @type {Array<number|null>} */
        let lower = xs.map(() => 0);
        let svg = "";
        let dots = "";
        layers.forEach((layer, n) => {
            const low = lower;
            const upper = values[n].map((v, i) => {
                const base = low[i];
                return v === null || base === null ? null : base + v;
            });
            let area = "";
            /** @type {Array<{x: string, top: string, bottom: string}>} */
            let run = [];
            const flush = () => {
                if (run.length >= 2) {
                    area += `M${run.map((p) => `${p.x} ${p.top}`).join(" L")}`
                        + ` L${run.map((p) => `${p.x} ${p.bottom}`).reverse().join(" L")} Z`;
                }
                run = [];
            };
            upper.forEach((v, i) => {
                const base = low[i];
                if (v === null || base === null) {
                    flush();
                    return;
                }
                const x = xs[i].toFixed(1);
                run.push({ x, top: yAt(v, 100).toFixed(2), bottom: yAt(base, 100).toFixed(2) });
            });
            flush();
            const edge = linePath(xs, upper.map((v) => (v === null ? null : yAt(v, 100))));
            svg += `<path d="${area}" style="fill:${layer.color};fill-opacity:.85"/>`
                + `<path d="${edge}" fill="none" style="stroke:${layer.edge}" stroke-width="1"`
                + ' stroke-linejoin="round" vector-effect="non-scaling-stroke"/>';
            dots += endDot(frame, upper[upper.length - 1], 100, layer.color);
            lower = upper;
        });
        svg += refLine(yAt(mark, 100), "var(--c-warning)", "4 3");
        if (limit !== null) svg += refLine(yAt(limit, 100), "var(--c-error)", "2 2");

        // 一番上の層の上端が全体
        const peak = peakIndex(lower);
        const latest = lower[lower.length - 1];
        const parts = peak < 0 ? []
            : [`最大 ${formatPercent(lower[peak])}（${formatClock(frame.points[peak].at)}）`];
        parts.push(latest === null
            ? "最新は 取得できませんでした" : `最新 ${formatPercent(latest)}`);
        const ticks = [
            { value: 0, text: "0%" },
            { value: mark, text: `${mark}%` },
            { value: 100, text: "100%" },
        ];
        /** @param {number} i 点の位置 @returns {string} 吹き出しの値 */
        const tip = (i) => layers.map((layer, n) => `${layer.label} ${formatPercent(values[n][i])}`)
            .join(TIP_SEPARATOR);
        const label = `${owner}推移、${chartRange(frame)}。${parts.join("、")}`;
        return chartShell(frame, label, 100, ticks, svg, dots, tip);
    }

    /**
     * ネットワークの折れ線のグラフ。受信を実線・送信を破線にし、色だけでなく線の形でも見分け
     * られるようにする（レビュー #42）。上端は 2 本の最大を枠の内訳と同じ単位に直して切り上げ、
     * 小さい値も潰さずに描く。
     * @param {ChartFrame} frame 横軸と範囲の中の点
     * @returns {ChartView} 描いたグラフ
     */
    function rateChart(frame) {
        const receive = frame.points.map((point) => point.networkReceiveBytesPerSecond);
        const send = frame.points.map((point) => point.networkSendBytesPerSecond);
        const peakBytes = Math.max(0, ...[...receive, ...send].map((bytes) => bytes ?? 0));
        const unit = rateUnit(peakBytes);
        const top = systemNiceMax(peakBytes / unit.size);
        const max = top * unit.size;
        /** @type {(values: Array<number|null>, color: string, dash: string) => string} */
        const line = (values, color, dash) => {
            const d = linePath(frame.xs, values.map((v) => (v === null ? null : yAt(v, max))));
            return `<path d="${d}" fill="none" style="stroke:${color}" stroke-width="2"`
                + ` stroke-dasharray="${dash}" stroke-linejoin="round"`
                + ' vector-effect="non-scaling-stroke"/>';
        };
        const receiveColor = "var(--c-chart-receive)";
        const sendColor = "var(--c-chart-send)";
        const last = frame.points.length - 1;

        const peak = peakIndex(receive);
        const parts = peak < 0 ? [] : [`受信の最大 ${formatRates([receive[peak]])[0]}`
            + `（${formatClock(frame.points[peak].at)}）`];
        if (receive[last] === null || send[last] === null) {
            parts.push("最新は 取得できませんでした");
        } else {
            const [latestReceive, latestSend] = formatRates([receive[last], send[last]]);
            parts.push(`最新は受信 ${latestReceive}、送信 ${latestSend}`);
        }
        const label = `${PANEL_OWNER.network}推移、${chartRange(frame)}。${parts.join("、")}`;
        const ticks = [{ value: 0, text: "0 B/s" }, { value: max, text: `${top} ${unit.label}` }];
        const svg = line(receive, receiveColor, "none") + line(send, sendColor, "4 3");
        const dots = endDot(frame, receive[last], max, receiveColor)
            + endDot(frame, send[last], max, sendColor);
        return chartShell(frame, label, max, ticks, svg, dots, (i) => {
            const [r, s] = formatRates([receive[i], send[i]]);
            return `受信 ${r}${TIP_SEPARATOR}送信 ${s}`;
        });
    }

    /**
     * 4 つの枠に直近 3 時間の推移を描く。推移を読めなかったときは前の推移で描く（右端は今の
     * 計測の時刻なので、古い推移は左へ寄って、右に空きができる）。
     *
     * <p>描いた HTML が前と同じなら描き直さない。推移は 1 分に 1 回しか変わらないのに、10 秒ごとの
     * 読み直しのたびに描き直すと、マウスを重ねて出した値がそのたびに消えるため。
     */
    function renderCharts() {
        const r = resources;
        /** @type {Record<string, ChartView>} */
        let views = {};
        let message = "";
        if (!r || !history) {
            message = settled ? "取得できませんでした" : "";
        } else {
            const frame = chartFrame(history, r.measuredAt);
            if (frame.points.length < 2) {
                message = "記録を集めています";
            } else {
                const memoryTotal = r.system.memoryTotalBytes;
                const diskTotal = r.system.diskTotalBytes;
                const service = "var(--c-chart-service)";
                /** @type {(label: string, values: Array<number|null>) => ChartLayer} */
                const serviceLayer = (label, values) => ({
                    label, values, color: service, edge: service,
                });
                // 灰色は地の色に近いので、上端に濃い線を引いて輪郭を出す（レビュー #44）
                /** @type {(values: Array<number|null>) => ChartLayer} */
                const otherLayer = (values) => ({
                    label: "そのほか", values, color: "var(--c-chart-other)",
                    edge: "var(--c-border-input)",
                });
                const points = frame.points;
                const cpuService = points.map((p) => p.registeredCpuPercent);
                const memoryService = points
                    .map((p) => p.registeredMemoryBytes / memoryTotal * 100);
                const recordings = points.map((p) => (diskTotal === null
                    ? null : p.recordingsBytes / diskTotal * 100));
                // 設定 0 は空きを確かめない（録画を止めない）ので、下限の線を引かない（子 #826）
                const reserve = r.diskOutlook.reserveBytes;
                const limit = reserve > 0 && diskTotal !== null
                    ? (diskTotal - reserve) / diskTotal * 100 : null;
                views = {
                    cpu: areaChart(frame, PANEL_OWNER.cpu, [
                        serviceLayer("サービス", cpuService),
                        // 全体とプロセスの測り方が違い、引くと負になりうるので 0 で止める
                        otherLayer(points.map((p, i) => {
                            const s = cpuService[i];
                            return p.systemCpuPercent === null || s === null
                                ? null : Math.max(p.systemCpuPercent - s, 0);
                        })),
                    ], CPU_MARK),
                    memory: areaChart(frame, PANEL_OWNER.memory, [
                        serviceLayer("サービス", memoryService),
                        otherLayer(points.map((p, i) =>
                            Math.max(p.systemMemoryUsedPercent - memoryService[i], 0))),
                    ], USAGE_MARK),
                    disk: areaChart(frame, PANEL_OWNER.disk, [
                        serviceLayer("録画", recordings),
                        otherLayer(points.map((p, i) => {
                            const recording = recordings[i];
                            const free = p.diskFreeBytes;
                            if (diskTotal === null || free === null || recording === null) {
                                return null;
                            }
                            const used = (diskTotal - free) / diskTotal * 100;
                            return Math.max(used - recording, 0);
                        })),
                    ], USAGE_MARK, limit),
                    network: rateChart(frame),
                };
            }
        }
        for (const key of CHART_KEYS) {
            const view = views[key];
            const html = view ? view.html : message && `<p class="chartEmpty">${message}</p>`;
            chartPoints[key] = view ? view.points : [];
            if (chartHtml[key] === html) continue;
            el(`${key}Chart`).innerHTML = html;
            chartHtml[key] = html;
        }
    }

    /**
     * @param {HTMLElement} box グラフの枠（{@code #<key>Chart}）
     */
    function hideChartCursor(box) {
        for (const node of box.querySelectorAll(".chartCursor, .chartTip")) {
            if (node instanceof HTMLElement) node.hidden = true;
        }
    }

    /**
     * マウスを重ねた（指で押した）位置に一番近い点の時刻と値を、縦線と吹き出しで出す
     * （レビュー #23）。右寄りの点（60% より右）では吹き出しを縦線の左に出し、グラフの右へ
     * はみ出させない。狭い画面では吹き出しが枠より広いので、画面の外へ出る分は内へずらす。
     * @param {string}       key   グラフ（cpu・memory・disk・network）
     * @param {PointerEvent} event 重ねた（押した）位置
     */
    function showChartCursor(key, event) {
        const box = el(`${key}Chart`);
        const plot = event.target instanceof Element ? event.target.closest(".plot") : null;
        const points = chartPoints[key] ?? [];
        const rect = plot ? plot.getBoundingClientRect() : null;
        const x = rect ? (event.clientX - rect.left) / rect.width * 1000 : -1;
        if (!plot || !points.length || x < 0 || x > 1000) {
            hideChartCursor(box);
            return;
        }
        const nearest = points.reduce((best, point) =>
            (Math.abs(point.x - x) < Math.abs(best.x - x) ? point : best));
        const cursor = query(".chartCursor", plot);
        const tip = query(".chartTip", plot);
        const left = nearest.x / 10;
        const before = nearest.x > 600;
        cursor.style.left = `${left}%`;
        tip.textContent = nearest.text;
        tip.style.left = before ? "auto" : `calc(${left}% + 6px)`;
        tip.style.right = before ? `calc(${100 - left}% + 6px)` : "auto";
        cursor.hidden = false;
        tip.hidden = false;
        const bounds = tip.getBoundingClientRect();
        const shift = Math.max(bounds.right - (document.documentElement.clientWidth - 8), 0)
            - Math.max(8 - bounds.left, 0);
        if (shift !== 0) {
            tip.style.left = `${tip.offsetLeft - shift}px`;
            tip.style.right = "auto";
        }
    }

    // ---- プロセスの表 ----

    /**
     * @param {HostProcess} p プロセス
     * @returns {string} 行の key。PID は使い回されるので、起動時刻と組にして別のプロセスと見分ける
     */
    const processKey = (p) => `${p.pid}:${p.startTime}`;

    /**
     * @param {HostProcess[]} members 頭とたたんだ子（先頭が頭）
     * @returns {ProcessGroup} 表の 1 行。CPU は 1 つでも分からなければ null にする。分からない値を
     *                         0 とみなして足すと、実際より少ない合計を確かな値のように見せるため
     */
    function toGroup(members) {
        return {
            key: processKey(members[0]),
            members,
            cpu: members.some((p) => p.cpuPercent === null)
                ? null : members.reduce((sum, p) => sum + (p.cpuPercent ?? 0), 0),
            memory: members.reduce((sum, p) => sum + p.memoryBytes, 0),
            seconds: members[0].upSeconds,
        };
    }

    /**
     * @param {HostProcess[]} processes プロセス
     * @returns {ProcessGroup[]} 表の行（同じ名前の子をたたむ。理由は systemGroupRows）
     */
    function processGroups(processes) {
        const byPid = new Map(processes.map((p) => [p.pid, p]));
        return systemGroupRows(processes).map((row) =>
            toGroup(row.members.map((pid) => /** @type {HostProcess} */ (byPid.get(pid)))));
    }

    /**
     * 今の列と向きで並べる。値が同じなら PID の小さい順にし、並べるたびに順が入れ替わらないように
     * する。分からない CPU（null）は向きによらず末尾に置く（0 とみなすと少ない順の先頭に並ぶため）。
     * @param {ProcessGroup[]} groups 行
     * @returns {ProcessGroup[]} 並べた行（新しい配列）
     */
    function sortGroups(groups) {
        const value = SORT_VALUE[sort];
        const sign = direction === "desc" ? -1 : 1;
        return [...groups].sort((a, b) => {
            const x = value(a);
            const y = value(b);
            if (x !== y) {
                if (x === null) return 1;
                if (y === null) return -1;
                return (x - y) * sign;
            }
            return a.members[0].pid - b.members[0].pid;
        });
    }

    /**
     * 行の並び（order）を今の一覧に合わせる。並べ直さないときは、無くなった行を抜き、新しい行を
     * 末尾に足すだけにする（理由は order）。新しい行どうしは今の列と向きで並べる。
     * @param {ProcessGroup[]} groups 今の行
     * @param {boolean}        resort 並べ直すか
     */
    function syncOrder(groups, resort) {
        if (resort) {
            order = sortGroups(groups).map((g) => g.key);
            return;
        }
        const keys = new Set(groups.map((g) => g.key));
        const kept = order.filter((key) => keys.has(key));
        const known = new Set(kept);
        order = [...kept, ...sortGroups(groups.filter((g) => !known.has(g.key))).map((g) => g.key)];
    }

    /**
     * @param {HostProcess} p プロセス
     * @returns {string} 検索で見る文字（小文字）。画面に出ている文字で探せるよう、役割のバッジと
     *                   「録画中」も含める（レビュー #38）
     */
    const searchText = (p) => [p.name, String(p.pid), ...p.ports.map(String), p.service ?? "",
        p.role ? ROLE_LABEL[p.role] : "", p.recording ? "録画中" : ""].join("\n").toLowerCase();

    /**
     * 中身の HTML が前に書いたものと違うときだけ書き換える（理由は drawnHtml）。
     * @param {Element} node 書き換える要素
     * @param {string}  html 中身の HTML
     */
    function setHtml(node, html) {
        if (drawnHtml.get(node) === html) return;
        node.innerHTML = html;
        drawnHtml.set(node, html);
    }

    /**
     * @param {number} bytes バイト数
     * @returns {string} MB の数。表の中は単位を MB にそろえ、桁の違う数字を並べない（レビュー #21）
     */
    const formatMegabytes = (bytes) => `${Math.round(bytes / 1048576).toLocaleString("ja-JP")} MB`;

    /**
     * 名前のセルのうち、開閉のボタンより後ろ（名前・サービス・バッジ）の HTML。
     *
     * <p>コマンドラインと作業フォルダーは title だけに入れ、常には出さない（名前が埋もれて行が
     * 読みにくくなるため。レビュー #3）。録画中は状態なので、役割のバッジと違う形（ランプ）にする
     * （レビュー #58）。止めている途中の印は名前のすぐ後ろに置き、行を読み直しても止めている最中
     * であることが分かるようにする（サーバーが持つ状態なので、画面を読み込み直しても出る。
     * レビュー #4）。
     *
     * @param {ProcessGroup} group 行
     * @param {number}       cores 論理コア数
     * @returns {string} HTML
     */
    function namePartsHtml(group, cores) {
        const head = group.members[0];
        /** @type {string[]} */
        const lines = [];
        if (head.commandLine) lines.push(head.commandLine);
        if (head.workingDirectory) lines.push(`作業フォルダー: ${head.workingDirectory}`);
        const title = lines.length ? lines.map(escapeHtml).join("&#10;") : escapeHtml(head.name);
        const service = group.members.find((p) => p.service)?.service;
        const roles = new Set(group.members.flatMap((p) => (p.role ? [ROLE_LABEL[p.role]] : [])));
        // 1 コア分（端末全体の 100 / cores %）の 9 割を使っていれば知らせる（レビュー #8）
        const busy = group.members.some((p) => p.cpuPercent !== null && p.cpuPercent >= 90 / cores);
        const stopping = head.stopState ? '<span class="status progress"><span class="spinner"'
            + ` aria-hidden="true"></span>${STOP_STATUS_LABEL[head.stopState]}</span>` : "";
        return `<span class="procName" title="${title}">${escapeHtml(head.name)}</span>${stopping}`
            + (service ? `<span class="procService">${escapeHtml(service)}</span>` : "")
            + [...roles].map((role) => `<span class="badge grey">${role}</span>`).join("")
            + (group.members.some((p) => p.recording) ? statusLamp("recording", "録画中") : "")
            + (busy ? '<span class="badge warning">1 コアを使い切っています</span>' : "");
    }

    /**
     * @param {string} key 行の key
     * @returns {HTMLTableRowElement} 空の行（中身は updateRow が書く）
     */
    function createRow(key) {
        const tr = document.createElement("tr");
        tr.dataset.key = key;
        tr.innerHTML = '<td class="selectCol"></td><td><div class="nameCell">'
            + '<button type="button" class="expandButton" aria-expanded="false" hidden></button>'
            + '<span class="nameParts"></span></div></td>'
            + '<td class="num"></td>'.repeat(4) + "<td></td>";
        return tr;
    }

    /**
     * 行のセルの文字と属性を書き換える。変わったところだけを書くので、読み直しでもフォーカスや
     * 選んだ文字が消えない（レビュー #28）。開閉のボタンは書き換えずに属性だけを変え、押した後も
     * フォーカスが残るようにする。
     * @param {HTMLTableRowElement} tr    行
     * @param {ProcessRowView}      row   描く行
     * @param {number}              cores 論理コア数
     */
    function updateRow(tr, { group, member }, cores) {
        const head = group.members[0];
        const cells = tr.cells;
        tr.classList.toggle("is-member", member);
        // 止めている途中の行は選べなくする（同じものをもう一度止めさせない）
        tr.classList.toggle("is-stopping", Boolean(head.stopState));
        setHtml(cells[0], head.lockedReason
            ? '<button type="button" class="lockButton" aria-label="選べない理由" aria-expanded="false">'
                + `${LOCK_ICON}</button><div class="popoverBody lockReason" hidden>`
                + `${escapeHtml(head.lockedReason)}</div>`
            : `<input type="radio" name="process"${head.stopState ? " disabled" : ""}`
                + ` aria-label="${escapeHtml(head.name)}（PID ${head.pid}）を選ぶ">`);
        const expand = query(".expandButton", cells[1]);
        const children = group.members.length - 1;
        expand.hidden = children === 0;
        if (children) {
            const open = expanded.has(group.key);
            expand.setAttribute("aria-expanded", String(open));
            expand.setAttribute("aria-label", `${head.name} の子のプロセス ${children} 件を表示`);
            setHtml(expand, `${open ? "－" : "＋"}${children}`);
        }
        setHtml(query(".nameParts", cells[1]), namePartsHtml(group, cores));
        setHtml(cells[2], String(head.pid));
        setHtml(cells[3], escapeHtml(formatPercent(group.cpu)));
        setHtml(cells[4], escapeHtml(formatMegabytes(group.memory)));
        setHtml(cells[5], escapeHtml(formatElapsed(group.seconds)));
        const ports = [...new Set(group.members.flatMap((p) => p.ports))]
            .sort((a, b) => a - b).join(", ");
        cells[6].title = ports;
        setHtml(cells[6], ports || '<span class="muted">—</span>');
    }

    /**
     * 見える行を、key で今の行と突き合わせて描く。tbody を作り直さず、無い行だけを作り、要らない
     * 行を外し、並びが違う所だけを動かす。作り直すと、radio のフォーカスと選んだ文字が 10 秒ごとに
     * 消えるため（レビュー #6・#28）。
     * @param {ProcessRowView[]} rows  見える行（上から）
     * @param {number}           cores 論理コア数
     */
    function drawRows(rows, cores) {
        drawnHtml.delete(rowsBox);
        const wanted = new Set(rows.map((row) => row.group.key));
        /** @type {Map<string, HTMLTableRowElement>} */
        const current = new Map();
        for (const tr of [...rowsBox.children]) {
            const key = tr instanceof HTMLTableRowElement ? tr.dataset.key : undefined;
            if (tr instanceof HTMLTableRowElement && key && wanted.has(key)) current.set(key, tr);
            else tr.remove();
        }
        let cursor = rowsBox.firstElementChild;
        for (const row of rows) {
            const tr = current.get(row.group.key) ?? createRow(row.group.key);
            updateRow(tr, row, cores);
            if (tr === cursor) cursor = cursor.nextElementSibling;
            else rowsBox.insertBefore(tr, cursor);
        }
    }

    /**
     * 見える行が無いときの 1 行。空の表だけでは、読み込み中・失敗・一致なしを見分けられないので、
     * 何も出ない理由と次にできること（検索を消す・すべてから探す・再試行）を出す
     * （レビュー #7・#38）。
     * @returns {string} tbody の HTML
     */
    function emptyRowHtml() {
        /** @type {(text: string, actions?: Array<[string, string]>) => string} */
        const row = (text, actions = []) => {
            const buttons = actions.map(([action, label]) =>
                `<button type="button" class="btn" data-action="${action}">${label}</button>`).join("");
            return `<tr class="emptyRow"><td colspan="7">${text}`
                + `${buttons ? `<div class="emptyActions">${buttons}</div>` : ""}</td></tr>`;
        };
        if (!system) {
            return settled
                ? row("プロセスの一覧を取得できませんでした", [["retry", "再試行"]])
                : row('<span class="spinner" aria-hidden="true"></span>読み込んでいます');
        }
        const quoted = `「${escapeHtml(filter)}」`;
        if (filter && view === "services") {
            return row(`${quoted}に一致するプロセスは、サービスの中にありません`,
                [["all", "すべてから探す"], ["clear", "検索を消す"]]);
        }
        if (filter) return row(`${quoted}に一致するプロセスはありません`, [["clear", "検索を消す"]]);
        return row(view === "services"
            ? "動いているサービスのプロセスはありません" : "プロセスはありません");
    }

    /**
     * ページ送りを描く。描き直してもキーボードの位置を失わないよう、押したボタン（前・次・番号を
     * aria-label で見分ける）へフォーカスを戻し、端で無効になったら今のページの番号へ移す
     * （レビュー #50）。1 ページに収まるときは出さない。
     * @param {number} pages ページの数
     */
    function renderPagination(pages) {
        pagination.hidden = pages <= 1;
        const active = document.activeElement;
        const focused = active && pagination.contains(active) ? active.getAttribute("aria-label") : null;
        const numbers = [...new Set([1, page - 1, page, page + 1, pages])]
            .filter((n) => n >= 1 && n <= pages).sort((a, b) => a - b);
        let previous = 0;
        const items = numbers.map((n) => {
            const gap = n - previous > 1 ? '<li class="gap" aria-hidden="true">…</li>' : "";
            previous = n;
            return `${gap}<li><button type="button" data-page="${n}" aria-label="${n} ページ目"`
                + `${n === page ? ' aria-current="true"' : ""}>${n}</button></li>`;
        }).join("");
        setHtml(pagination, `<button type="button" data-page="${page - 1}" aria-label="前のページ"`
            + `${page === 1 ? " disabled" : ""}>${lineIcon("M10 3L5 8l5 5")}</button><ol>${items}</ol>`
            + `<button type="button" data-page="${page + 1}" aria-label="次のページ"`
            + `${page === pages ? " disabled" : ""}>${lineIcon("M6 3l5 5-5 5")}</button>`);
        if (!focused || pagination.contains(document.activeElement)) return;
        const next = [...pagination.querySelectorAll("button")]
            .find((button) => button.getAttribute("aria-label") === focused && !button.disabled)
            ?? pagination.querySelector('button[aria-current="true"]');
        if (next instanceof HTMLElement) next.focus();
    }

    /**
     * 一致した件数を、入力が 300ms 止まってから書き換える。#filterCount は読み上げの領域なので、
     * 1 文字ごとに書き換えると、入力のたびに件数が読み上げられるため。
     * @param {string} text 件数の文。検索語が無ければ空
     */
    function scheduleFilterCount(text) {
        window.clearTimeout(filterCountTimer);
        filterCountTimer = window.setTimeout(() => {
            const box = el("filterCount");
            if (box.textContent !== text) box.textContent = text;
        }, 300);
    }

    /**
     * 停止のボタンを、行を選んでいて「更新できていません」でないときだけ押せるようにする。古い
     * 一覧のまま止めると、もう無いプロセスや PID を使い回した別のプロセスを選んでいることがある
     * ため。disabled でなく aria-disabled にするのは、フォーカスを置いたまま読み直しが来ても
     * 位置を失わないため（refresh の表示を更新と同じ）。
     *
     * <p>狭い画面では、行を選んでいる間だけ画面の下に帯（選んだ名前と停止）を出す。表を下へ送ると
     * 上の停止のボタンが見えなくなり、選んでから押すまでが遠いため（レビュー #36）。
     */
    function renderStopButton() {
        const disabled = String(selected === null || isStale());
        stopButton.setAttribute("aria-disabled", disabled);
        selectionStop.setAttribute("aria-disabled", disabled);
        const head = selected === null ? null : findProcess(selected);
        const bar = head !== null && barWidth.matches;
        selectionBar.hidden = !bar;
        document.body.classList.toggle("has-selectionBar", bar);
        if (head) el("selectionLabel").textContent = `${head.name}（PID ${head.pid}）`;
    }

    /** 選んだ行の印（淡い青と radio）だけを書き換える。表は作り直さない（レビュー #28） */
    function applySelection() {
        for (const tr of rowsBox.querySelectorAll("tr[data-key]")) {
            if (!(tr instanceof HTMLTableRowElement)) continue;
            const on = tr.dataset.key === selected;
            tr.classList.toggle("is-selected", on);
            const radio = tr.querySelector('input[type="radio"]');
            if (radio instanceof HTMLInputElement) radio.checked = on;
        }
        renderStopButton();
    }

    /** @param {string|null} key 選ぶ行の key。外すなら null */
    function select(key) {
        closeLock();
        selected = key;
        applySelection();
    }

    /** @param {boolean} [restoreFocus] 閉じた後に鍵へフォーカスを戻すか（Esc で閉じたとき） */
    function closeLock(restoreFocus = false) {
        const lock = openLock;
        openLock = null;
        if (!lock || !lock.isConnected) return;
        lock.setAttribute("aria-expanded", "false");
        const body = lock.nextElementSibling;
        if (body instanceof HTMLElement) body.hidden = true;
        if (restoreFocus) lock.focus();
    }

    /**
     * 選べない理由の吹き出しを開け閉めする。理由を常に出さず押したときだけにするのは、常に出す
     * 文字を減らすため（レビュー #37）。開くときは選択を外す（選べない行を選んだように見せない）。
     * 吹き出しは画面に対して置くので、鍵の位置から決め、画面の右と下へはみ出さないようにする。
     * @param {HTMLButtonElement} lock 鍵
     */
    function toggleLock(lock) {
        const wasOpen = openLock === lock;
        select(null);
        const body = lock.nextElementSibling;
        if (wasOpen || !(body instanceof HTMLElement)) return;
        openLock = lock;
        lock.setAttribute("aria-expanded", "true");
        body.hidden = false;
        const rect = lock.getBoundingClientRect();
        const right = document.documentElement.clientWidth - 16 - body.offsetWidth;
        body.style.left = `${Math.max(16, Math.min(rect.left - 12, right))}px`;
        const below = rect.bottom + 8;
        body.style.top = `${below + body.offsetHeight > window.innerHeight
            ? rect.top - 8 - body.offsetHeight : below}px`;
    }

    /** 表が横にはみ出しているときだけ、右端に影を付ける（続きがあることを見せるため） */
    const markOverflow = () =>
        tableWrap.classList.toggle("is-overflowing", tableWrap.scrollWidth > tableWrap.clientWidth);

    /**
     * プロセスの表を描く。並びは order のまま（resort のときだけ並べ直す）。
     *
     * <p>選んだ行が、検索・表示の切り替え・並べ替え・ページ送り・読み直しで見えなくなったら選択を
     * 外す。見えない行が選ばれたまま停止を押せると、何を止めるのかが画面から分からないため
     * （レビュー #2）。
     *
     * @param {boolean} [resort] 並べ直すか（見出しと「表示を更新」を押したとき）
     */
    function renderProcesses(resort = false) {
        el("processUser").textContent = system?.user ?? "-";
        el("processUserName").textContent = system?.user ?? "-";
        el("processCores").textContent = system ? String(system.cores) : "-";
        const groups = system ? processGroups(system.processes) : [];
        el("processCounter").textContent = system ? `(${groups.length})` : "";
        syncOrder(groups, resort);
        const byKey = new Map(groups.map((g) => [g.key, g]));
        for (const key of expanded) {
            if (!byKey.has(key)) expanded.delete(key);
        }
        const needle = filter.toLowerCase();
        const shown = order.flatMap((key) => {
            const group = byKey.get(key);
            return group && (view === "all" || group.members.some((p) => p.service !== null))
                && (!needle || group.members.some((p) => searchText(p).includes(needle))) ? [group] : [];
        });
        const pages = Math.max(1, Math.ceil(shown.length / PAGE_SIZE));
        page = Math.min(page, pages);
        /** @type {ProcessRowView[]} */
        const rows = shown.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE).flatMap((group) => [
            { group, member: false },
            ...(expanded.has(group.key)
                ? group.members.slice(1).map((p) => ({ group: toGroup([p]), member: true })) : []),
        ]);
        if (rows.length) drawRows(rows, system?.cores || 1);
        else setHtml(rowsBox, emptyRowHtml());
        renderPagination(pages);
        scheduleFilterCount(filter ? `${shown.length} 件一致` : "");
        if (!rows.some((row) => row.group.key === selected && !row.group.members[0].lockedReason
            && !row.group.members[0].stopState)) {
            selected = null;
        }
        if (openLock && !openLock.isConnected) openLock = null;
        applySelection();
        markOverflow();
    }

    // ---- お知らせ（レビュー #4・#9・#10） ----

    /**
     * お知らせを出す。同じ key のお知らせは、位置を変えずに中身だけを置き換える。止めている途中の
     * お知らせを結果に替えるとき、並びが動いて別の停止の結果と取り違えさせないため。中身が前と
     * 同じなら書き換えない（2 秒ごとの読み直しのたびに、同じ文を読み上げ直さないため）。
     * @param {string}             key     どの停止・録画のお知らせか
     * @param {FlashKind}          kind    種類
     * @param {string}             html    文の HTML（値は escapeHtml を通しておく）
     * @param {(() => void)|null}  [retry] ボタン「もう一度」で呼ぶ処理。ボタンを出さないなら null
     */
    function showFlash(key, kind, html, retry = null) {
        const current = flashes.get(key);
        if (current && current.kind === kind && current.html === html) return;
        if (current) window.clearTimeout(current.timer);
        const node = current ? current.node : document.createElement("div");
        flashes.set(key, {
            kind, html, retry, node,
            timer: TRANSIENT_FLASH.has(kind)
                ? window.setTimeout(() => dismissFlash(key), FLASH_MILLIS) : 0,
        });
        node.className = `flash ${kind}`;
        node.innerHTML = `${FLASH_ICON[kind]}<div class="flashText">${html}</div>`
            + (retry ? '<button type="button" class="btn flashRetry">もう一度</button>' : "")
            + '<button type="button" class="flashDismiss" aria-label="お知らせを閉じる">'
            + `${CLOSE_ICON}</button>`;
        if (!current) flashbar.append(node);
        for (const [oldKey, old] of flashes) {
            if (flashes.size <= FLASH_LIMIT) break;
            if (oldKey !== key && TRANSIENT_FLASH.has(old.kind)) dismissFlash(oldKey);
        }
    }

    /**
     * お知らせを 1 件消す。✕ にフォーカスがあったときは、残ったお知らせの ✕ へ移し、続けて
     * キーボードで消せるようにする。
     * @param {string} key 消すお知らせの key
     */
    function dismissFlash(key) {
        const flash = flashes.get(key);
        if (!flash) return;
        window.clearTimeout(flash.timer);
        flashes.delete(key);
        const focused = flash.node.contains(document.activeElement);
        flash.node.remove();
        const next = focused ? flashbar.querySelector(".flashDismiss") : null;
        if (next instanceof HTMLElement) next.focus();
    }

    // ---- 停止 ----

    /**
     * @param {string} key プロセスの key（{@code ${pid}:${startTime}}）
     * @returns {HostProcess|null} 今の一覧にあるそのプロセス。無ければ null
     */
    const findProcess = (key) => system?.processes.find((p) => processKey(p) === key) ?? null;

    /**
     * @param {HostProcess} head 選んだプロセス
     * @returns {HostProcess[]} head の子孫（parentPid でたどる。サーバーが止める範囲と同じ）。親を
     *                          先に並べる
     */
    function descendantsOf(head) {
        /** @type {Map<number, HostProcess[]>} */
        const children = new Map();
        for (const p of system ? system.processes : []) {
            const list = children.get(p.parentPid);
            if (list) list.push(p);
            else children.set(p.parentPid, [p]);
        }
        /** @type {HostProcess[]} */
        const found = [];
        const seen = new Set([head.pid]);
        // 読み出しの時刻のずれで親子が輪になっていても止まるよう、たどったものは二度たどらない
        for (let i = -1; i < found.length; i++) {
            for (const child of children.get(i < 0 ? head.pid : found[i].pid) ?? []) {
                if (seen.has(child.pid)) continue;
                seen.add(child.pid);
                found.push(child);
            }
        }
        return found;
    }

    /**
     * @param {number} id 録画の ID
     * @returns {HostProcess|null} その録画のいちばん上のプロセス（yt-dlp）。無ければ null
     */
    function recordingRoot(id) {
        const members = system ? system.processes.filter((p) => p.recording?.id === id) : [];
        return members.find((p) => !members.some((m) => m.pid === p.parentPid)) ?? null;
    }

    /**
     * @param {HostRecording} recording 録画
     * @returns {string} 「「タイトル」の録画」。どの配信かを名前で示し、PID だけで録画を見分け
     *                   させないため（レビュー #5）。タイトルが無ければチャンネル名で示す
     */
    function recordingSubject(recording) {
        const name = recording.videoTitle || recording.channelName;
        return name ? `「${name}」の録画` : "この録画";
    }

    /**
     * @param {FlashKind} tone  info か warning
     * @param {string[]}  lines 1 行ずつの HTML
     * @returns {string} ダイアログの注意の HTML（{@code #modalAlert}）
     */
    const alertHtml = (tone, lines) => `<div class="alert ${tone}" id="modalAlert">${FLASH_ICON[tone]}`
        + `<div>${lines.map((line) => `<p>${line}</p>`).join("")}</div></div>`;

    /**
     * @param {Array<[string, string, string?]>} items 名前・値の HTML・項目の class
     * @returns {string} 「名前と値」の HTML（レビュー #57）
     */
    const kvHtml = (items) => `<dl class="kv">${items.map(([name, value, className]) =>
        `<div${className ? ` class="${className}"` : ""}><dt>${escapeHtml(name)}</dt>`
        + `<dd>${value}</dd></div>`).join("")}</dl>`;

    /** @param {string|null} text 値 @returns {string} コードの体裁の HTML。無ければ "-" */
    const codeHtml = (text) => (text ? `<code>${escapeHtml(text)}</code>` : "-");

    /**
     * 一緒に止まる子孫を、名前ごとの件数（多い順）と、開いて見る全件で出す。3 件で切ると、何が
     * 止まるのかを確かめられないため（レビュー #12）。別のサービス・確認用インスタンスの子は、
     * 止めると困るものなので先頭に置く。
     * @param {HostProcess[]}               children 子孫
     * @param {(p: HostProcess) => boolean} notable  先頭に置く子か
     * @returns {string} 値の HTML
     */
    function childrenHtml(children, notable) {
        if (!children.length) return "なし";
        /** @type {Map<string, HostProcess[]>} */
        const byName = new Map();
        for (const p of children) byName.set(p.name, [...(byName.get(p.name) ?? []), p]);
        const groups = [...byName.values()].sort((a, b) =>
            Number(b.some(notable)) - Number(a.some(notable)) || b.length - a.length);
        const summary = groups.map((members) => `${escapeHtml(members[0].name)} ${members.length} 件`)
            .join("、");
        const all = groups.flat().map((p) =>
            `<li>${escapeHtml(p.name)}（<span class="nowrap">PID ${p.pid}</span>）</li>`).join("");
        return `${summary}<details><summary>すべて表示</summary>`
            + `<ul class="childList">${all}</ul></details>`;
    }

    /**
     * ふつうのプロセスの確認ダイアログの中身。止まるもの（サービス・確認用インスタンス）と失うもの
     * （保存していない内容）を、確定の前に注意として出す（レビュー #11）。確認用インスタンスは
     * サービスに数えない（service が null）ので、service より先に見る。
     * @param {HostProcess}   head     選んだプロセス
     * @param {HostProcess[]} children 一緒に止まる子孫
     * @returns {StopModalContent} 中身
     */
    function processModal(head, children) {
        /** @type {(p: HostProcess) => boolean} 別のサービス・確認用インスタンスも止まる子か */
        const other = (p) => p.role === "SANDBOX"
            || (p.service !== null && p.service !== head.service);
        /** @type {FlashKind} */
        let tone = "warning";
        /** @type {string[]} */
        const lines = [];
        if (head.role === "SANDBOX") {
            tone = "info";
            lines.push("確認用の YouTube Live Monitor です。止めても本番には影響しません。");
        } else if (!head.service) {
            lines.push("登録したサービスのプロセスではありません。停止すると、そのアプリで保存して"
                + "いない内容が失われることがあります。");
        } else {
            lines.push(`${escapeHtml(head.service)}（PID ${head.pid}）が止まります。`
                + "起動し直すまで使えません。");
            const launch = system?.services.find((service) => service.name === head.service)?.launch;
            if (launch) lines.push(`起動のしかた: ${codeHtml(launch)}`);
        }
        // サービスごとに、そのサービスのいちばん上のプロセスだけを挙げる（子まで並べると長くなる）
        const byPid = new Map([head, ...children].map((p) => [p.pid, p]));
        const others = children.filter((p) => other(p)
            && (p.role === "SANDBOX" || byPid.get(p.parentPid)?.service !== p.service));
        if (others.length) {
            tone = "warning";
            lines.push(...others.map((p) => (p.role === "SANDBOX"
                ? `YouTube Live Monitor（確認用、PID ${p.pid}）も止まります`
                : `${escapeHtml(p.service)}（PID ${p.pid}）も止まります`)));
        }
        const { cpu, memory } = toGroup([head, ...children]);
        /** @type {Array<[string, string, string?]>} */
        const items = [
            ["プロセス", `${escapeHtml(head.name)}（<span class="nowrap">PID ${head.pid}</span>）`],
        ];
        if (head.service) items.push(["サービス", escapeHtml(head.service)]);
        items.push(
            ["CPU", escapeHtml(formatPercent(cpu))],
            ["メモリ", escapeHtml(formatMegabytes(memory))],
            ["コマンドライン", codeHtml(head.commandLine)],
            ["作業フォルダー", codeHtml(head.workingDirectory)],
            [`一緒に停止する子のプロセス（${children.length} 件）`, childrenHtml(children, other),
                "is-wide"],
        );
        return {
            title: `${head.name}（PID ${head.pid}）を停止しますか？`,
            confirm: "停止",
            describedBy: "modalAlert modalTarget",
            body: alertHtml(tone, lines)
                + '<p id="modalTarget">終了を要求し、10 秒たっても残っていれば強制終了します。</p>'
                + kvHtml(items),
        };
    }

    /**
     * 録画のプロセスの確認ダイアログの中身。PID ではなく、どの配信の録画かで確かめさせる
     * （レビュー #5）。止め方はアーカイブ一覧の「停止」と同じ（録画の停止の API）。PID で直接
     * 止めると、録画の仕組みが「普通に終わった」と見て録り直すため。
     * @param {HostProcess}   head      選んだプロセス（yt-dlp か、その子の ffmpeg）
     * @param {HostRecording} recording その録画
     * @returns {StopModalContent} 中身
     */
    function recordingModal(head, recording) {
        const name = recording.videoTitle || recording.channelName;
        const root = recordingRoot(recording.id) ?? head;
        const lost = `${name ? escapeHtml(`「${name}」`) : "この配信"}の続きは録画されません。`
            + "そこまでの内容は「途中まで」として残ります。";
        return {
            title: `${recordingSubject(recording)}を停止しますか？`,
            confirm: "録画を停止",
            describedBy: "modalAlert",
            body: alertHtml("warning", [lost])
                + kvHtml([
                    ["チャンネル", escapeHtml(recording.channelName ?? "-")],
                    ["配信タイトル", escapeHtml(recording.videoTitle || "-")],
                    ["録画開始", formatClock(recording.startedAt)],
                    ["ここまでのファイル", escapeHtml(formatFileSize(recording.fileBytes))],
                    ["プロセス",
                        `${escapeHtml(root.name)}（<span class="nowrap">PID ${root.pid}</span>）`],
                ])
                + '<p class="secondary">アーカイブ一覧の「停止」と同じ止め方です。</p>',
        };
    }

    /**
     * 確認ダイアログを開く。window.confirm を使わないのは、止める対象の名前・一緒に止まるもの・
     * 失うものを並べられないため（レビュー #2・#11）。最初のフォーカスは「キャンセル」（autofocus）
     * に置き、Enter の押し間違いで止めないようにする。
     * @param {HostProcess} head 選んだプロセス
     */
    function openStopModal(head) {
        const children = descendantsOf(head);
        const content = head.recording
            ? recordingModal(head, head.recording) : processModal(head, children);
        modalTarget = head;
        modalTree = [head, ...children].map(processKey);
        el("modalTitle").textContent = content.title;
        modalBody.innerHTML = content.body;
        modalConfirm.textContent = content.confirm;
        modalConfirm.disabled = false;
        // 開くたびに本文が違うので、本文にある方の id を読ませる（レビュー #29）
        stopModal.setAttribute("aria-describedby", content.describedBy);
        stopModal.showModal();
        adjustPolling();
    }

    /**
     * 開いている間に読み直しで対象が無くなったら、止めるものが無いことを出し、確定を押せなくする。
     * 無くなったものを止めに行かせないため（サーバーも起動時刻で断るが、押す前に分かる方がよい。
     * レビュー #6）。
     */
    function markModalGone() {
        modalTarget = null;
        modalBody.innerHTML = '<p id="modalTarget">このプロセスはすでに終了しています</p>';
        stopModal.setAttribute("aria-describedby", "modalTarget");
        if (document.activeElement === modalConfirm) modalCancel.focus();
        modalConfirm.disabled = true;
    }

    /**
     * 選んだ行を確認ダイアログで止める。停止のボタンを押せない間は何もしない（理由は
     * renderStopButton）。
     */
    function requestStop() {
        if (stopButton.getAttribute("aria-disabled") === "true" || selected === null) return;
        const head = findProcess(selected);
        if (head) openStopModal(head);
    }

    /**
     * @param {string[]} tree 止める範囲の key（先頭が選んだプロセス）
     * @returns {string|null} 止める行の次の、選べる行の key。止める範囲の行は一緒に消えるので
     *                        飛ばす。止める行が見えていなければ null
     */
    function nextRowKey(tree) {
        const rows = [...rowsBox.querySelectorAll("tr[data-key]")]
            .flatMap((tr) => (tr instanceof HTMLElement ? [tr] : []));
        const at = rows.findIndex((tr) => tr.dataset.key === tree[0]);
        if (at < 0) return null;
        const next = rows.slice(at + 1).find((tr) => !tree.includes(tr.dataset.key ?? "")
            && tr.querySelector('input[type="radio"]:not(:disabled)'));
        return next?.dataset.key ?? null;
    }

    /**
     * 止め終わったとき、フォーカスが停止のボタンか body にあれば、止めた行の次の行の radio
     * （無ければ検索欄）へ移す。止めた行と一緒に radio が消えるとフォーカスがページの先頭へ戻り、
     * キーボードで続けて選べなくなるため（レビュー #51）。ほかの場所にあれば、そこで操作して
     * いるので動かさない。
     * @param {string|null} next 移す先の行の key
     */
    function focusAfterStop(next) {
        const active = document.activeElement;
        if (active && active !== document.body && active !== stopButton
            && active !== selectionStop) return;
        const radio = next
            ? rowsBox.querySelector(`tr[data-key="${next}"] input[type="radio"]:not(:disabled)`) : null;
        (radio instanceof HTMLElement ? radio : filterInput).focus();
    }

    /**
     * 「もう一度」。対象がまだ一覧にあれば確認ダイアログを開き直す（確かめずに止め直さない）。
     * @param {string}                 flash お知らせの key
     * @param {() => HostProcess|null} find  開き直す対象を今の一覧から探す処理
     */
    function retryStop(flash, find) {
        const target = find();
        if (target) openStopModal(target);
        else showFlash(flash, "info", "すでに終了しています");
    }

    /**
     * 停止の記録をお知らせに出し、終わっていれば待つのをやめる。
     *
     * <p>お知らせの key は止めたプロセス（PID と起動時刻）にする。確定を 2 回押した・2 つのタブで
     * 止めたときはサーバーが同じ記録を返すので 1 件のままになり、続けて 2 つを止めたときは 2 件
     * 並ぶ。「もう一度」で止め直した結果も同じ key なので、残っていたお知らせを置き換える。
     *
     * @param {ProcessStopJob} job 停止の記録
     */
    function showJob(job) {
        const pending = pendingStops.get(job.id);
        if (pending && pending.state === job.state) return;
        if (pending) pending.state = job.state;
        const key = `stop:${job.pid}:${job.startTime}`;
        const target = escapeHtml(`${job.name}（PID ${job.pid}）`);
        const all = job.children ? `${target}と子のプロセス ${job.children} 件` : target;
        const at = job.finishedAt ? formatClock(job.finishedAt) : "";
        if (job.state === "STOPPING") {
            showFlash(key, "progress", `${target}を停止しています`);
        } else if (job.state === "KILLING") {
            showFlash(key, "progress", `${target}を強制終了しています`);
        } else if (job.state === "STOPPED") {
            showFlash(key, "success", `${at} に ${all}を停止しました。`);
        } else if (job.state === "KILLED") {
            showFlash(key, "warning",
                `${at} に ${all}を強制終了しました。10 秒たっても終了しなかったためです。`);
        } else {
            showFlash(key, "error", `強制終了しても残っています（PID ${job.remaining.join("、")}）`,
                () => retryStop(key, () => findProcess(`${job.pid}:${job.startTime}`)));
        }
        if (job.state === "STOPPING" || job.state === "KILLING") return;
        pendingStops.delete(job.id);
        if (pending?.own) focusAfterStop(pending.next);
    }

    /**
     * ふつうのプロセスを止める。起動時刻を添え、選んだときと同じプロセスかをサーバーに確かめさせる
     * （PID は使い回されるため。レビュー #14）。断られたら（すでに終了・選べない・録画）理由を赤の
     * お知らせに出し、すぐ読み直して表を今の状態に合わせる。
     * @param {HostProcess} head 止めるプロセス
     * @param {string|null} next 止め終わったときにフォーカスを移す行の key
     */
    async function stopProcess(head, next) {
        try {
            /** @type {ProcessStopJob} */
            const job = await apiPost(
                `/api/system/processes/${head.pid}/stop?startTime=${head.startTime}`, {});
            if (!pendingStops.has(job.id)) {
                pendingStops.set(job.id, {
                    flash: `stop:${job.pid}:${job.startTime}`, own: true, next,
                    since: Date.now(), state: null,
                });
            }
            showJob(job);
        } catch (e) {
            showFlash(`stop:${processKey(head)}`, "error", escapeHtml(errorMessage(e)));
        }
        adjustPolling();
        refresh();
    }

    /**
     * 録画を、アーカイブ一覧の「停止」と同じ API で止める（理由は recordingModal）。common.js の
     * stopRecording は window.confirm で確かめ直すので使わない（このダイアログで確かめ済み）。
     * 止まったかは、一覧からその録画のプロセスが消えたことで確かめる（この API は止め始めた時点で
     * 返り、止まるまで待たないため）。
     * @param {HostRecording} recording 止める録画
     * @param {string|null}   next      止まったときにフォーカスを移す行の key
     */
    async function stopRecordingProcess(recording, next) {
        const flash = `recording:${recording.id}`;
        const subject = escapeHtml(recordingSubject(recording));
        try {
            await apiPost(`/api/recordings/${recording.id}/stop`, {});
            pendingRecordings.set(recording.id, { subject, startedAt: Date.now(), next });
            showFlash(flash, "progress", `${subject}を停止しています`);
        } catch (e) {
            showFlash(flash, "error", escapeHtml(errorMessage(e)),
                () => retryStop(flash, () => recordingRoot(recording.id)));
        }
        adjustPolling();
        refresh();
    }

    /**
     * 読み直した一覧で、待っている停止と録画の結果を確かめる。
     *
     * <p>読み込んだときにすでに途中だった停止は、進行中のお知らせを出して結果を待つ（画面を
     * 読み直しても、止めている最中だと分かるように。レビュー #4）。記録が無くなった停止
     * （アプリの再起動で消えた）は、止まったとも止まらなかったとも言えないので、そう出す。
     * ただし止め始める前に頼んだ一覧（自動の読み直しの途中で確定したとき）には記録がまだ無いので、
     * 消えたとは見なさない。記録の番号はアプリの起動から振り直すので、PID と起動時刻でも確かめる。
     *
     * @param {number} requestedAt 一覧を頼んだ時刻（ミリ秒）
     */
    function checkStops(requestedAt) {
        if (!system) return;
        const current = system;
        if (!stopsSeeded) {
            stopsSeeded = true;
            for (const job of current.stops) {
                if (job.state !== "STOPPING" && job.state !== "KILLING") continue;
                const flash = `stop:${job.pid}:${job.startTime}`;
                pendingStops.set(job.id, { flash, own: false, next: null, since: 0, state: null });
            }
        }
        for (const [id, pending] of pendingStops) {
            const job = current.stops.find((candidate) => candidate.id === id
                && `stop:${candidate.pid}:${candidate.startTime}` === pending.flash);
            if (job) {
                showJob(job);
            } else if (requestedAt > pending.since) {
                pendingStops.delete(id);
                showFlash(pending.flash, "info", "停止の結果を確かめられませんでした");
            }
        }
        for (const [id, pending] of pendingRecordings) {
            const flash = `recording:${id}`;
            if (!current.processes.some((p) => p.recording?.id === id)) {
                pendingRecordings.delete(id);
                showFlash(flash, "success", `${formatClock(Date.now())} に${pending.subject}を`
                    + "停止しました。そこまでの内容は「途中まで」として残ります。");
                focusAfterStop(pending.next);
            } else if (Date.now() - pending.startedAt > RECORDING_STOP_MILLIS) {
                pendingRecordings.delete(id);
                showFlash(flash, "error", `${pending.subject}を停止できませんでした`,
                    () => retryStop(flash, () => recordingRoot(id)));
            }
        }
    }

    /**
     * 止めている途中（結果を待つ停止か録画がある間）と確認ダイアログを開いている間は 2 秒ごと、
     * それ以外は 10 秒ごとに読み直す（理由は STOPPING_REFRESH_MILLIS）。ダイアログを開いている間も
     * 速めるのは、その間に終わったプロセスを、確定を押す前に「すでに終了しています」と出すため。
     */
    function adjustPolling() {
        const millis = pendingStops.size || pendingRecordings.size || stopModal.open
            ? STOPPING_REFRESH_MILLIS : REFRESH_MILLIS;
        if (millis === pollMillis) return;
        stopPolling();
        stopPolling = startVisibleRefresh(refresh, millis);
        pollMillis = millis;
    }

    /**
     * 今の値の measuredAt が、前に推移を読めたときから変わっていれば推移を読み直す。推移は
     * 1 分ごとの記録でしか増えないので、24 時間分（最大 1440 件）を 10 秒ごとに読まないため。
     * 失敗したら前の推移を残し、measuredAt を覚えないので次の読み直しでもう一度読む。
     */
    async function loadHistory() {
        if (!resources || resources.measuredAt === historyMeasuredAt) return;
        const measuredAt = resources.measuredAt;
        try {
            history = await apiGet("/api/dashboard/resources/history");
            historyMeasuredAt = measuredAt;
        } catch {
            // 前の推移のまま描く（renderCharts）
        }
    }

    /**
     * 計測の値と左メニューの状態を読み直す。片方だけ失敗したときは、読めた方だけ描く
     * （もう片方は前の値のまま）。最終更新の警告は、両方を読めた時刻から数える。
     *
     * <p>読んでいる間はボタンを回す。押したのに何も変わらないように見えないよう、読み終えた
     * ことを最終更新の書き換えと合わせて見せるため（レビュー #43）。止めるのは disabled でなく
     * aria-disabled にする。disabled にするとフォーカスがボタンから外れ、キーボードで押した人や、
     * ボタンにフォーカスを置いている人が、10 秒ごとの読み直しのたびに位置を失うため（押しても
     * loading の間は何もしないので、押せること自体は問題にならない）。
     *
     * <p>選んでいたプロセスが一覧から消えていたら、選択を外してそう知らせる。黙って外すと、選んだ
     * つもりのまま停止を押して何も起きない理由が分からないため（レビュー #6）。
     *
     * @param {boolean} [resort] プロセスの表を並べ直すか。「表示を更新」を押したときだけ true にし、
     *                           10 秒ごとの読み直しでは並びを変えない（理由は order）。読み込みの
     *                           最中に押されたら、その読み込みを描くときに並べ直す
     */
    async function refresh(resort = false) {
        if (resort) resortRequested = true;
        if (loading) return;
        loading = true;
        const icon = refreshButton.innerHTML;
        refreshButton.setAttribute("aria-disabled", "true");
        refreshButton.innerHTML = '<span class="spinner" aria-hidden="true"></span>';
        const requestedAt = Date.now();
        try {
            const [resourcesResult, systemResult] = await Promise.allSettled([
                apiGet("/api/dashboard/resources"),
                apiGet("/api/system/processes"),
            ]);
            if (resourcesResult.status === "fulfilled") resources = resourcesResult.value;
            if (systemResult.status === "fulfilled") {
                const before = selected === null ? null : findProcess(selected);
                system = systemResult.value;
                if (before && !findProcess(processKey(before))) {
                    selected = null;
                    showFlash(`gone:${processKey(before)}`, "info",
                        escapeHtml(`選んでいた ${before.name}（PID ${before.pid}）は終了していました`));
                }
            }
            if (resourcesResult.status === "fulfilled" && systemResult.status === "fulfilled") {
                lastSuccessAt = Date.now();
            }
            await loadHistory();
            settled = true;
            renderHeader();
            renderNav();
            renderResources();
            renderCharts();
            renderProcesses(resortRequested);
            resortRequested = false;
            if (systemResult.status === "fulfilled") checkStops(requestedAt);
            if (stopModal.open && modalTarget && !findProcess(processKey(modalTarget))) markModalGone();
            adjustPolling();
        } finally {
            refreshButton.innerHTML = icon;
            refreshButton.removeAttribute("aria-disabled");
            loading = false;
        }
    }

    /**
     * 狭い画面の左メニューを開け閉めする。
     *
     * <p>開いている間は本文を inert にし、Tab で幕の後ろへ抜けないようにする。閉じている間は
     * 左メニューを inert にし、画面の外へ隠れた項目へ Tab で入らないようにする（レビュー #31）。
     * 広い画面では左メニューが常に見えているので inert にしない。
     *
     * @param {boolean} open           開くなら true
     * @param {boolean} [restoreFocus] 閉じた後に ≡ へフォーカスを戻すか。幕・Esc・項目で閉じた
     *                                 とき、フォーカスが隠れた項目や消えた幕に残らないように
     *                                 するため
     */
    function setNavOpen(open, restoreFocus = false) {
        sideNav.classList.toggle("is-open", open);
        navScrim.hidden = !open;
        main.inert = open;
        sideNav.inert = narrow.matches && !open;
        navToggle.setAttribute("aria-expanded", String(open));
        navToggle.setAttribute("aria-label", open ? "メニューを閉じる" : "メニューを開く");
        if (open) query("a", sideNav).focus();
        else if (restoreFocus) navToggle.focus();
    }

    /**
     * 開いた「？」の吹き出しが右へはみ出す分だけ左へずらす。「？」は見出しの右端に来ることが
     * あり、そのまま開くと画面の外へ切れて読めないため。ずらす量は CSS が決めた今の位置から
     * 引く。枠の「？」は見出しの行の右へ開き、ページの「？」と開く位置が違うため。
     * @param {HTMLDetailsElement} help 開いた「？」
     */
    function placePopover(help) {
        const body = query(".popoverBody", help);
        body.style.left = "";
        const overflow = body.getBoundingClientRect().right
            - (document.documentElement.clientWidth - 16);
        if (overflow > 0) body.style.left = `${parseFloat(getComputedStyle(body).left) - overflow}px`;
    }

    /** @returns {HTMLDetailsElement[]} 開いている「？」 */
    const openHelps = () => [...document.querySelectorAll("details.help[open]")]
        .filter((help) => help instanceof HTMLDetailsElement);

    // 「？」は、外を押す・画面の大きさが変わる・フォーカスが外へ出る・Esc のどれでも閉じる。
    // 開いたままだと下の値に重なって読めないため（レビュー #49）。
    document.querySelectorAll("details.help").forEach((help) => {
        if (!(help instanceof HTMLDetailsElement)) return;
        help.addEventListener("toggle", () => { if (help.open) placePopover(help); });
        help.addEventListener("focusout", (event) => {
            // 吹き出しの文を押したとき（フォーカスの移り先が無い）は閉じない。
            // 外を押したときは下の click で閉じる
            const next = event.relatedTarget;
            if (next instanceof Node && !help.contains(next)) help.open = false;
        });
    });
    document.addEventListener("click", (event) => {
        for (const help of openHelps()) {
            if (!(event.target instanceof Node && help.contains(event.target))) help.open = false;
        }
    });
    window.addEventListener("resize", () => { for (const help of openHelps()) help.open = false; });

    document.addEventListener("keydown", (event) => {
        // ダイアログは Esc で自分で閉じる（cancel）。後ろの吹き出しやメニューまで一緒に閉じない
        if (event.key !== "Escape" || stopModal.open) return;
        const helps = openHelps();
        if (openLock) {
            closeLock(true);
        } else if (helps.length) {
            for (const help of helps) {
                help.open = false;
                query("summary", help).focus();
            }
        } else if (sideNav.classList.contains("is-open")) {
            setNavOpen(false, true);
        }
    });

    navToggle.addEventListener("click",
        () => setNavOpen(!sideNav.classList.contains("is-open"), true));
    navScrim.addEventListener("click", () => setNavOpen(false, true));
    sideNav.addEventListener("click", (event) => {
        // 「端末の状態」は同じ画面の中へ移るだけなので、閉じないとメニューが本文に重なったまま
        // になる
        const link = event.target instanceof Element && event.target.closest("a");
        if (!link || !sideNav.classList.contains("is-open")) return;
        setNavOpen(false);
        // #mainContent へのリンクは、既定の動き（クリックの処理の後）でフォーカスが本文へ移る。
        // それより後で ≡ へ戻すため、既定の動きが終わるのを待つ
        window.setTimeout(() => navToggle.focus());
    });
    // 広い画面へ変わったら閉じて inert を外す（広い画面では左メニューが常に見えるため）。
    // 狭い画面へ変わったときは、閉じた状態の inert を付ける
    narrow.addEventListener("change", () => setNavOpen(false));
    setNavOpen(false);

    for (const key of CHART_KEYS) {
        const box = el(`${key}Chart`);
        box.addEventListener("pointermove", (event) => showChartCursor(key, event));
        box.addEventListener("pointerdown", (event) => showChartCursor(key, event));
        // 指は離した時点で pointerleave が届くので、押して出した値がすぐ消えないよう、
        // マウスだけ消す。指で出した値は、下の pointerdown でグラフの外を押したときに消す
        box.addEventListener("pointerleave", (event) => {
            if (event.pointerType !== "touch") hideChartCursor(box);
        });
    }
    /** @param {EventTarget|null} [keep] この要素を含むグラフの値だけは残す */
    const hideChartCursors = (keep = null) => {
        for (const key of CHART_KEYS) {
            const box = el(`${key}Chart`);
            if (!(keep instanceof Node && box.contains(keep))) hideChartCursor(box);
        }
    };
    document.addEventListener("pointerdown", (event) => hideChartCursors(event.target));
    // 画面の大きさが変わると、吹き出しの位置（画面の外へ出ないようずらした量）が合わなくなる
    window.addEventListener("resize", () => hideChartCursors());

    // ---- プロセスの表の操作 ----
    /** @type {HTMLElement[]} 並べ替えの見出し */
    const sortHeaders = [...document.querySelectorAll("#processTable th[data-sort]")]
        .flatMap((th) => (th instanceof HTMLElement ? [th] : []));
    for (const th of sortHeaders) {
        query(".sortHeader", th).addEventListener("click", () => {
            // 同じ見出しなら向きを入れ替え、違う見出しなら多い順から（レビュー #39）
            const key = /** @type {ProcessSort} */ (th.dataset.sort);
            direction = key === sort && direction === "desc" ? "asc" : "desc";
            sort = key;
            for (const other of sortHeaders) {
                if (other !== th) other.removeAttribute("aria-sort");
            }
            th.setAttribute("aria-sort", direction === "desc" ? "descending" : "ascending");
            page = 1;
            renderProcesses(true);
        });
    }

    /** @param {"all"|"services"} next 出すプロセス */
    function setView(next) {
        view = next;
        page = 1;
        el("viewAll").setAttribute("aria-pressed", String(next === "all"));
        el("viewServices").setAttribute("aria-pressed", String(next === "services"));
        renderProcesses();
    }
    el("viewAll").addEventListener("click", () => setView("all"));
    el("viewServices").addEventListener("click", () => setView("services"));

    filterInput.addEventListener("input", () => {
        filter = filterInput.value.trim();
        page = 1;
        renderProcesses();
    });

    pagination.addEventListener("click", (event) => {
        const button = event.target instanceof Element ? event.target.closest("button[data-page]") : null;
        if (!(button instanceof HTMLButtonElement) || button.disabled) return;
        page = Number(button.dataset.page);
        renderProcesses();
    });

    rowsBox.addEventListener("click", (event) => {
        const target = event.target instanceof Element ? event.target : null;
        const action = target?.closest("button[data-action]");
        if (action instanceof HTMLElement) {
            // 押したボタンは描き直しで消えるので、次に使う場所へフォーカスを移す
            if (action.dataset.action === "retry") {
                refresh();
            } else if (action.dataset.action === "all") {
                setView("all");
                el("viewAll").focus();
            } else {
                filterInput.value = "";
                filter = "";
                page = 1;
                renderProcesses();
                filterInput.focus();
            }
            return;
        }
        const row = target?.closest("tr[data-key]");
        if (!target || !(row instanceof HTMLTableRowElement) || target.closest("input, .lockReason")) return;
        const key = row.dataset.key ?? null;
        if (key && target.closest(".expandButton")) {
            if (expanded.has(key)) expanded.delete(key);
            else expanded.add(key);
            renderProcesses();
            return;
        }
        // 文字を選んだ直後の click では、行の選択を変えない（PID などを写せるように。レビュー #28）
        if (!target.closest(".lockButton") && window.getSelection()?.toString()) return;
        const lock = row.querySelector(".lockButton");
        if (lock instanceof HTMLButtonElement) toggleLock(lock);
        else if (!row.classList.contains("is-stopping")) select(selected === key ? null : key);
    });
    // radio は矢印のキーでも選べる。change で選び、行を作り直さないのでフォーカスも残る
    rowsBox.addEventListener("change", (event) => {
        const radio = event.target;
        const row = radio instanceof HTMLInputElement && radio.checked ? radio.closest("tr") : null;
        if (row?.dataset.key) select(row.dataset.key);
    });
    // 選んだ行の radio で Enter を押すと、上の停止のボタンまで Tab で戻らずに止められる
    // （レビュー #36）
    rowsBox.addEventListener("keydown", (event) => {
        const radio = event.target;
        if (event.key !== "Enter" || !(radio instanceof HTMLInputElement)
            || radio.type !== "radio") return;
        if (radio.closest("tr")?.dataset.key !== selected) return;
        event.preventDefault();
        requestStop();
    });
    // 選べない理由の吹き出しは、外を押す・画面が動く・Esc（keydown）で閉じる。吹き出しは画面に
    // 対して置くので、画面や表が動くと鍵から離れるため
    document.addEventListener("click", (event) => {
        const row = openLock?.closest("tr");
        if (row && !(event.target instanceof Node && row.contains(event.target))) closeLock();
    });
    window.addEventListener("scroll", () => closeLock(), { passive: true });
    tableWrap.addEventListener("scroll", () => closeLock(), { passive: true });
    window.addEventListener("resize", () => {
        closeLock();
        markOverflow();
    });

    // ---- 停止の操作 ----
    // 停止のボタンは aria-disabled の間も押せる（フォーカスを残すため）ので、requestStop で止める
    stopButton.addEventListener("click", requestStop);
    selectionStop.addEventListener("click", requestStop);
    barWidth.addEventListener("change", renderStopButton);

    modalConfirm.addEventListener("click", () => {
        const head = modalTarget;
        if (!head || modalConfirm.disabled) return;
        const next = nextRowKey(modalTree);
        stopModal.close();
        // 止めている行を選んだままにしない（止まって消えたときに「終了していました」と出さない
        // ため）
        if (selected === processKey(head)) select(null);
        if (head.recording) stopRecordingProcess(head.recording, next);
        else stopProcess(head, next);
    });
    modalCancel.addEventListener("click", () => stopModal.close());
    buttonEl("modalClose").addEventListener("click", () => stopModal.close());
    stopModal.addEventListener("close", () => {
        modalTarget = null;
        adjustPolling();
    });
    // 背景を押して閉じるのは、押し始め（mousedown）と離した所（click）の両方が背景のときだけ。
    // 中の文字を選ぼうとして外までドラッグしただけで閉じないため（レビュー #54）
    stopModal.addEventListener("mousedown", (event) => {
        pressedBackdrop = event.target === stopModal;
    });
    stopModal.addEventListener("click", (event) => {
        if (pressedBackdrop && event.target === stopModal) stopModal.close();
        pressedBackdrop = false;
    });

    flashbar.addEventListener("click", (event) => {
        const target = event.target instanceof Element ? event.target : null;
        const entry = target ? [...flashes].find(([, flash]) => flash.node.contains(target)) : undefined;
        if (!target || !entry) return;
        const [key, flash] = entry;
        if (target.closest(".flashDismiss")) dismissFlash(key);
        else if (target.closest(".flashRetry")) flash.retry?.();
    });

    refreshButton.addEventListener("click", () => refresh(true));
    renderResources();
    renderProcesses();
    refresh();
    stopPolling = startVisibleRefresh(refresh, REFRESH_MILLIS);
    // 応答が返らないまま読み込みが止まると（サーバーが固まったときなど）、refresh からは
    // renderHeader が呼ばれない。それでも古くなったことを出せるよう、別に見直す。
    // 隠れている間は見直さない。読まないので必ず古くなり、戻った瞬間に、読み直しが返るまで
    // 「更新できていません」が一瞬出るため
    window.setInterval(() => {
        if (document.visibilityState === "visible") {
            renderHeader();
            renderStopButton();
        }
    }, 5_000);
})();
