// @ts-check
/**
 * 稼働秒数を「◯時間◯分◯秒」に整形する。
 *
 * @param {number} seconds 稼働秒数
 * @returns {string} 整形済みの文字列
 */
function formatUptime(seconds) {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = Math.floor(seconds % 60);
    return `${h}時間${m}分${s}秒`;
}

/**
 * ディスク使用量の合計と内訳を描く。
 *
 * <p>同じ内訳をドーナツと横棒の両方で出している。ドーナツは「全体に対する割合」、
 * 横棒は「順位と差」を読むためのもので、チャンネルが増えると前者は判別できなくなるが
 * 後者は読み続けられる。
 */
async function loadDiskUsage() {
    try {
        const data = await apiGet("/api/recordings/disk-usage");
        el("diskUsage").textContent = formatFileSize(data.totalBytes);

        // API が使用量の多い順に返すので、その順番がそのまま色の濃さの順になる
        const segments = /** @type {any[]} */ (data.byChannel).map((c, index) => ({
            label: c.channelName,
            value: c.bytes,
            color: rampColor(index),
            display: formatFileSize(c.bytes),
        }));

        el("diskDonutChart").innerHTML =
            donutChart(segments.filter((segment) => segment.value > 0), formatFileSize(data.totalBytes).split(" ")[0],
                formatFileSize(data.totalBytes).split(" ")[1] || "B");
        el("diskBarChart").innerHTML = barChart(segments);
    } catch (e) {
        // ダッシュボードの主目的はチャンネル監視の集計なので、ここの失敗で他の表示まで止めない
        el("diskUsage").textContent = "取得失敗";
        el("diskDonutChart").innerHTML = '<p class="muted">取得に失敗しました</p>';
        el("diskBarChart").innerHTML = '<p class="muted">取得に失敗しました</p>';
    }
}

/**
 * チャンネルの状態の内訳を描く。
 *
 * <p>区画は「配信中」と「休止中」だけにしている。判定失敗のチャンネルは配信中・休止中の
 * どちらかにも数えられており、3つ目の区画として足すと合計がチャンネル数を超えて
 * 面積の比が意味を失うため。判定失敗は上の警告欄が担当する。
 *
 * @param {number} total 監視チャンネル数
 * @param {number} liveNow そのうち配信中の数
 */
function renderChannelStatusChart(total, liveNow) {
    el("channelStatusChart").innerHTML = donutChart([
        { label: "配信中", value: liveNow, color: "var(--chart-active)" },
        { label: "休止中", value: total - liveNow, color: "var(--chart-3)" },
    ], String(total), "CHANNELS");
}

/**
 * 録画履歴の状態の内訳を描く。
 *
 * @param {{completed: number, partial: number, recording: number, failed: number}} status 状態別の件数
 */
function renderRecordingStatusChart(status) {
    const total = status.completed + status.partial + status.recording + status.failed;
    el("recordingStatusChart").innerHTML = donutChart([
        { label: "完了", value: status.completed, color: "var(--chart-1)" },
        { label: "途中まで", value: status.partial, color: "var(--chart-3)" },
        { label: "録画中", value: status.recording, color: "var(--chart-active)" },
        { label: "失敗", value: status.failed, color: "var(--chart-failure)" },
    ], String(total), "RECORDINGS");
}

/**
 * 判定に連続失敗しているチャンネルの警告を出す。
 * 該当が無い平常時は見出しごと隠し、画面を余計に占有しないようにする。
 *
 * @param {any[]} failingChannels 連続失敗中のチャンネル
 */
function renderDetectionAlert(failingChannels) {
    const alertBox = el("detectionAlert");
    if (!failingChannels || failingChannels.length === 0) {
        alertBox.style.display = "none";
        return;
    }
    alertBox.style.display = "";

    const tbody = query("#detectionAlertTable tbody");
    tbody.innerHTML = "";
    for (const c of failingChannels) {
        const tr = document.createElement("tr");
        tr.innerHTML = `
            <td class="revealable" title="クリックでチャンネルIDを表示">${escapeHtml(c.channelName)}</td>
            <td class="error">${c.consecutiveFailures}回</td>
            <td>${c.lastDetectionSuccessAt ? datetimeCell(c.lastDetectionSuccessAt) : "一度も成功していません"}</td>
        `;
        query("td", tr).addEventListener("click", (ev) => toggleChannelIdReveal(/** @type {HTMLTableCellElement} */ (ev.currentTarget), c.youtubeChannelId));
        tbody.appendChild(tr);
    }
    bindDatetimeCells(tbody);
}

/**
 * 直近で録画に失敗した配信の警告を出す。
 *
 * <p>判定失敗の警告欄と同じく、平常時（0 件）は見出しごと隠して画面を占有しない。
 * 取得に失敗したときは「失敗なし」と区別できるよう、欄を出して取得できなかったことを示す。
 */
async function loadRecordingFailures() {
    const alertBox = el("recordingFailureAlert");
    const box = el("recordingFailures");
    try {
        /** @type {Array<{channelName: string, channelUrl: string|null, videoTitle: string|null, videoUrl: string|null, startedAt: string}>} */
        const failures = await apiGet("/api/dashboard/recording-failures");
        if (failures.length === 0) {
            alertBox.style.display = "none";
            return;
        }
        const rows = failures.map(f => `
        <tr>
            <td>${datetimeCell(f.startedAt)}</td>
            <td>${externalLink(f.channelName, f.channelUrl)}</td>
            <td>${externalLink(f.videoTitle ?? "（タイトル不明）", f.videoUrl)}</td>
        </tr>`).join("");
        box.innerHTML = `
        <div class="table-scroll">
            <table id="recordingFailureTable">
                <thead><tr><th>日時</th><th>チャンネル</th><th>動画タイトル</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
        </div>`;
        bindDatetimeCells(box);
    } catch {
        box.innerHTML = '<p class="muted">取得できませんでした</p>';
    }
    alertBox.style.display = "";
}

/**
 * サービスを構成するファイル（録画・ログ・アプリ本体・データベース）の容量を表にする。
 *
 * <p>録画だけのディスク使用量では、ログや DB の肥大化に気づけないため別に出している。
 * ディレクトリの走査は録画の量に比例して重いので、毎分の自動更新（loadDashboard）には含めず、
 * 開いたときと利用者が求めたとき（表示を更新・今すぐチェック）だけ読む（#190）。
 *
 * <p>上部のディスク使用量（loadDiskUsage）も同じ理由で、同じ時機にだけ読む。
 * この表の「録画」と同じ値なので、片方だけ読み直すと同じ画面で 2 つの値が食い違う。
 */
async function loadServiceStorage() {
    const box = el("serviceStorage");
    loadStorageForecast();
    try {
        /** @type {{totalBytes: number, items: Array<{key: string, label: string, path: string, bytes: number}>}} */
        const data = await apiGet("/api/dashboard/storage");
        const rows = data.items.map(item => `
        <tr>
            <td>${escapeHtml(item.label)}</td>
            <td>${escapeHtml(item.path)}</td>
            <td>${escapeHtml(formatFileSize(item.bytes))}</td>
        </tr>`).join("");
        box.innerHTML = `
        <div class="table-scroll">
            <table id="serviceStorageTable">
                <thead><tr><th>項目</th><th>場所</th><th>容量</th></tr></thead>
                <tbody>${rows}
                    <tr><td><strong>合計</strong></td><td></td><td><strong>${escapeHtml(formatFileSize(data.totalBytes))}</strong></td></tr>
                </tbody>
            </table>
        </div>`;
    } catch {
        box.innerHTML = '<p class="muted">取得できませんでした</p>';
    }
}

/**
 * 録画が止まる（空きが録画を始めるしきい値を割る）までの見込みと、その根拠を 1 行で出す。
 *
 * <p>空きが 0 になる時ではなく録画が止まる時を出すのは、利用者が知りたいのがそちらだから（#816）。
 * 根拠を添えるのは、録画の多かった日が続いた直後などに見込みが極端になっても、理由を読めるようにするため。
 * 容量の表と同じ時機に読む（loadServiceStorage から呼ぶ）。
 */
async function loadStorageForecast() {
    const box = el("storageForecast");
    try {
        /** @type {{freeBytes: number|null, reserveBytes: number, dailyGrowthBytes: number, windowDays: number,
         *   hoursUntilFull: number|null, fullAt: string|null, status: string}} */
        const data = await apiGet("/api/dashboard/storage-forecast");
        let headline;
        if (data.status === "STOPPED") {
            headline = "空きがしきい値を下回っているため、新しい録画が止まっています";
        } else if (data.status === "NO_GROWTH") {
            headline = "ディスクが埋まるまで: 見込みなし（録画が増えていません）";
        } else if (data.status === "UNKNOWN" || data.hoursUntilFull == null || !data.fullAt) {
            headline = "ディスクが埋まるまで: 空き容量を読めませんでした";
        } else {
            const days = Math.floor(data.hoursUntilFull / 24);
            const fullAt = new Date(data.fullAt);
            headline = `ディスクが埋まるまで: あと約 ${days} 日（${data.hoursUntilFull} 時間）・`
                + `${fullAt.getMonth() + 1} 月 ${fullAt.getDate()} 日ごろ`;
        }
        const free = data.freeBytes == null ? "不明" : formatFileSize(data.freeBytes);
        const basis = `空き ${free}・しきい値 ${formatFileSize(data.reserveBytes)}・`
            + `1 日あたり ${formatFileSize(data.dailyGrowthBytes)} 増加（直近 ${data.windowDays} 日で計測）`;
        const alert = data.status === "WARNING" || data.status === "STOPPED";
        box.innerHTML = `<p class="${alert ? "error" : ""}"><strong>${escapeHtml(headline)}</strong></p>
        <p class="muted">${escapeHtml(basis)}</p>`;
    } catch {
        box.innerHTML = '<p class="muted">見込みを取得できませんでした</p>';
    }
}

/**
 * @typedef {{pid: number, name: string, cpuPercent: number|null, memoryBytes: number}} ResourceProcess
 * @typedef {ResourceProcess & {label: string, children: ResourceProcess[]}} RecorderProcess
 * @typedef {ResourceProcess & {purpose: string, children: ResourceProcess[]}} HelperProcess
 * @typedef {object} ResourceSnapshot
 * @property {string} measuredAt
 * @property {{cpuPercent: number|null, cores: number, loadAverage1m: number|null,
 *   memoryTotalBytes: number, memoryUsedBytes: number, memoryAvailableBytes: number,
 *   swapTotalBytes: number, swapUsedBytes: number, diskPath: string, diskTotalBytes: number|null, diskFreeBytes: number|null,
 *   networkReceiveBytesPerSecond: number|null, networkSendBytesPerSecond: number|null}} system
 * @property {{cpuPercent: number|null, memoryBytes: number,
 *   application: {pid: number, cpuPercent: number|null, memoryBytes: number, heapUsedBytes: number, heapMaxBytes: number, threads: number},
 *   recorders: RecorderProcess[], helpers: HelperProcess[]}} service
 * @property {Array<{key: string, message: string}>} warnings
 * @typedef {{at: string, systemCpuPercent: number|null, systemMemoryUsedPercent: number,
 *   serviceCpuPercent: number|null, serviceMemoryBytes: number}} ResourceHistoryPoint
 */

/**
 * CPU 使用率を表示用に整える。起動直後は前回の計測が無く null が返るため "-" にする。
 *
 * @param {number|null|undefined} percent 使用率
 * @returns {string} 表示用の文字列
 */
function formatPercent(percent) {
    return percent === null || percent === undefined ? "-" : `${percent.toFixed(1)}%`;
}

/**
 * 端末全体のカードを 1 枚作る。
 *
 * <p>注意の対象になっている値はカードごと警告の色にする。先頭の警告文を読まなくても、
 * どの資源が足りないのかを目で追えるようにするため。
 *
 * @param {string} label 項目名
 * @param {string} valueHtml 大きく出す値（エスケープ済み）
 * @param {string} subHtml 補足（エスケープ済み）
 * @param {boolean} warned 注意の対象か
 * @param {number|null} [usedRatio] 使用率の横棒に出す割合（0〜1）。省くと横棒を出さない
 * @returns {string} 差し込む HTML
 */
function resourceCard(label, valueHtml, subHtml, warned, usedRatio = null) {
    const meter = usedRatio === null ? "" : `<div class="barTrack resourceMeter">`
        + `<div class="barFill" style="width:${(Math.min(Math.max(usedRatio, 0), 1) * 100).toFixed(1)}%;`
        + `background:${warned ? "var(--chart-failure)" : "var(--chart-1)"}"></div></div>`;
    return `<div class="kpi${warned ? " is-warning" : ""}">
        <span class="kpiLabel">${escapeHtml(label)}</span>
        <span class="kpiValue">${valueHtml}</span>
        ${meter}
        <span class="kpiSub">${subHtml}</span>
    </div>`;
}

/**
 * 端末全体の今の値をカードに並べる。
 *
 * @param {ResourceSnapshot["system"]} system 端末全体の値
 * @param {Set<string>} warned 注意の対象になっている項目の key
 * @returns {string} 差し込む HTML
 */
function renderSystemCards(system, warned) {
    // 主の値（「227.0 MB」）は数字と単位の間で折り返さないよう kpiNumber で包む。
    // 折り返してよいのは主の値と「/ 合計」の間だけ
    // （カード 5 枚の横並びで幅が足りないため）
    const size = (/** @type {number|null} */ bytes) => escapeHtml(formatFileSize(bytes));
    const rate = (/** @type {number|null} */ bytes) => bytes === null ? "-" : `${size(bytes)}/s`;
    // 1 分平均負荷は OS が提供しないと null（Windows など）。ここで例外になると loadResources の catch が
    // リソース欄全体を「取得できませんでした」にしてしまうため、取れない値は項目ごとに "-" で出す
    const load = system.loadAverage1m === null ? "-" : system.loadAverage1m.toFixed(1);
    // 保存先の容量を読めなかったとき（null）は、使用率の横棒を出さない（0% や 100% と誤読させない）
    const diskUsedRatio = system.diskTotalBytes === null || system.diskFreeBytes === null
        ? null : 1 - system.diskFreeBytes / system.diskTotalBytes;
    const swap = system.swapTotalBytes > 0
        ? resourceCard("スワップ", `<span class="kpiNumber">${size(system.swapUsedBytes)}</span><span class="kpiUnit">/ ${size(system.swapTotalBytes)}</span>`,
            "", warned.has("swap"), system.swapUsedBytes / system.swapTotalBytes)
        : resourceCard("スワップ", "なし", "", false);
    return `<div class="kpiGrid resourceGrid">
        ${resourceCard("CPU", escapeHtml(formatPercent(system.cpuPercent)),
            `${system.cores} コア・負荷 ${load}`, warned.has("cpu"))}
        ${resourceCard("メモリ", `<span class="kpiNumber">${size(system.memoryUsedBytes)}</span><span class="kpiUnit">/ ${size(system.memoryTotalBytes)}</span>`,
            `空き ${size(system.memoryAvailableBytes)}`, warned.has("memory"), system.memoryUsedBytes / system.memoryTotalBytes)}
        ${swap}
        ${resourceCard("ディスク（録画の保存先）", `<span class="kpiNumber">${size(system.diskFreeBytes)}</span><span class="kpiUnit">空き / ${size(system.diskTotalBytes)}</span>`,
            escapeHtml(system.diskPath), warned.has("disk"), diskUsedRatio)}
        ${resourceCard("ネットワーク", `<span class="resourceRate">↓ ${rate(system.networkReceiveBytesPerSecond)}</span>`
            + `<span class="resourceRate">↑ ${rate(system.networkSendBytesPerSecond)}</span>`, "受信・送信", false)}
    </div>`;
}

/**
 * このサービスのプロセス（アプリ本体・録画プロセス・アプリが起動したその他の外部プロセスと、それぞれの子）を表にする。
 *
 * <p>yt-dlp が起動する ffmpeg は字下げして親の下に置く。録画 1 本がどれだけ食っているかを
 * 親子の組で読めるようにするため。録画以外の外部プロセス（耳キスの検出の ffmpeg、「端末に保存」の yt-dlp など）は
 * 録画の後に並べ、対象の欄に用途を出す。
 *
 * @param {ResourceSnapshot["service"]} service このサービスの値
 * @returns {string} 差し込む HTML
 */
function renderServiceProcesses(service) {
    /**
     * @param {string} name プロセス名の HTML
     * @param {string} target 対象
     * @param {number|null} cpu CPU 使用率
     * @param {string} memory メモリの HTML
     */
    const row = (name, target, cpu, memory) =>
        `<tr><td>${name}</td><td>${escapeHtml(target)}</td><td>${escapeHtml(formatPercent(cpu))}</td><td>${memory}</td></tr>`;
    const app = service.application;
    const rows = [row(`アプリ本体 <span class="muted">PID ${app.pid}</span>`, "—", app.cpuPercent,
        `${escapeHtml(formatFileSize(app.memoryBytes))} <span class="muted">ヒープ ${escapeHtml(formatFileSize(app.heapUsedBytes))}`
        + ` / ${escapeHtml(formatFileSize(app.heapMaxBytes))}・スレッド ${app.threads}</span>`)];
    /**
     * 親のプロセスの行と、その子孫を字下げした行を足す。
     *
     * @param {ResourceProcess & {children: ResourceProcess[]}} parent 親のプロセス
     * @param {string} target 対象の欄に出す文字列（録画はチャンネル名、それ以外は用途）
     */
    const pushTree = (parent, target) => {
        rows.push(row(`${escapeHtml(parent.name)} <span class="muted">PID ${parent.pid}</span>`, target,
            parent.cpuPercent, escapeHtml(formatFileSize(parent.memoryBytes))));
        for (const child of parent.children) {
            rows.push(row(`<span class="processChild">└ ${escapeHtml(child.name)}</span> <span class="muted">PID ${child.pid}</span>`, "",
                child.cpuPercent, escapeHtml(formatFileSize(child.memoryBytes))));
        }
    };
    for (const recorder of service.recorders) {
        pushTree(recorder, recorder.label);
    }
    for (const helper of service.helpers) {
        pushTree(helper, helper.purpose);
    }
    rows.push(row("<strong>合計</strong>", "", service.cpuPercent,
        `<strong>${escapeHtml(formatFileSize(service.memoryBytes))}</strong>`));
    return `<div class="table-scroll">
        <table id="resourceProcessTable">
            <thead><tr><th>プロセス</th><th>対象・用途</th><th>CPU</th><th>メモリ</th></tr></thead>
            <tbody>${rows.join("")}</tbody>
        </table>
    </div>`;
}

/**
 * 直近 24 時間の推移を折れ線にする。
 *
 * <p>「異常な消費」は今の値よりも推移のほうが見つけやすい（じわじわ増えるメモリなど）。
 * 端末全体とこのサービスを同じ CPU のグラフに重ね、端末の負荷のうちどれだけがこのサービス由来かを読めるようにする。
 * メモリは単位が違う（% とバイト）ため、2 本目の縦軸を作らず小さな別のグラフに分ける。
 *
 * @param {ResourceHistoryPoint[]} history 古い順の記録
 * @returns {string} 差し込む HTML
 */
function renderResourceHistory(history) {
    // 線を引くには 2 点以上いる。起動直後は 1 分ごとの記録がまだ溜まっていない
    if (history.length < 2) {
        return '<p class="muted">記録を集めています（1 分ごと）</p>';
    }
    const times = history.map((p) => new Date(p.at));
    const percent = { max: 100, format: (/** @type {number} */ v) => `${Math.round(v)}%` };
    return `<div class="chartRow resourceCharts">
        <div class="chartCard">
            <h2>CPU（直近 24 時間）</h2>
            ${lineChart(times, [
                { label: "端末全体", color: "var(--chart-3)", values: history.map((p) => p.systemCpuPercent) },
                { label: "このサービス", color: "var(--chart-1)", values: history.map((p) => p.serviceCpuPercent) },
            ], percent)}
        </div>
        <div class="chartCard">
            <h2>メモリ（直近 24 時間）</h2>
            ${lineChart(times, [
                { label: "端末全体の使用率", color: "var(--chart-3)", values: history.map((p) => p.systemMemoryUsedPercent) },
            ], percent)}
            ${lineChart(times, [
                { label: "このサービスの使用量", color: "var(--chart-1)", values: history.map((p) => p.serviceMemoryBytes) },
            ], { format: formatFileSize, height: 100 })}
        </div>
    </div>`;
}

/**
 * このサービスが使っているリソース（端末全体・プロセス・推移）を出す。
 *
 * <p>今の値と推移は別の API だが、片方だけ出ても判断材料として半端なので、
 * どちらかが失敗したらまとまりごと「取得できませんでした」にする。
 * ダッシュボードの他の表示は巻き込まない（#136 のファイル容量と同じ扱い）。
 */
async function loadResources() {
    const box = el("resources");
    try {
        /** @type {[ResourceSnapshot, ResourceHistoryPoint[]]} */
        const [now, history] = await Promise.all([
            apiGet("/api/dashboard/resources"),
            apiGet("/api/dashboard/resources/history"),
        ]);
        const warnings = now.warnings.map((w) => `<p class="error resourceWarning">${escapeHtml(w.message)}</p>`).join("");
        // サーバーは 1 分ごとの記録の値を返す（最大 1 分古い）ので、いつの値かを添える
        box.innerHTML = `<p class="muted">${escapeHtml(formatDateTimeSimple(now.measuredAt))} 時点の値（1 分ごとに更新）</p>`
            + warnings
            + renderSystemCards(now.system, new Set(now.warnings.map((w) => w.key)))
            + renderServiceProcesses(now.service)
            + renderResourceHistory(history);
    } catch {
        box.innerHTML = '<p class="muted">取得できませんでした</p>';
    }
}

/**
 * 選択肢に無い現在値だった場合、末尾に選択肢を追加してそれを選択状態にする。
 *
 * <p>{@code .env} を直接編集して、プルダウンの選択肢に無い値（例: 監視間隔を250秒）に
 * している利用者もいうるため。用意した選択肢に無いからといって黙って別の値に
 * 変わってしまうと、保存した瞬間に意図しない値へ変更することになる。
 *
 * @param {HTMLSelectElement} select 対象の選択欄
 * @param {number} currentValue 現在の値
 * @param {(value: number) => string} formatLabel 選択肢に無い場合に表示するラベルを作る関数
 */
function ensureOptionPresent(select, currentValue, formatLabel) {
    const value = String(currentValue);
    const exists = [...select.options].some((option) => option.value === value);
    if (!exists) {
        const option = document.createElement("option");
        option.value = value;
        option.textContent = formatLabel(currentValue);
        select.appendChild(option);
    }
    select.value = value;
}

/**
 * フォームに入れた値（.env に保存済みの値。無ければ適用中の値）。保存のときにこれと比べ、変えた項目だけを送る。
 *
 * <p>読み込めていない間は null にして、保存させない。読めないまま保存すると選択欄の先頭の値
 * （60 秒・上限なし）が送られ、本当の設定を上書きしてしまうため。
 *
 * <p>{@code pendingRestart} は読み込んだときの再起動待ちの文。保存が要らなかったときや失敗したときに
 * 結果の欄をこれへ戻し、再起動待ちの表示が消えないようにする。
 *
 * @type {{intervalSeconds: number, recordingDirectory: string, recordingMaxHeight: number,
 *     pendingRestart: string} | null}
 */
let loadedSettings = null;

/**
 * 設定フォームの保存ボタン。
 *
 * @returns {HTMLButtonElement}
 */
function settingsSubmitButton() {
    return /** @type {HTMLButtonElement} */ (query("button[type=submit]", formEl("settingsForm")));
}

/**
 * 再起動待ちの項目を、画面の項目名で並べた文にする。
 *
 * <p>保存した直後だけでなく、画面を開き直しても再起動待ちだと分かるようにするため、
 * 読み込むたびにサーバーの応答から作り直す。
 *
 * @param {any} s GET /api/settings の応答
 * @returns {string} 再起動待ちが無ければ空文字
 */
function pendingRestartText(s) {
    /** @type {string[]} */
    const keys = s.pendingRestartKeys ?? [];
    if (keys.length === 0) return "";
    const labels = keys.map((key) => {
        switch (key) {
            case "MONITOR_INTERVAL_SECONDS":
                return `監視間隔（適用中: ${s.intervalSeconds}秒）`;
            case "MONITOR_RECORDING_DIRECTORY":
                return `録画の保存先（適用中: ${s.recordingDirectory}）`;
            case "MONITOR_RECORDING_MAX_HEIGHT":
                return `録画の画質上限（適用中: ${s.recordingMaxHeight > 0 ? `${s.recordingMaxHeight}p` : "上限なし"}）`;
            case "YOUTUBE_API_KEY":
                return "YouTube APIキー";
            case "DISCORD_WEBHOOK_URL":
                return "Discord Webhook";
            case "TWITCH_CLIENT_ID":
                return "Twitch Client ID";
            case "TWITCH_CLIENT_SECRET":
                return "Twitch Client Secret";
            default:
                return key;
        }
    });
    return `再起動待ち：${labels.join("、")}。保存済みですが、bin/service.sh restart まで反映されません。`;
}

/**
 * 現在の設定値をフォームへ反映する。
 *
 * <p>秘密情報（APIキー・Webhook URL）の実際の値はサーバーから返ってこない
 * （設定されているかどうかのみ。{@code SettingsResponse}のJavaDoc参照）ため、
 * 欄は空のままにし、プレースホルダーで設定有無だけを示す。
 *
 * <p>フォームには .env に保存済みの値を入れる（無ければ適用中の値）。適用中の値を入れると、
 * 再起動待ちの変更が画面から見えない。そのまま別の項目を保存すると、黙って旧値へ戻してしまうため。
 *
 * @returns {Promise<boolean>} 読み込めたら true
 */
async function loadSettings() {
    const submitBtn = settingsSubmitButton();
    const result = el("settingsSaveResult");
    loadedSettings = null;
    submitBtn.disabled = true;
    try {
        const s = await apiGet("/api/settings");
        const intervalSeconds = s.savedIntervalSeconds ?? s.intervalSeconds;
        const recordingDirectory = s.savedRecordingDirectory ?? s.recordingDirectory;
        const recordingMaxHeight = s.savedRecordingMaxHeight ?? s.recordingMaxHeight;
        ensureOptionPresent(selectEl("settingIntervalSelect"), intervalSeconds,
            (value) => `${value}秒（現在の値）`);
        ensureOptionPresent(selectEl("settingMaxHeightSelect"), recordingMaxHeight,
            (value) => value > 0 ? `${value}px（現在の値）` : "上限なし（最高画質）");
        inputEl("settingRecordingDirInput").value = recordingDirectory;
        inputEl("settingApiKeyInput").placeholder =
            s.youTubeApiKeyConfigured ? "変更する場合のみ入力（設定済み）" : "未設定";
        inputEl("settingWebhookInput").placeholder =
            s.discordWebhookConfigured ? "変更する場合のみ入力（設定済み）" : "未設定";
        // Client ID と Secret は片方だけでは認証できないため、まとめて1つの状態として扱う
        const twitchPlaceholder = s.twitchConfigured ? "変更する場合のみ入力（設定済み）" : "未設定";
        inputEl("settingTwitchClientIdInput").placeholder = twitchPlaceholder;
        inputEl("settingTwitchClientSecretInput").placeholder = twitchPlaceholder;
        const pending = pendingRestartText(s);
        result.textContent = pending;
        query("summary", el("monitorSettings")).textContent =
            pending ? "監視・録画の設定（再起動待ちあり）" : "監視・録画の設定";
        loadedSettings = { intervalSeconds, recordingDirectory, recordingMaxHeight, pendingRestart: pending };
        submitBtn.disabled = false;
        return true;
    } catch (e) {
        result.textContent = "設定を読み込めなかったため保存できません。画面を開き直してください。";
        showError(errorMessage(e));
        return false;
    }
}

/**
 * OS のフォルダ選択ダイアログを開き、選ばれたパスを保存先の入力欄へ反映する。
 *
 * <p>このダイアログはサーバー側のプロセスから起動される（{@code NativeDirectoryPickerService}
 * 参照）。ブラウザとサーバーが同じマシン上にある運用が前提で、ダイアログは<b>サーバー側の画面に</b>
 * 表示される。人がダイアログを操作して閉じるまで応答が返らないため、ボタンは押している間
 * 無効化するだけにし、通常のAPI呼び出しのような短いタイムアウト感を出さないようにする。
 */
buttonEl("browseDirectoryBtn").addEventListener("click", async () => {
    const btn = buttonEl("browseDirectoryBtn");
    const dirInput = inputEl("settingRecordingDirInput");
    const startPath = dirInput.value.trim();

    btn.disabled = true;
    try {
        const params = startPath ? `?initialDirectory=${encodeURIComponent(startPath)}` : "";
        const result = await apiPost(`/api/settings/directories/pick${params}`, {});
        clearError();
        // result が null なのはダイアログがキャンセルされた場合（204）。入力欄はそのままにする
        if (result) {
            dirInput.value = result.path;
        }
    } catch (e) {
        showError(errorMessage(e), { reveal: true });
    } finally {
        btn.disabled = false;
    }
});

formEl("settingsForm").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const loaded = loadedSettings;
    // 読み込めていない間は送らない（ボタンも無効だが、入力欄での Enter による送信もここで止める）
    if (loaded === null) return;
    const submitBtn = settingsSubmitButton();
    const result = el("settingsSaveResult");
    const apiKeyInput = inputEl("settingApiKeyInput");
    const webhookInput = inputEl("settingWebhookInput");
    const twitchClientIdInput = inputEl("settingTwitchClientIdInput");
    const twitchClientSecretInput = inputEl("settingTwitchClientSecretInput");
    const intervalSeconds = Number(selectEl("settingIntervalSelect").value);
    const recordingDirectory = inputEl("settingRecordingDirInput").value.trim();
    const recordingMaxHeight = Number(selectEl("settingMaxHeightSelect").value);

    // 変えた項目だけを送る（null はサーバー側で「変更しない」）。変えていない項目まで送ると、
    // 画面を開いた後に保存された値を、この画面が持っている古い値で黙って上書きしてしまうため
    const body = {
        youtubeApiKey: apiKeyInput.value || null,
        discordWebhookUrl: webhookInput.value || null,
        twitchClientId: twitchClientIdInput.value || null,
        twitchClientSecret: twitchClientSecretInput.value || null,
        intervalSeconds: intervalSeconds !== loaded.intervalSeconds ? intervalSeconds : null,
        recordingDirectory: recordingDirectory !== "" && recordingDirectory !== loaded.recordingDirectory
            ? recordingDirectory : null,
        recordingMaxHeight: recordingMaxHeight !== loaded.recordingMaxHeight ? recordingMaxHeight : null,
    };
    if (Object.values(body).every((value) => value === null)) {
        result.textContent = `変更された項目がありません。${loaded.pendingRestart}`;
        return;
    }

    submitBtn.disabled = true;
    result.textContent = "保存中...";
    try {
        await apiPut("/api/settings", body);
        clearError();
        // 秘密情報は保存後に画面へ残さない（次に空欄のまま保存しても「変更しない」扱いになる）
        apiKeyInput.value = "";
        webhookInput.value = "";
        twitchClientIdInput.value = "";
        twitchClientSecretInput.value = "";
    } catch (e) {
        // 400・500 なら .env は変わっていない（SettingsController.updateSettings() 参照）ので、
        // 読み込んだときの再起動待ちの文を残す（消すと、再起動待ちの変更まで消えたように見える）。
        // 保存ボタンは画面の下にあり、理由を出すエラー帯は画面の外になるので、
        // 失敗したことをボタンの横にも出し、帯まで画面を動かす（#574 と同じ扱い）
        result.textContent = `保存できませんでした（理由は画面上部）。${loaded.pendingRestart}`;
        showError(errorMessage(e), { reveal: true });
        submitBtn.disabled = false;
        return;
    }
    // 保存した値と再起動待ちの表示を、サーバーの .env から読み直す（保存ボタンの有効化も loadSettings が行う）
    if (await loadSettings()) {
        result.textContent = `保存しました。${result.textContent}`;
    } else {
        result.textContent = "保存しましたが、設定を読み直せませんでした。画面を開き直してください。";
    }
});

/**
 * 日時を「YYYY-MM-DDTHH:mm」にする。datetime-local の入力欄と、通知履歴 API の since・until が受け付ける形。
 *
 * <p>toISOString() は UTC になるため使わない。通知時刻（notifiedAt）はサーバーの時計の時刻で記録され、
 * 管理画面はブラウザとサーバーが同じ時間帯にある前提でその時刻をそのまま表示・検索している。
 *
 * @param {Date} date 変換する日時（ブラウザの時間帯で読む）
 * @returns {string} 秒を切り捨てた日時の文字列
 */
function toLocalDateTimeParam(date) {
    /** @param {number} n */
    const pad = (n) => String(n).padStart(2, "0");
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * 「24時間の通知」の KPI に添える送信失敗の件数を描く。
 *
 * <p>1 件以上なら、通知履歴を「失敗・直近 24 時間」で絞り込んだ画面へのリンクにする。
 * 以前は赤字の文字だけで、失敗した通知を探すには通知履歴を 20 件ずつ送って目で探すしかなかった。
 * リンクは作り直さずに href と文字だけを書き換える。1 分ごとの自動更新で要素を作り直すと、
 * キーボードでリンクに合わせたフォーカスが body に落ちるため。
 *
 * @param {number} failureCount 直近 24 時間の送信失敗の件数（/api/dashboard の notificationFailuresLast24h）
 */
function renderNotificationFailures(failureCount) {
    const failures = el("notificationFailures");
    // 0件なら平常なので目立たせない。1件でもあれば赤字にして気づけるようにする
    failures.className = failureCount > 0 ? "kpiSub alert" : "kpiSub";
    if (failureCount <= 0) {
        failures.textContent = "送信失敗なし";
        return;
    }
    let link = failures.querySelector("a");
    if (!link) {
        link = document.createElement("a");
        failures.replaceChildren(link);
    }
    // サーバーの集計（DashboardService の直近 24 時間）と同じ範囲を、ブラウザの時計で作る。
    // 分未満は切り捨てるので、集計より最大 1 分ぶん古い通知まで含むことがある
    const since = toLocalDateTimeParam(new Date(Date.now() - 24 * 60 * 60 * 1000));
    link.href = `/notifications.html?${new URLSearchParams({ status: "FAILED", since })}`;
    link.textContent = `うち送信失敗 ${failureCount}件`;
}

let dashboardRequest = 0;
/** @type {Date|null} */
let dashboardLastUpdatedAt = null;
/**
 * 配信中のカードと配信予定の表を前回描いた内容の要約。同じなら描き直さない（利用者のトップと同じ。#279）。
 * 1 分ごとの自動更新で毎回描き直すと、カードや表のリンクにフォーカスしていた人のフォーカスが body へ飛ぶため。
 */
let dashboardVideosKey = "";

async function loadDashboard() {
    const request = ++dashboardRequest;
    // 下の Promise.all に入れると、片方の失敗で KPI やグラフまで更新されなくなるため別に読む
    loadRecordingFailures();
    loadResources();
    try {
        const [data, livePage, upcoming] = await Promise.all([
            apiGet("/api/dashboard"),
            apiGet("/api/videos?liveOnly=true&size=100"),
            apiGet("/api/channels/upcoming"),
        ]);
        if (request !== dashboardRequest) return;
        el("totalChannels").textContent = data.totalChannels;
        el("liveNowCount").textContent = data.liveNowCount;
        setLiveIndicator(data.liveNowCount);
        el("notificationsLast24h").textContent = data.notificationsLast24h;
        renderNotificationFailures(data.notificationFailuresLast24h);

        renderChannelStatusChart(data.totalChannels, data.liveNowCount);
        renderRecordingStatusChart(data.recordingStatus);

        renderDetectionAlert(data.detectionFailingChannels);
        el("uptime").textContent = formatUptime(data.uptimeSeconds);
        const startedAtCell = el("startedAt");
        startedAtCell.innerHTML = datetimeCell(data.serviceStartedAt);
        bindDatetimeCells(startedAtCell);

        const videosKey = JSON.stringify([onlineVideosRenderKey(livePage.content), upcoming]);
        if (videosKey !== dashboardVideosKey) {
            dashboardVideosKey = videosKey;
            renderLiveVideoCards(el("liveVideos"), livePage);
            renderUpcomingStreams(upcoming);
        }
        dashboardLastUpdatedAt = new Date();
        renderRefreshStatus(el("dashboardRefreshStatus"), dashboardLastUpdatedAt, false);
    } catch {
        if (request === dashboardRequest) {
            renderRefreshStatus(el("dashboardRefreshStatus"), dashboardLastUpdatedAt, true);
        }
    }
}

buttonEl("refreshDashboardBtn").addEventListener("click", () => {
    loadDashboard();
    // ファイル容量の表の「録画」と上部のディスク使用量は同じ値なので、必ず一緒に読み直す
    loadServiceStorage();
    loadDiskUsage();
});
startVisibleRefresh(loadDashboard);

buttonEl("checkNowBtn").addEventListener("click", async () => {
    const btn = buttonEl("checkNowBtn");
    const result = el("checkResult");
    // 巡回はチャンネル数ぶん時間がかかるため、終わるまで押せないようにする
    btn.disabled = true;
    result.textContent = "チェック中...";
    try {
        const res = await apiPost("/api/monitor/check", {});
        clearError();
        result.textContent = `${res.checkedChannels}件をチェックしました`;
        loadDashboard();
        // 巡回中に録画が進んでいる可能性があるため、チェック後は使用量も引き直す
        loadServiceStorage();
        loadDiskUsage();
    } catch (e) {
        result.textContent = "";
        showError(errorMessage(e));
    } finally {
        btn.disabled = false;
    }
});

loadDashboard();
loadServiceStorage();
loadDiskUsage();
loadSettings();
