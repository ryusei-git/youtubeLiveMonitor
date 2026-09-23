// @ts-check
async function loadChannelOptions() {
    try {
        const channels = await apiGet("/api/logs/channels");
        const select = selectEl("channelSelect");
        for (const id of channels) {
            const opt = document.createElement("option");
            opt.value = id;
            opt.textContent = id;
            select.appendChild(opt);
        }
    } catch (e) {
        showError(errorMessage(e));
    }
}

/**
 * レベルの選択肢を、実際にログへ出力されている値だけで作り直す。
 * 選択中の値がまだ存在すればそのまま残す（表示のたびに「すべて」へ戻ると絞り込みが続けられないため）。
 */
/**
 * ログに実在するレベルだけを選択肢として並べ直す。
 *
 * @param {string[]} availableLevels 絞り込み前の全行から集めたレベル
 */
function refreshLevelOptions(availableLevels) {
    const select = selectEl("levelSelect");
    const selected = select.value;

    select.innerHTML = '<option value="">すべて</option>';
    for (const level of availableLevels) {
        const opt = document.createElement("option");
        opt.value = level;
        opt.textContent = level;
        select.appendChild(opt);
    }
    select.value = availableLevels.includes(selected) ? selected : "";
}

async function loadLogs() {
    try {
        const channel = selectEl("channelSelect").value;
        const level = selectEl("levelSelect").value;
        const limit = inputEl("limitInput").value || 200;

        const basePath = channel === "__system__"
            ? "/api/logs/system"
            : `/api/logs/channels/${encodeURIComponent(channel)}`;
        let url = `${basePath}?limit=${limit}`;
        if (level) url += `&level=${encodeURIComponent(level)}`;

        const data = await apiGet(url);
        clearError();
        refreshLevelOptions(data.availableLevels);

        const tbody = query("#logTable tbody");
        tbody.innerHTML = "";
        for (const entry of data.entries.slice().reverse()) {
            const tr = document.createElement("tr");
            tr.innerHTML = `
                <td>${escapeHtml(entry.timestamp)}</td>
                <td>${escapeHtml(entry.level)}</td>
                <td>${escapeHtml(entry.loggerName)}</td>
                <td>${collapsibleCell(entry.message)}</td>
            `;
            tbody.appendChild(tr);
        }
        el("logCount").textContent = `${data.entries.length}件`;
    } catch (e) {
        showError(errorMessage(e));
    }
}

el("filterForm").addEventListener("submit", (ev) => {
    ev.preventDefault();
    loadLogs();
});

// 対象を切り替えるとレベルの顔ぶれも変わるため、絞り込みは解除してから読み直す
selectEl("channelSelect").addEventListener("change", () => {
    selectEl("levelSelect").value = "";
    loadLogs();
});

selectEl("levelSelect").addEventListener("change", loadLogs);

loadChannelOptions();
loadLogs();
