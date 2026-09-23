// @ts-check
// 画面固有の状態をグローバルへ漏らさないため、全体を即時実行関数で包む。
// （各画面のスクリプトは <script> で読み込まれ、既定では同じスコープを共有するため）
(() => {
    let currentPage = 0;
    const pageSize = 20;
    let totalPages = 1;

    async function loadChannelOptions() {
        try {
            const channels = await apiGet("/api/channels");
            const select = selectEl("channelFilter");
            for (const ch of channels) {
                const opt = document.createElement("option");
                opt.value = ch.id;
                opt.textContent = ch.channelName;
                select.appendChild(opt);
            }
        } catch (e) {
            showError(errorMessage(e));
        }
    }

    async function loadHistory() {
        try {
            const channelId = selectEl("channelFilter").value;
            let url = `/api/notifications?page=${currentPage}&size=${pageSize}`;
            if (channelId) url += `&channelId=${channelId}`;
            const data = await apiGet(url);
            clearError();
            totalPages = data.totalPages || 1;

            const tbody = query("#historyTable tbody");
            tbody.innerHTML = "";
            for (const h of data.content) {
                const tr = document.createElement("tr");
                tr.innerHTML = `
                    <td>${datetimeCell(h.notifiedAt)}</td>
                    <td>${channelLink(h.channelName, h.youtubeChannelId)}</td>
                    <td>${collapsibleCell(h.videoTitle)}</td>
                    <td>${videoLink(h.videoId)}</td>
                    <td>${h.status === "SUCCESS" ? "成功" : '<span class="error">失敗</span>'}</td>
                    <td>${collapsibleCell(h.errorMessage)}</td>
                `;
                tbody.appendChild(tr);
            }
            bindDatetimeCells(tbody);
            el("pageInfo").textContent = `${currentPage + 1} / ${totalPages}`;
        } catch (e) {
            showError(errorMessage(e));
        }
    }

    el("filterForm").addEventListener("submit", (ev) => {
        ev.preventDefault();
        currentPage = 0;
        loadHistory();
    });

    el("prevBtn").addEventListener("click", () => {
        if (currentPage > 0) {
            currentPage--;
            loadHistory();
        }
    });

    el("nextBtn").addEventListener("click", () => {
        if (currentPage + 1 < totalPages) {
            currentPage++;
            loadHistory();
        }
    });

    loadChannelOptions();
    loadHistory();
})();
