/**
 * マイチャンネル画面。ログイン中の利用者が自分の購読を追加・解除する。
 *
 * <p>管理者画面の channels.js とは<b>別物として作っている</b>。
 * あちらの「削除」はチャンネル本体を消し、全利用者の監視を止めて通知履歴・録画履歴まで
 * 連鎖削除する。こちらの「解除」で消えるのは<b>自分の購読 1 行だけ</b>で、
 * チャンネル本体にも他人の購読にも触れない。同じ画面に見せると取り違えるため分けている。
 *
 * <p>録画の設定はここには出さない。チャンネル単位の設定なので、
 * 利用者が変えると他の利用者にも影響するため（録画の制御は管理者画面のみ）。
 */

/**
 * プラットフォームの選択肢を API から読み込んで並べる。
 *
 * <p>選択肢を画面に決め打ちすると、対応プラットフォームが増えるたびに
 * 画面の修正が要る（ログ画面のレベル絞り込みと同じ考え方）。
 */
async function loadPlatforms() {
    try {
        const options = await apiGet("/api/platforms");
        const select = selectEl("addPlatform");
        select.replaceChildren();
        for (const platform of options) {
            const option = document.createElement("option");
            option.value = platform.name;
            option.textContent = platform.label;
            select.appendChild(option);
        }
    } catch (e) {
        showError(errorMessage(e));
    }
}

/**
 * 購読しているチャンネル 1 件の状態表示を決める。
 *
 * <p>「配信していない」と「判定できなかった」を必ず分ける。
 * まとめてしまうと、検知が壊れていてもアプリは平常運転に見える。
 *
 * @param {any} channel 購読しているチャンネル 1 件
 * @returns {string} セルへ差し込む HTML
 */
function subscribedStateLabel(channel) {
    if (channel.detectionFailing) {
        return statusLamp("failed", "確認できません",
            "配信状態を判定できていません。しばらくしても直らない場合は管理者に連絡してください。");
    }
    if (!channel.lastCheckedAt) {
        return statusLamp("unknown", "未確認", "まだ一度も確認していません");
    }
    return channel.currentlyLive
        ? statusLamp("live", "配信中", "最終確認時点で配信中です")
        : statusLamp("idle", "配信していません");
}

/**
 * 配信中なら視聴ページへのリンクを返す。
 *
 * <p>視聴 URL はプラットフォームごとに組み立て方が違い、動画 ID だけからは作れない
 * （Twitch はログイン名が要る）。そのため検知時に組み立てられたものをそのまま使う。
 *
 * @param {any} channel 購読しているチャンネル 1 件
 * @returns {string} セルへ差し込む HTML
 */
function watchLink(channel) {
    if (!channel.currentlyLive || channel.detectionFailing) return "";
    return ` <a href="/videos.html?channelId=${encodeURIComponent(channel.id)}&liveOnly=true">サービス内で見る</a>`;
}

/** 購読しているチャンネルを読み込んで一覧に並べる。 */
async function loadMyChannels() {
    const table = el("myChannelTable");
    setBusy(table, true);
    try {
        const channels = await apiGet("/api/my/channels");
        const tbody = query("#myChannelTable tbody");
        tbody.replaceChildren();

        // 0 件と読み込み失敗は利用者にとって別の意味なので、はっきり分けて伝える
        const empty = el("myChannelEmpty");
        table.style.display = channels.length === 0 ? "none" : "";
        empty.style.display = channels.length === 0 ? "block" : "none";
        empty.innerHTML = channels.length === 0
            ? emptyState("まだチャンネルを追加していません",
                "上の入力欄にチャンネルのURLを貼ると、配信の開始を見張ります")
            : "";

        let liveCount = 0;
        for (const ch of channels) {
            if (ch.currentlyLive) liveCount++;
            const tr = document.createElement("tr");
            tr.innerHTML = `
                <td>${escapeHtml(ch.platformLabel)}</td>
                <td class="revealable" title="クリックでチャンネルIDを表示">${escapeHtml(ch.channelName)}</td>
                <td>${subscribedStateLabel(ch)}${watchLink(ch)}</td>
                <td><button class="recordBtn" data-id="${ch.id}" data-enabled="${ch.recordEnabled}">${ch.recordEnabled ? "録画する" : "録画しない"}</button></td>
                <td class="titleFilterCell">${titleFilterButton(ch.recordTitleKeywords || "")}</td>
                <td><button data-id="${ch.id}" data-name="${escapeHtml(ch.channelName)}" class="unsubscribeBtn">解除</button></td>
            `;
            // 列を足したときにずれないよう、位置ではなくクラスで対象を選ぶ
            query(".revealable", tr).addEventListener("click",
                (ev) => toggleChannelIdReveal(/** @type {HTMLTableCellElement} */ (ev.currentTarget), ch.youtubeChannelId));
            query(".recordBtn", tr).addEventListener("click", async (ev) => {
                const btn = /** @type {HTMLButtonElement} */ (ev.currentTarget);
                const next = btn.dataset.enabled !== "true";
                btn.disabled = true;
                try {
                    // 保存するのは自分の希望だけ。条件は今の値をそのまま持ち越す
                    await apiPut(`/api/my/channels/${ch.id}/record`,
                        { enabled: next, titleKeywords: ch.recordTitleKeywords || "" });
                    clearError();
                    showToast(next ? "この配信者の録画を始めます" : "この配信者の録画をやめます");
                    loadMyChannels();
                } catch (e) {
                    showError(errorMessage(e));
                    btn.disabled = false;
                }
            });
            query(".titleFilterCell", tr).addEventListener("click", (ev) => {
                // 利用者の保存先は自分の購読。他の人の設定には影響しない
                editTitleFilterCell(
                    /** @type {HTMLTableCellElement} */ (ev.currentTarget),
                    ch.recordTitleKeywords || "",
                    (value) => apiPut(`/api/my/channels/${ch.id}/record`,
                        { enabled: ch.recordEnabled, titleKeywords: value }));
            });
            query(".unsubscribeBtn", tr).addEventListener("click", async (ev) => {
                const btn = /** @type {HTMLButtonElement} */ (ev.currentTarget);
                // 「解除」が何をするのかを明示する。管理者画面の「削除」と取り違えられると困る
                if (!confirm(`${btn.dataset.name} の購読を解除しますか？\n解除されるのはあなたの購読だけで、チャンネルの監視そのものは続きます。`)) return;
                try {
                    await apiDelete(`/api/my/channels/${btn.dataset.id}`);
                    clearError();
                    showToast(`${btn.dataset.name} の購読を解除しました`, "danger");
                    loadMyChannels();
                } catch (e) {
                    showError(errorMessage(e));
                }
            });
            tbody.appendChild(tr);
        }
        setLiveIndicator(liveCount);
    } catch (e) {
        showError(errorMessage(e));
    } finally {
        setBusy(table, false);
    }
}

el("addForm").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const platform = selectEl("addPlatform").value;
    const channelInput = inputEl("addChannelInput").value.trim();
    const channelName = inputEl("addChannelName").value.trim();
    const btn = /** @type {HTMLButtonElement} */ (query("button[type=submit]", el("addForm")));
    btn.disabled = true;
    try {
        const added = await apiPost("/api/my/channels", { platform, channelInput, channelName });
        clearError();
        showToast(`${added.channelName} を追加しました`);
        inputEl("addChannelInput").value = "";
        inputEl("addChannelName").value = "";
        loadMyChannels();
    } catch (e) {
        showError(errorMessage(e));
    } finally {
        btn.disabled = false;
    }
});

loadPlatforms();
loadMyChannels();
