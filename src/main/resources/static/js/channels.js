// @ts-check
/**
 * @typedef {object} PlatformOption
 * @property {string} name 登録時に送る名前（YOUTUBE / TWITCH）
 * @property {string} label 画面に出す表示名
 * @property {boolean} available 今すぐ使えるか（認証情報が未設定なら false）
 * @property {string} inputHint 入力欄に出す説明
 */

/** 選択肢はサーバーが返したものだけを使う（画面側に決め打ちしない）。 @type {PlatformOption[]} */
let platformOptions = [];

/**
 * 選択中のプラットフォームに合わせて、入力欄の説明と注意書きを切り替える。
 */
function applySelectedPlatform() {
    const selected = platformOptions.find((p) => p.name === selectEl("addPlatform").value);
    if (!selected) return;

    inputEl("addChannelId").placeholder = selected.inputHint;

    const notice = el("platformNotice");
    if (selected.available) {
        notice.style.display = "none";
        notice.textContent = "";
        return;
    }
    // 設定漏れに気づくのが「登録ボタンを押した後の失敗」だけにならないようにする
    notice.style.display = "";
    notice.textContent = `${selected.label} は認証情報が未設定のため登録できません。`
        + "ダッシュボードの「現在の設定」から Client ID / Client Secret を登録してください。";
}

/**
 * プラットフォームの選択肢を読み込んでプルダウンを作る。
 */
async function loadPlatforms() {
    try {
        platformOptions = await apiGet("/api/platforms");
        const select = selectEl("addPlatform");
        select.innerHTML = "";
        for (const platform of platformOptions) {
            const option = document.createElement("option");
            option.value = platform.name;
            option.textContent = platform.available ? platform.label : `${platform.label}（未設定）`;
            select.appendChild(option);
        }
        applySelectedPlatform();
    } catch (e) {
        showError(errorMessage(e));
    }
}


/**
 * 状態欄の表示を決める。
 * 判定に失敗し続けている場合は「休止中」と出してはいけない（配信していないのか、
 * 検知が壊れているのか区別がつかなくなるため）。失敗していることを明示する。
 */
/**
 * チャンネルの状態を画面表示用の文字列にする。
 *
 * @param {any} channel チャンネル1件
 * @returns {string} 表示用の HTML
 */
function channelStateLabel(channel) {
    if (channel.consecutiveDetectionFailures > 0) {
        return statusLamp("failed", "判定失敗",
            `配信状態を判定できていません（連続${channel.consecutiveDetectionFailures}回）。ログを確認してください。`);
    }
    if (!channel.lastDetectionSuccessAt) {
        return statusLamp("unknown", "未チェック", "まだ一度も巡回していません");
    }
    return channel.currentlyLive
        ? statusLamp("live", "配信中", "最終観測時点で配信中です") + ` <a href="/videos.html?channelId=${encodeURIComponent(channel.id)}&liveOnly=true">サービス内で見る</a>`
        : statusLamp("idle", "未配信");
}

async function loadChannels() {
    // 中身は消さずに薄くする。消すと一瞬空になり「0件になった」と誤解させるため
    const table = document.getElementById("channelTable");
    setBusy(table, true);
    try {
        const channels = await apiGet("/api/channels");
        const tbody = query("#channelTable tbody");
        tbody.innerHTML = "";
        for (const [index, ch] of channels.entries()) {
            const tr = document.createElement("tr");
            tr.innerHTML = `
                <td data-sort-value="${index + 1}">${index + 1}</td>
                <td title="チャンネルID: ${escapeHtml(ch.youtubeChannelId)}">${externalLink(ch.platformLabel, ch.channelUrl)}</td>
                <td><a href="/recordings.html?channelId=${ch.id}">${escapeHtml(ch.channelName)}</a></td>
                <td>${channelStateLabel(ch)}</td>
                <td data-sort-value="${ch.recordEnabled ? "1" : "0"}"><button class="recordBtn" data-id="${ch.id}" data-enabled="${ch.recordEnabled}">${ch.recordEnabled ? "自動録画：有効" : "自動録画：無効"}</button></td>
                <td data-sort-value="${ch.recordingCount}">${ch.recordingCount}件</td>
                <td data-sort-value="${ch.subscriberCount}">${ch.subscriberNames.length > 0 ? ch.subscriberNames.map(escapeHtml).join("、") : "なし"}</td>
                <td class="titleFilterCell" data-sort-value="${escapeHtml(ch.recordTitleKeywords || "")}">${titleFilterButton(ch.recordTitleKeywords || "")}</td>
                <td><button class="removeBtn">削除</button></td>
            `;
            // 列を足したときにずれないよう、位置ではなくクラスで対象を選ぶ
            query(".titleFilterCell", tr).addEventListener("click", (ev) => {
                // 管理者の保存先はチャンネル単位の設定（全利用者に効く）
                editTitleFilterCell(
                    /** @type {HTMLTableCellElement} */ (ev.currentTarget),
                    ch.recordTitleKeywords || "",
                    (value) => apiPut(`/api/channels/${ch.id}/record-title-filter`, { titleKeywords: value }));
            });
            query(".removeBtn", tr).addEventListener("click", async () => {
                // 同じ名前が配信元違いで並ぶため、名前と配信元を出して押し間違いに気付けるようにする。
                // 購読も連鎖で消える取り消せない操作なので、巻き込む人数も示す
                let message = `「${ch.channelName}」（${ch.platformLabel}）を監視対象から削除しますか？\n`
                    + "通知履歴・録画の記録も一緒に削除されます。\n";
                if (ch.subscriberCount > 0) {
                    message += `${ch.subscriberCount} 人が購読しています。その人たちの購読・視聴済み・お気に入りも消えます。\n`;
                }
                // 削除は DB の行とログだけで録画ファイルは消さないため、消し方を案内する
                message += "録画ファイルは残ります（アーカイブ一覧の「孤立した録画ファイルを一括削除」で消せます）。";
                if (!confirm(message)) return;
                try {
                    await apiDelete(`/api/channels/${ch.id}`);
                    showToast("監視対象から削除しました", "danger");
                    clearError();
                    loadChannels();
                } catch (e) {
                    showError(errorMessage(e));
                }
            });
            tbody.appendChild(tr);
        }
        // 操作のたびに読み直すため、利用者が選んだ並び順をここで掛け直す
        applyTableSort(/** @type {HTMLTableElement} */ (table));
        for (const btn of /** @type {NodeListOf<HTMLElement>} */ (document.querySelectorAll(".recordBtn"))) {
            btn.addEventListener("click", async () => {
                const nextEnabled = btn.dataset.enabled !== "true";
                try {
                    await apiPut(`/api/channels/${btn.dataset.id}/record`, { enabled: nextEnabled });
                    showToast(nextEnabled ? "自動録画を有効にしました" : "自動録画を無効にしました");
                    clearError();
                    loadChannels();
                } catch (e) {
                    showError(errorMessage(e));
                }
            });
        }
    } catch (e) {
        showError(errorMessage(e));
    } finally {
        setBusy(table, false);
    }
}

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
        loadChannels();
    } catch (e) {
        result.textContent = "";
        showError(errorMessage(e));
    } finally {
        btn.disabled = false;
    }
});

selectEl("addPlatform").addEventListener("change", applySelectedPlatform);

el("addForm").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const platform = selectEl("addPlatform").value;
    const youtubeChannelId = inputEl("addChannelId").value.trim();
    const channelName = inputEl("addChannelName").value.trim();
    const recordEnabled = inputEl("addRecordEnabled").checked;
    const recordTitleKeywords = inputEl("addTitleKeywords").value.trim();
    try {
        await apiPost("/api/channels", { platform, youtubeChannelId, channelName, recordEnabled, recordTitleKeywords });
        showToast(`${channelName || youtubeChannelId} を監視対象に追加しました`);
        clearError();
        formEl("addForm").reset();
        // reset() はプルダウンも既定値に戻すため、入力欄の説明も合わせ直す
        applySelectedPlatform();
        loadChannels();
    } catch (e) {
        showError(errorMessage(e));
    }
});

el("searchForm").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const name = inputEl("searchName").value.trim();
    const table = el("searchTable");
    try {
        const results = await apiGet(`/api/channels/search?name=${encodeURIComponent(name)}`);
        clearError();
        const tbody = query("tbody", table);
        tbody.innerHTML = "";
        if (results.length === 0) {
            table.style.display = "none";
            showError("該当するチャンネルが見つかりませんでした");
            return;
        }
        table.style.display = "";
        for (const r of results) {
            const tr = document.createElement("tr");
            tr.innerHTML = `
                <td>${escapeHtml(r.youtubeChannelId)}</td>
                <td>${escapeHtml(r.channelTitle)}</td>
                <td><button class="registerBtn">登録</button></td>
            `;
            query(".registerBtn", tr).addEventListener("click", async () => {
                try {
                    // この検索は YouTube 専用なので、選択中のプラットフォームに関わらず YouTube で登録する
                    await apiPost("/api/channels", {
                        platform: "YOUTUBE", youtubeChannelId: r.youtubeChannelId, channelName: r.channelTitle });
                    clearError();
                    loadChannels();
                } catch (e) {
                    showError(errorMessage(e));
                }
            });
            tbody.appendChild(tr);
        }
    } catch (e) {
        showError(errorMessage(e));
    }
});

makeTableSortable(/** @type {HTMLTableElement} */ (document.getElementById("channelTable")));
loadPlatforms();
loadChannels();
