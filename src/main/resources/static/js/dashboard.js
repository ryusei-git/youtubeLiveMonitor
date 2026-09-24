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
 * 開始予定を日付・曜日・時間の列に分ける。列ごとに並べ替えや目視での比較をしやすくするため。
 * @param {string|null} iso 開始予定時刻
 * @returns {{date: string, weekday: string, time: string}} 表示用の文字列。不明なら全て "-"
 */
function splitScheduledStart(iso) {
    if (!iso) return { date: "-", weekday: "-", time: "-" };
    const start = new Date(iso);
    return {
        date: `${start.getMonth() + 1}/${start.getDate()}`,
        weekday: "日月火水木金土"[start.getDay()],
        time: `${String(start.getHours()).padStart(2, "0")}:${String(start.getMinutes()).padStart(2, "0")}`,
    };
}

/**
 * 配信予定を開始時刻の近さで読み取れる一覧にする。
 *
 * <p>配信中の一覧と分けることで、待機所を配信開始と誤解せず、利用者が次の予定を把握できる。
 *
 * @param {Array<{channelName: string, title: string|null, scheduledStartTime: string|null, watchUrl: string, genre?: string|null, channelIconUrl?: string|null}>} streams 開始予定の早い順で返された配信予定
 */
function renderUpcomingStreams(streams) {
    const box = el("upcomingStreams");
    if (!streams || streams.length === 0) {
        box.innerHTML = emptyState("配信予定はありません",
            "監視中のチャンネルが YouTube で待機所を作ると、ここに開始予定の早い順で並びます。");
        return;
    }
    const rows = streams.map(s => {
        const start = splitScheduledStart(s.scheduledStartTime);
        // 隣にチャンネル名があるため alt は空にし、読み上げで名前が 2 回読まれないようにする
        const icon = s.channelIconUrl
            ? `<img class="channelIcon" src="${escapeHtml(s.channelIconUrl)}" alt="" width="24" height="24" loading="lazy" referrerpolicy="no-referrer">`
            : "";
        return `
        <tr>
            <td>${escapeHtml(start.date)}</td>
            <td>${escapeHtml(start.weekday)}</td>
            <td>${escapeHtml(start.time)}</td>
            <td><span class="channelWithIcon">${icon}${escapeHtml(s.channelName)}</span></td>
            <td>${escapeHtml(s.genre || "未設定")}</td>
            <td>${externalLink(s.title ?? "（タイトル不明）", s.watchUrl)}</td>
        </tr>`;
    }).join("");
    box.innerHTML = `
        <div class="table-scroll">
            <table id="upcomingTable">
                <thead><tr><th>日付</th><th>曜日</th><th>時間</th><th>チャンネル名</th><th>ジャンル</th><th>タイトル</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
        </div>`;
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
 * 現在の設定値をフォームへ反映する。
 *
 * <p>秘密情報（APIキー・Webhook URL）の実際の値はサーバーから返ってこない
 * （設定されているかどうかのみ。{@code SettingsResponse}のJavaDoc参照）ため、
 * 欄は空のままにし、プレースホルダーで設定有無だけを示す。
 */
async function loadSettings() {
    try {
        const s = await apiGet("/api/settings");
        ensureOptionPresent(selectEl("settingIntervalSelect"), s.intervalSeconds,
            (value) => `${value}秒（現在の値）`);
        ensureOptionPresent(selectEl("settingMaxHeightSelect"), s.recordingMaxHeight,
            (value) => value > 0 ? `${value}px（現在の値）` : "上限なし（最高画質）");
        inputEl("settingRecordingDirInput").value = s.recordingDirectory;
        inputEl("settingApiKeyInput").placeholder =
            s.youTubeApiKeyConfigured ? "変更する場合のみ入力（設定済み）" : "未設定";
        inputEl("settingWebhookInput").placeholder =
            s.discordWebhookConfigured ? "変更する場合のみ入力（設定済み）" : "未設定";
        // Client ID と Secret は片方だけでは認証できないため、まとめて1つの状態として扱う
        const twitchPlaceholder = s.twitchConfigured ? "変更する場合のみ入力（設定済み）" : "未設定";
        inputEl("settingTwitchClientIdInput").placeholder = twitchPlaceholder;
        inputEl("settingTwitchClientSecretInput").placeholder = twitchPlaceholder;
    } catch (e) {
        showError(errorMessage(e));
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
        showError(errorMessage(e));
    } finally {
        btn.disabled = false;
    }
});

formEl("settingsForm").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const submitBtn = /** @type {HTMLButtonElement} */ (query("button[type=submit]", formEl("settingsForm")));
    const result = el("settingsSaveResult");
    const apiKeyInput = inputEl("settingApiKeyInput");
    const webhookInput = inputEl("settingWebhookInput");
    const twitchClientIdInput = inputEl("settingTwitchClientIdInput");
    const twitchClientSecretInput = inputEl("settingTwitchClientSecretInput");

    submitBtn.disabled = true;
    result.textContent = "保存中...";
    try {
        await apiPut("/api/settings", {
            youtubeApiKey: apiKeyInput.value || null,
            discordWebhookUrl: webhookInput.value || null,
            twitchClientId: twitchClientIdInput.value || null,
            twitchClientSecret: twitchClientSecretInput.value || null,
            intervalSeconds: Number(selectEl("settingIntervalSelect").value),
            recordingDirectory: inputEl("settingRecordingDirInput").value || null,
            recordingMaxHeight: Number(selectEl("settingMaxHeightSelect").value),
        });
        clearError();
        // 秘密情報は保存後に画面へ残さない（次に空欄のまま保存しても「変更しない」扱いになる）
        apiKeyInput.value = "";
        webhookInput.value = "";
        twitchClientIdInput.value = "";
        twitchClientSecretInput.value = "";
        result.textContent = "保存しました。反映するには bin/service.sh restart が必要です。";
    } catch (e) {
        result.textContent = "";
        showError(errorMessage(e));
    } finally {
        submitBtn.disabled = false;
    }
});

let dashboardRequest = 0;
/** @type {Date|null} */
let dashboardLastUpdatedAt = null;

async function loadDashboard() {
    const request = ++dashboardRequest;
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
        const failures = el("notificationFailures");
        const failureCount = data.notificationFailuresLast24h;
        failures.textContent = failureCount > 0 ? `うち送信失敗 ${failureCount}件` : "送信失敗なし";
        // 0件なら平常なので目立たせない。1件でもあれば赤字にして気づけるようにする
        failures.className = failureCount > 0 ? "kpiSub alert" : "kpiSub";

        renderChannelStatusChart(data.totalChannels, data.liveNowCount);
        renderRecordingStatusChart(data.recordingStatus);

        renderDetectionAlert(data.detectionFailingChannels);
        el("uptime").textContent = formatUptime(data.uptimeSeconds);
        const startedAtCell = el("startedAt");
        startedAtCell.innerHTML = datetimeCell(data.serviceStartedAt);
        bindDatetimeCells(startedAtCell);

        renderLiveVideoCards(el("liveVideos"), livePage);
        renderUpcomingStreams(upcoming);
        dashboardLastUpdatedAt = new Date();
        renderRefreshStatus(el("dashboardRefreshStatus"), dashboardLastUpdatedAt, false);
    } catch {
        if (request === dashboardRequest) {
            renderRefreshStatus(el("dashboardRefreshStatus"), dashboardLastUpdatedAt, true);
        }
    }
}

buttonEl("refreshDashboardBtn").addEventListener("click", loadDashboard);
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
        loadDiskUsage();
    } catch (e) {
        result.textContent = "";
        showError(errorMessage(e));
    } finally {
        btn.disabled = false;
    }
});

loadDashboard();
loadDiskUsage();
loadSettings();
