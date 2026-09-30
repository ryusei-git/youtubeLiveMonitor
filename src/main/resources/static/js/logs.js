// @ts-check
/**
 * 監視対象のチャンネル ID から、選択肢に出す名前（「チャンネル名（配信元）」）を引く対応表を作る。
 *
 * ログのファイル名はチャンネル ID（YouTube の UC… や Twitch の数字の ID）なので、そのまま並べると
 * どのチャンネルか見分けられない。名前が取れなくてもログは見られるよう、失敗しても例外にせず null を返す。
 * 空の対応表を返さないのは、監視中のチャンネルまで全部「監視対象外」と表示されてしまうため。
 *
 * @returns {Promise<Map<string, string> | null>} チャンネル ID → 表示名。取得に失敗したら null
 */
async function loadChannelLabels() {
    try {
        /** @type {{youtubeChannelId: string, channelName: string, platformLabel: string}[]} */
        const channels = await apiGet("/api/channels");
        /** @type {Map<string, string>} */
        const labels = new Map();
        for (const ch of channels) {
            labels.set(ch.youtubeChannelId, `${ch.channelName}（${ch.platformLabel}）`);
        }
        return labels;
    } catch {
        // 名前が引けなくても、ID のままで選べるようにする
        return null;
    }
}

/**
 * ログがある対象を、チャンネル名の順で選択肢に並べる。
 *
 * 監視していないチャンネルのログ（監視対象でない動画の手動ダウンロードで作られる）は名前を引けないので、
 * ID に「（監視対象外）」を添えて出す。名前の一覧そのものが取れなかったときは ID だけを出す。
 */
async function loadChannelOptions() {
    try {
        const labelsPromise = loadChannelLabels();
        /** @type {string[]} */
        const channelIds = await apiGet("/api/logs/channels");
        const labels = await labelsPromise;

        const options = channelIds.map((id) => ({
            id,
            label: labels === null ? id : (labels.get(id) ?? `${id}（監視対象外）`),
        }));
        options.sort((a, b) => a.label.localeCompare(b.label, "ja"));

        const select = selectEl("channelSelect");
        for (const { id, label } of options) {
            const opt = document.createElement("option");
            opt.value = id;
            opt.textContent = label;
            opt.title = `チャンネルID: ${id}`;
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
            // 本文（内容）を出力元より前に置き、スマホ幅でも最初の表示に入れる。出力元は完全修飾の
            // ロガー名で長く、前にあると本文を入れ物の外へ押し出す（セルは折り返さないため）
            tr.innerHTML = `
                <td>${escapeHtml(entry.timestamp)}</td>
                <td>${escapeHtml(entry.level)}</td>
                <td>${collapsibleCell(entry.message)}</td>
                <td>${escapeHtml(entry.loggerName)}</td>
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
