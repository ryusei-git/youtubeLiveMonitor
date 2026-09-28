// @ts-check
/*
 * 利用者画面の「新人発掘」（/my/discover）（#490。API は #488）。
 *
 * my-app.js は別の作業が並行して触るため、画面はこのファイルに分け、my-app.js の myRoutes には行を足すだけにしている。
 * jsconfig.json はすべての js を 1 つのスコープで型検査するので、グローバルの名前は myDiscover で始める。
 *
 * 数字は YouTube の値をそのまま出し、独自の点数や伸び率は出さない（YouTube API Services の規約 III.E.4.h。#485）。
 * 発掘の理由は「一致した語」だけで伝える。
 */

/** タブ（URL の ?status=）と、その一覧が空のときの表示。 */
const MY_DISCOVER_TABS = [
    { status: "CANDIDATE", label: "候補",
        empty: ["まだ候補はありません", "1 日 3 回（3 時・11 時・19 時）の巡回で見つかります"] },
    { status: "VTUBER", label: "VTuber と判定したもの",
        empty: ["VTuber と判定したチャンネルはまだありません", "「候補」のタブで「VTuber」を押すと、ここに移ります"] },
];

/**
 * 日時を日付（YYYY-MM-DD、この端末の時刻）にする。チャンネルの開設や最初の投稿は、時刻まで出しても読み取ることがないため。
 *
 * @param {string|null} iso ISO-8601 の日時
 * @returns {string} 日付。無ければ「-」
 */
function myDiscoverDate(iso) {
    const date = iso ? new Date(iso) : null;
    // sv-SE は YYYY-MM-DD で書く
    return date && !Number.isNaN(date.getTime()) ? date.toLocaleDateString("sv-SE") : "-";
}

/**
 * 候補の 1 件のボタン。判定のボタンはタブで変わる（候補なら VTuber／ちがう、VTuber なら候補に戻す）。
 *
 * @param {any} item 候補
 * @param {Set<string>} subscribed 自分が購読している YouTube のチャンネル ID
 * @returns {string} 差し込む HTML
 */
function myDiscoverActions(item, subscribed) {
    const decide = item.status === "VTUBER"
        ? '<button type="button" data-status="CANDIDATE">候補に戻す</button>'
        : `<button type="button" data-status="VTUBER">VTuber</button>
           <button type="button" data-status="REJECTED">ちがう</button>`;
    return `${decide}${mySearchWatchChannel(item, subscribed)}`;
}

/**
 * 候補の 1 件（カード）。
 *
 * @param {any} item 候補
 * @param {Set<string>} subscribed 自分が購読している YouTube のチャンネル ID
 * @returns {HTMLElement} 差し込む要素
 */
function myDiscoverCard(item, subscribed) {
    const subscribers = item.subscriberHidden || item.subscriberCount === null
        ? "登録者 非公開" : `登録者 ${item.subscriberCount.toLocaleString("ja-JP")} 人`;
    const videos = item.videoCount === null ? "" : `動画 ${item.videoCount.toLocaleString("ja-JP")} 本`;
    const words = (/** @type {string[]} */ (item.matchedWords || [])).map((word) => `<span class="statusLamp">${escapeHtml(word)}</span>`).join("");
    const sample = item.sampleVideoId
        ? `<p>見つけた動画：<a href="/my/search/watch/${encodeURIComponent(item.sampleVideoId)}">${escapeHtml(item.sampleVideoTitle || item.sampleVideoId)}</a></p>`
        : "";
    const found = [item.foundByTerm ? `見つけた語「${escapeHtml(item.foundByTerm)}」` : "手動で登録",
        `見つけた日時 ${formatInstant(item.discoveredAt)}`, `${mySearchTimeAgo(item.refreshedAt) || "-"}に取得`];
    const card = document.createElement("article");
    card.className = "discoverCard";
    card.dataset.channelId = item.channelId;
    // 判定でカードを消した後に、隣のカードへフォーカスを移すための目印（rememberFocus）
    card.dataset.focusKey = item.channelId;
    card.innerHTML = `${item.iconUrl ? `<img class="discoverIcon" src="${escapeHtml(item.iconUrl)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : '<span class="discoverIcon"></span>'}
        <div class="discoverInfo">
          <h3 class="discoverTitle">${externalLink(item.title || item.channelId, item.channelUrl)}</h3>
          <p class="discoverStats">${[subscribers, videos, `最初の投稿 ${myDiscoverDate(item.firstUploadAt)}`,
              `チャンネル開設 ${myDiscoverDate(item.channelPublishedAt)}`].filter(Boolean).map((s) => `<span>${s}</span>`).join("")}</p>
          ${words ? `<p class="searchMarks">${words}</p>` : ""}
          ${sample}
          <p class="muted">${found.join("・")}</p>
          <p class="discoverActions">${myDiscoverActions(item, subscribed)}</p>
        </div>`;
    return card;
}

/**
 * 新人発掘の画面。タブ（?status=）は URL に残し、タブのリンクはルーターが拾って画面ごと描き直す。
 * @type {MyView}
 */
const myDiscoverView = {
    title: "新人発掘",
    nav: "/my/discover",
    render(root, _match, params) {
        const tab = MY_DISCOVER_TABS.find((t) => t.status === params.get("status")) || MY_DISCOVER_TABS[0];
        root.innerHTML = `<h1>新人発掘</h1>
            <p class="pageDescription">定期の巡回で見つかった、登録者が少なく投稿が少ない新しいチャンネル。VTuber かどうかを決めて、気になれば監視できます。</p>
            <p class="discoverStatus muted"></p>
            <p id="error" class="error" role="alert" style="display:none;"></p>
            <form id="discoverAddForm" class="inline">
              <input type="text" name="url" placeholder="チャンネルの URL・@ハンドル・ID" aria-label="候補に足すチャンネルの URL" required>
              <button type="submit">URL で候補に足す</button>
            </form>
            <nav class="viewToggle discoverTabs" aria-label="候補の状態">
              ${MY_DISCOVER_TABS.map((t) => `<a href="/my/discover${t === MY_DISCOVER_TABS[0] ? "" : `?status=${t.status}`}"
                  ${t === tab ? 'aria-current="page"' : ""}>${t.label}</a>`).join("")}
            </nav>
            <div class="discoverList"></div>
            ${mySearchAttribution}`;
        const list = query(".discoverList", root);
        const showEmptyIfNone = () => {
            if (!list.querySelector(".discoverCard")) list.innerHTML = emptyState(tab.empty[0], tab.empty[1]);
        };

        apiGet("/api/my/discover/status").then((s) => {
            if (!list.isConnected) return;
            const lastRun = s.lastRunAt ? `${formatInstant(s.lastRunAt)}（検索 ${s.lastRunSearches} 回）` : "まだ";
            query(".discoverStatus", root).textContent = [`最後の巡回 ${lastRun}`,
                `次の巡回 ${s.nextRunAt ? formatInstant(s.nextRunAt) : "止まっています"}`,
                `今日の発掘の検索 ${s.discoverySearchesUsedToday}/${s.discoveryLimit} 回`].join("・");
        }).catch(() => { /* 状態が出なくても候補は見られる */ });

        // 札とボタンの出し分けに使う。「監視する」を押したらこの集合にも足し、URL で足した候補にも同じ集合を使う
        const subscribedLoad = mySearchSubscribedChannelIds();
        setBusy(list, true);
        Promise.all([apiGet(`/api/my/discover/candidates?status=${tab.status}`), subscribedLoad]).then(([items, subscribed]) => {
            if (!list.isConnected) return;
            list.replaceChildren(...(/** @type {any[]} */ (items)).map((item) => myDiscoverCard(item, subscribed)));
            showEmptyIfNone();
        }).catch((e) => {
            if (list.isConnected) showError(errorMessage(e));
        }).finally(() => setBusy(list, false));

        list.addEventListener("click", async (event) => {
            const button = event.target instanceof Element ? event.target.closest("button") : null;
            const card = button?.closest(".discoverCard");
            if (!(button instanceof HTMLButtonElement) || !(card instanceof HTMLElement)) return;
            const channelId = card.dataset.channelId || "";
            const title = query(".discoverTitle", card).textContent || channelId;
            // 押したボタンの場所を disabled にする前に覚える。判定でカードを消す・「監視する」を札に置き換えると、
            // 押したボタンが DOM から外れてフォーカスが body へ落ちるので、隣のカード（無ければ今のタブ）へ戻す
            const restoreFocus = rememberFocus(list, query(".discoverTabs a[aria-current]", root));
            button.disabled = true;
            try {
                if (button.dataset.status) {
                    const status = button.dataset.status;
                    await apiPut(`/api/my/discover/candidates/${encodeURIComponent(channelId)}`, { status });
                    if (!list.isConnected) return;
                    card.remove();
                    showEmptyIfNone();
                    restoreFocus();
                    /** @type {Record<string, string>} */
                    const done = { VTUBER: " VTuber と判定しました", REJECTED: "「ちがう」にしました", CANDIDATE: "候補に戻しました" };
                    showToast(`${title} を${done[status]}`);
                } else {
                    await apiPost("/api/my/channels", { platform: "YOUTUBE", channelInput: channelId, channelName: title });
                    if (!list.isConnected) return;
                    card.querySelector(".serviceWatchLamp")?.remove();
                    button.outerHTML = MY_SEARCH_SUBSCRIBED_LAMP;
                    restoreFocus();
                    (await subscribedLoad).add(channelId);
                    showToast(`${title} をマイチャンネルに登録しました`);
                }
                clearError();
            } catch (e) {
                if (!list.isConnected) return;
                showError(errorMessage(e));
                button.disabled = false;
            }
        });

        const form = formEl("discoverAddForm");
        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            const input = /** @type {HTMLInputElement} */ (query("[name=url]", form));
            const submit = /** @type {HTMLButtonElement} */ (query("button", form));
            submit.disabled = true;
            try {
                const item = await apiPost("/api/my/discover/candidates", { url: input.value.trim() });
                if (!list.isConnected) return;
                clearError();
                input.value = "";
                if (item.status === tab.status) {
                    // 既にあった候補は取り直した値で置き換える
                    list.querySelector(`.discoverCard[data-channel-id="${CSS.escape(item.channelId)}"]`)?.remove();
                    list.querySelector(".emptyState")?.remove();
                    list.prepend(myDiscoverCard(item, await subscribedLoad));
                }
                showToast(item.status === "VTUBER" ? `${item.title} は VTuber と判定済みです（値を取り直しました）`
                    : `${item.title} を候補に足しました`);
            } catch (e) {
                if (list.isConnected) showError(errorMessage(e));
            } finally {
                submit.disabled = false;
            }
        });
    },
};
