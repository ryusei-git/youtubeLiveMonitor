// @ts-check
/**
 * 段ごとにページ送りを独立させるため、ページ・総ページ数・リクエスト番号を段ごとに持つ。
 * @typedef {{name: string, page: number, totalPages: number, request: number, empty: string}} VideoSection
 */
/** @type {VideoSection[]} */
const videoSections = [
    {name: "now", page: 0, totalPages: 0, request: 0, empty: "配信中・配信予定の動画はありません"},
    {name: "streams", page: 0, totalPages: 0, request: 0, empty: "配信済みの動画はありません"},
    {name: "uploads", page: 0, totalPages: 0, request: 0, empty: "投稿済みの動画はありません"},
];
/** @type {Date|null} */
let onlineLastUpdatedAt = null;

// 段ごとのページは URL に残さない。3 段分を持たせると戻る操作の単位が分かりにくくなるため、絞り込み条件だけを残す。
function syncVideoUrl(replace = false) {
    const url = new URL(location.href);
    for (const key of ["keyword", "channelId", "liveOnly", "page"]) url.searchParams.delete(key);
    const keyword = inputEl("videoKeyword").value.trim();
    if (keyword) url.searchParams.set("keyword", keyword);
    const channelId = selectEl("videoChannel").value;
    if (channelId) url.searchParams.set("channelId", channelId);
    if (url.href !== location.href) history[replace ? "replaceState" : "pushState"](null, "", url);
}

function restoreVideoUrl() {
    const params = new URLSearchParams(location.search);
    inputEl("videoKeyword").value = (params.get("keyword") || "").trim().slice(0, 200);
    const channel = selectEl("videoChannel");
    const channelId = params.get("channelId") || "";
    channel.value = Array.from(channel.options).some(option => option.value === channelId) ? channelId : "";
    syncVideoUrl(true);
}

/** 自動更新では通知帯の読み上げを繰り返さず、利用者が操作した失敗だけ明示する。
 * @param {VideoSection} section
 * @param {boolean} showAlert
 */
async function loadOnlineVideos(section, showAlert = true) {
    const request = ++section.request;
    const grid = el(section.name + "Grid");
    setBusy(grid, true);
    const params = new URLSearchParams({section: section.name, page: String(section.page), size: "12",
        keyword: inputEl("videoKeyword").value.trim()});
    if (selectEl("videoChannel").value) params.set("channelId", selectEl("videoChannel").value);
    try {
        const data = await apiGet("/api/videos?" + params);
        if (request !== section.request) return;
        if (section.page > 0 && section.page >= data.totalPages) {
            section.page = Math.max(0, data.totalPages - 1);
            return loadOnlineVideos(section, showAlert);
        }
        section.totalPages = data.totalPages;
        grid.replaceChildren(...data.content.map(buildOnlineVideoCard));
        if (!data.content.length) grid.innerHTML = emptyState(section.empty, "新着動画の取得後に表示されます。絞り込み条件も確認してください。");
        el(section.name + "Summary").textContent = `${data.totalElements}件`;
        el(section.name + "Page").textContent = data.totalPages ? `${data.number + 1} / ${data.totalPages}` : "0 / 0";
        buttonEl(section.name + "Prev").disabled = data.first || data.empty;
        buttonEl(section.name + "Next").disabled = data.last || data.empty;
        return true;
    } catch (error) {
        if (request === section.request && showAlert) showError(errorMessage(error));
        return false;
    } finally { if (request === section.request) setBusy(grid, false); }
}

/** 3 段をまとめて読み直し、表示更新の時刻は全段が揃って成功したときだけ進める。
 * @param {boolean} resetPage 絞り込みを変えたときは各段を先頭に戻す。自動更新では今のページを保つ
 * @param {boolean} showAlert
 */
async function loadAllSections(resetPage, showAlert = true) {
    if (resetPage) for (const section of videoSections) section.page = 0;
    if (showAlert) clearError();
    const results = await Promise.all(videoSections.map(section => loadOnlineVideos(section, showAlert)));
    // 新しい読み直しに追い越された段は undefined を返す。その回の結果で表示時刻を決めない。
    if (results.includes(undefined)) return;
    const failed = results.includes(false);
    if (!failed) onlineLastUpdatedAt = new Date();
    renderRefreshStatus(el("videoRefreshStatus"), onlineLastUpdatedAt, failed);
}

async function loadVideoChannels() {
    try {
        const channels = await apiGet("/api/videos/channels");
        const select = selectEl("videoChannel");
        const previous = select.value;
        select.replaceChildren(new Option("すべて", ""));
        for (const channel of channels) select.add(new Option(channel.name, String(channel.id)));
        select.value = previous;
        const failed = channels.filter(/** @param {any} c */ c => c.error);
        const waiting = channels.filter(/** @param {any} c */ c => !c.checkedAt);
        el("collectionNotice").textContent = !channels.length ? "チャンネルを登録・購読すると新着動画を取得します。" : failed.length
            ? `新着動画を取得できないチャンネル：${failed.map(/** @param {any} c */ c => c.name).join("、")}。自動再試行します。`
            : waiting.length ? "新着動画の初回取得を待っています。配信中の動画は監視で確認できたものから表示します。"
            : `前回の新着動画取得：${formatInstant(channels.map(/** @param {any} c */ c => c.checkedAt).sort()[0])}。約10分間隔で確認します。`;
    } catch (error) { el("collectionNotice").textContent = "動画の取得状況を確認できません。表示を更新して再試行してください。"; }
}

formEl("videoFilterForm").addEventListener("submit", event => {
    event.preventDefault();
    syncVideoUrl();
    loadAllSections(true);
});
for (const section of videoSections) {
    buttonEl(section.name + "Prev").addEventListener("click", () => {
        if (section.page > 0) { section.page--; clearError(); loadOnlineVideos(section); }
    });
    buttonEl(section.name + "Next").addEventListener("click", () => {
        if (section.page + 1 < section.totalPages) { section.page++; clearError(); loadOnlineVideos(section); }
    });
}
buttonEl("refreshVideosBtn").addEventListener("click", async () => {
    await loadVideoChannels();
    syncVideoUrl(true);
    await loadAllSections(true);
});
window.addEventListener("popstate", () => { restoreVideoUrl(); loadAllSections(true); });
startVisibleRefresh(() => loadAllSections(false, false));
(async () => { await loadVideoChannels(); restoreVideoUrl(); await loadAllSections(true); })();

// 共有画面なので、サーバーが返す権限で共通メニューを選ぶ。
(async () => {
    try {
        const viewer = await apiGet("/api/videos/viewer");
        renderNavigationForViewer(viewer.admin);
    } catch {
        // 判定できなかったときは、権限の少ない利用者用のメニューを出す。一覧本体のエラー表示を優先する。
        renderNavigationForViewer(false);
    }
})();
