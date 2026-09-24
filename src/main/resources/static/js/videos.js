// @ts-check
let onlinePage = 0;
let onlineTotalPages = 0;
let onlineRequest = 0;

async function loadOnlineVideos() {
    const request = ++onlineRequest;
    const grid = el("onlineVideoGrid");
    setBusy(grid, true);
    const params = new URLSearchParams({page: String(onlinePage), size: "24",
        keyword: inputEl("videoKeyword").value.trim(), liveOnly: String(selectEl("videoMode").value === "live")});
    if (selectEl("videoChannel").value) params.set("channelId", selectEl("videoChannel").value);
    try {
        const data = await apiGet("/api/videos?" + params);
        if (request !== onlineRequest) return;
        clearError();
        onlineTotalPages = data.totalPages;
        grid.replaceChildren(...data.content.map(buildOnlineVideoCard));
        if (!data.content.length) grid.innerHTML = emptyState("該当する動画はありません", "新着動画の取得後に表示されます。絞り込み条件も確認してください。");
        el("videoSummary").textContent = `${data.totalElements}件`;
        el("videoPage").textContent = data.totalPages ? `${data.number + 1} / ${data.totalPages}` : "0 / 0";
        buttonEl("videoPrev").disabled = data.first || data.empty;
        buttonEl("videoNext").disabled = data.last || data.empty;
    } catch (error) {
        if (request === onlineRequest) showError(errorMessage(error));
    } finally { if (request === onlineRequest) setBusy(grid, false); }
}

async function loadVideoChannels() {
    try {
        const channels = await apiGet("/api/videos/channels");
        const select = selectEl("videoChannel");
        const previous = select.value || queryParam("channelId") || "";
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

formEl("videoFilterForm").addEventListener("submit", event => { event.preventDefault(); onlinePage = 0; loadOnlineVideos(); });
buttonEl("videoPrev").addEventListener("click", () => { if (onlinePage > 0) { onlinePage--; loadOnlineVideos(); } });
buttonEl("videoNext").addEventListener("click", () => { if (onlinePage + 1 < onlineTotalPages) { onlinePage++; loadOnlineVideos(); } });
buttonEl("refreshVideosBtn").addEventListener("click", async () => { await loadVideoChannels(); await loadOnlineVideos(); });
if (queryParam("liveOnly") === "true") selectEl("videoMode").value = "live";
(async () => { await loadVideoChannels(); await loadOnlineVideos(); })();

// 共有画面なので、サーバーが返す権限で共通メニューを選ぶ。
(async () => {
    try {
        const viewer = await apiGet("/api/videos/viewer");
        renderNavigationForViewer(viewer.admin);
    } catch { /* 一覧本体のエラー表示を優先する。 */ }
})();
