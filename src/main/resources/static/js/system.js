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

(() => {
    /**
     * 左メニューのサービス 1 件（Java の {@code SystemProcessesResponse.ServiceStatus}）。
     * @typedef {object} HubServiceStatus
     * @property {string} name メニューに出す名前
     * @property {string|null} url トップ画面の URL。無ければ null
     * @property {"RUNNING"|"STOPPED"|"UNKNOWN"} status 動いているか
     * @property {boolean} self この画面を動かしているサービス自身か
     */
    /**
     * {@code GET /api/system/processes} の応答のうち、この画面の骨組みで使う分。
     * @typedef {object} HostProcesses
     * @property {string} hostName 端末名
     * @property {number} uptimeSeconds 端末の稼働時間（秒）
     * @property {HubServiceStatus[]} services 左メニューのサービス。先頭はこのサービス自身
     */
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

    const sideNav = el("sideNav");
    const navToggle = buttonEl("navToggle");
    const navScrim = el("navScrim");
    const main = el("mainContent");
    const refreshButton = buttonEl("refreshButton");
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
        const stale = settled && (lastSuccessAt === 0 || Date.now() - lastSuccessAt > STALE_MILLIS);
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
     */
    async function refresh() {
        if (loading) return;
        loading = true;
        const icon = refreshButton.innerHTML;
        refreshButton.setAttribute("aria-disabled", "true");
        refreshButton.innerHTML = '<span class="spinner" aria-hidden="true"></span>';
        try {
            const [resourcesResult, systemResult] = await Promise.allSettled([
                apiGet("/api/dashboard/resources"),
                apiGet("/api/system/processes"),
            ]);
            if (resourcesResult.status === "fulfilled") resources = resourcesResult.value;
            if (systemResult.status === "fulfilled") system = systemResult.value;
            if (resourcesResult.status === "fulfilled" && systemResult.status === "fulfilled") {
                lastSuccessAt = Date.now();
            }
            await loadHistory();
            settled = true;
            renderHeader();
            renderNav();
            renderResources();
            renderCharts();
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
        if (event.key !== "Escape") return;
        const helps = openHelps();
        if (helps.length) {
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

    refreshButton.addEventListener("click", () => refresh());
    renderResources();
    refresh();
    startVisibleRefresh(refresh, 10_000);
    // 応答が返らないまま読み込みが止まると（サーバーが固まったときなど）、refresh からは
    // renderHeader が呼ばれない。それでも古くなったことを出せるよう、別に見直す。
    // 隠れている間は見直さない。読まないので必ず古くなり、戻った瞬間に、読み直しが返るまで
    // 「更新できていません」が一瞬出るため
    window.setInterval(() => {
        if (document.visibilityState === "visible") renderHeader();
    }, 5_000);
})();
