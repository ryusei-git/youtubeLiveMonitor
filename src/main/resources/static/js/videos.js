// @ts-check
let onlinePage = 0;
let onlineTotalPages = 0;
let onlineRequest = 0;
/** @type {Date|null} */
let onlineLastUpdatedAt = null;

function syncVideoUrl(replace = false) {
    const url = new URL(location.href);
    for (const key of ["keyword", "channelId", "liveOnly", "page"]) url.searchParams.delete(key);
    const keyword = inputEl("videoKeyword").value.trim();
    if (keyword) url.searchParams.set("keyword", keyword);
    const channelId = selectEl("videoChannel").value;
    if (channelId) url.searchParams.set("channelId", channelId);
    if (selectEl("videoMode").value === "live") url.searchParams.set("liveOnly", "true");
    if (onlinePage > 0) url.searchParams.set("page", String(onlinePage));
    if (url.href !== location.href) history[replace ? "replaceState" : "pushState"](null, "", url);
}

function restoreVideoUrl() {
    const params = new URLSearchParams(location.search);
    inputEl("videoKeyword").value = (params.get("keyword") || "").trim().slice(0, 200);
    const channel = selectEl("videoChannel");
    const channelId = params.get("channelId") || "";
    channel.value = Array.from(channel.options).some(option => option.value === channelId) ? channelId : "";
    selectEl("videoMode").value = params.get("liveOnly") === "true" ? "live" : "all";
    const page = params.get("page") || "";
    onlinePage = /^\d+$/.test(page) && Number(page) <= 2147483647 ? Number(page) : 0;
    syncVideoUrl(true);
}

/** 自動更新では通知帯の読み上げを繰り返さず、利用者が操作した失敗だけ明示する。
 * @param {boolean} showAlert
 */
async function loadOnlineVideos(showAlert = true) {
    const request = ++onlineRequest;
    const grid = el("onlineVideoGrid");
    setBusy(grid, true);
    const params = new URLSearchParams({page: String(onlinePage), size: "24",
        keyword: inputEl("videoKeyword").value.trim(), liveOnly: String(selectEl("videoMode").value === "live")});
    if (selectEl("videoChannel").value) params.set("channelId", selectEl("videoChannel").value);
    try {
        const data = await apiGet("/api/videos?" + params);
        if (request !== onlineRequest) return;
        if (onlinePage > 0 && onlinePage >= data.totalPages) {
            onlinePage = Math.max(0, data.totalPages - 1);
            syncVideoUrl(true);
            return loadOnlineVideos(showAlert);
        }
        clearError();
        onlineTotalPages = data.totalPages;
        grid.replaceChildren(...data.content.map(buildOnlineVideoCard));
        if (!data.content.length) grid.innerHTML = emptyState("該当する動画はありません", "新着動画の取得後に表示されます。絞り込み条件も確認してください。");
        el("videoSummary").textContent = `${data.totalElements}件`;
        el("videoPage").textContent = data.totalPages ? `${data.number + 1} / ${data.totalPages}` : "0 / 0";
        buttonEl("videoPrev").disabled = data.first || data.empty;
        buttonEl("videoNext").disabled = data.last || data.empty;
        onlineLastUpdatedAt = new Date();
        renderRefreshStatus(el("videoRefreshStatus"), onlineLastUpdatedAt, false);
    } catch (error) {
        if (request === onlineRequest) {
            renderRefreshStatus(el("videoRefreshStatus"), onlineLastUpdatedAt, true);
            if (showAlert) showError(errorMessage(error));
        }
    } finally { if (request === onlineRequest) setBusy(grid, false); }
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
    onlinePage = 0;
    syncVideoUrl();
    loadOnlineVideos();
});
buttonEl("videoPrev").addEventListener("click", () => {
    if (onlinePage > 0) { onlinePage--; syncVideoUrl(); loadOnlineVideos(); }
});
buttonEl("videoNext").addEventListener("click", () => {
    if (onlinePage + 1 < onlineTotalPages) { onlinePage++; syncVideoUrl(); loadOnlineVideos(); }
});
buttonEl("refreshVideosBtn").addEventListener("click", async () => {
    await loadVideoChannels();
    syncVideoUrl(true);
    await loadOnlineVideos();
});
window.addEventListener("popstate", () => { restoreVideoUrl(); loadOnlineVideos(); });
startVisibleRefresh(() => loadOnlineVideos(false));
(async () => { await loadVideoChannels(); restoreVideoUrl(); await loadOnlineVideos(); })();

// 共有画面なので、サーバーが返す権限で共通メニューを選ぶ。
(async () => {
    try {
        const viewer = await apiGet("/api/videos/viewer");
        renderNavigationForViewer(viewer.admin);
    } catch { /* 一覧本体のエラー表示を優先する。 */ }
})();
