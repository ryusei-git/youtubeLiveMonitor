// @ts-check
// 画面固有の状態をグローバルへ漏らさないため、全体を即時実行関数で包む（audit.js と同じ）。
// 各画面のスクリプトは <script> で読み込まれ、既定では common.js と同じスコープを共有するため。
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

    /** @type {Record<string, string>} 読み上げで、どの枠の注意かを言うための名前 */
    const PANEL_OWNER = { cpu: "CPU の", memory: "メモリの", disk: "ディスクの" };

    /**
     * CPU の注意の基準（%。サーバーの {@code CPU_WARNING_PERCENT} と同じ）。帯の印と、注意が
     * 出る手前の色に使う。注意は 5 分続いてから出るので、その間も今の値が高いことは見せるため。
     */
    const CPU_MARK = 85;
    /** メモリとディスクの注意の基準（%）。帯の印だけに使う */
    const USAGE_MARK = 90;

    /** @type {HostResources | null} {@code /api/dashboard/resources} の応答。未読は null */
    let resources = null;
    /** @type {Set<string>} 前回の描画で出ていた注意の key。新しく出た注意だけを読み上げるため */
    let shownWarningKeys = new Set();
    /** @type {HostProcesses | null} {@code /api/system/processes} の応答。未読なら null */
    let system = null;
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
     * @param {string}  value   サーバーの日時（ISO 形式。時差なしの LocalDateTime）
     * @param {boolean} [seconds] 秒まで出すか
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
        let values = ["-", "-"];
        if (receive !== null && send !== null) {
            const unit = rateUnit(Math.max(receive, send));
            values = [receive, send]
                .map((bytes) => `${(bytes / unit.size).toFixed(unit.digits)} ${unit.label}`);
        }
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
        if (key === "cpu") return "85% 超えが 5 分";
        if (key === "core") return "1 コアを 5 分使い切り";
        if (key === "memory") {
            return `空き ${formatPercent(s.memoryAvailableBytes / s.memoryTotalBytes * 100)}`;
        }
        // スワップは、この注意が出ている間だけ値を見せる（常に出す文字を減らすため。レビュー #17）
        if (key === "swap") return `スワップ ${(s.swapUsedBytes / s.swapTotalBytes * 100).toFixed(0)}%`;
        // disk は「空きが 10% 未満」と「録画の下限に近い・下回った」のどちらか。両方に当たるとき
        // サーバーは 10% の方だけを出すので、ここも 10% の方を先に見る
        if (s.diskTotalBytes !== null && s.diskFreeBytes !== null
            && s.diskFreeBytes < s.diskTotalBytes * 0.1) {
            return `空き ${formatPercent(s.diskFreeBytes / s.diskTotalBytes * 100)}`;
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
            settled = true;
            renderHeader();
            renderNav();
            renderResources();
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
