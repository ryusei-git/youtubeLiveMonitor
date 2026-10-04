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
    // 「監視する」を札に置き換えた後に、同じカードへフォーカスを戻すための目印（rememberFocus）
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
 * 判定した候補のカードの代わりに置く行（取り消しのボタン付き）。
 * 候補は利用者全員で共有していて、「ちがう」にした候補はどの一覧にも出ないため、押し間違えてもここでしか戻せない。
 * 確認ダイアログは 1 件ずつ判定する速さを落とすので出さず、代わりにこの行を画面を離れるまで残す。
 *
 * @param {string} channelId チャンネル ID
 * @param {string} title チャンネル名
 * @param {string} message 判定の結果の文（例：「「ちがう」にしました」）
 * @returns {HTMLElement} 差し込む要素
 */
function myDiscoverUndoRow(channelId, title, message) {
    const row = document.createElement("p");
    row.className = "discoverUndo";
    row.dataset.channelId = channelId;
    row.dataset.title = title;
    row.innerHTML = `<span>${escapeHtml(title)} を${escapeHtml(message)}</span>
        <button type="button" aria-label="${escapeHtml(title)} の判定を取り消す">取り消し</button>`;
    return row;
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
            <p class="discoverStatus muted">巡回の状態を読み込み中...</p>
            <p id="error" class="error" role="alert" style="display:none;"></p>
            <form id="discoverAddForm" class="inline">
              <input type="text" name="url" placeholder="チャンネルの URL・@ハンドル・ID" aria-label="候補に足すチャンネルの URL" required>
              <button type="submit">URL で候補に足す</button>
            </form>
            <nav class="viewToggle discoverTabs" aria-label="候補の状態">
              ${MY_DISCOVER_TABS.map((t) => `<a href="/my/discover${t === MY_DISCOVER_TABS[0] ? "" : `?status=${t.status}`}"
                  ${t === tab ? 'aria-current="page"' : ""}>${t.label}</a>`).join("")}
            </nav>
            <div class="discoverList"><p class="muted discoverPlaceholder">読み込み中...</p></div>
            ${mySearchAttribution}`;
        // 一覧の読み込み中・失敗の表示には discoverPlaceholder を付ける。候補を URL で足したときに消すため（#338）
        const list = query(".discoverList", root);
        const showEmptyIfNone = () => {
            // 取り消しの行（.discoverUndo）は消さずに残し、その後ろに空の表示を足す
            if (!list.querySelector(".discoverCard") && !list.querySelector(".emptyState")) {
                list.insertAdjacentHTML("beforeend", emptyState(tab.empty[0], tab.empty[1]));
            }
        };

        apiGet("/api/my/discover/status").then((s) => {
            if (!list.isConnected) return;
            const lastRun = s.lastRunAt ? `${formatInstant(s.lastRunAt)}（検索 ${s.lastRunSearches} 回）` : "まだ";
            // iPhone の幅では 3 つの値が 1 段落に詰まって区切りが分からないので、行を分ける（mobile.css の .discoverStatus の white-space と対）
            const separator = matchMedia("(max-width: 760px)").matches ? "\n" : "・";
            query(".discoverStatus", root).textContent = [`最後の巡回 ${lastRun}`,
                `次の巡回 ${s.nextRunAt ? formatInstant(s.nextRunAt) : "止まっています"}`,
                `今日の発掘の検索 ${s.discoverySearchesUsedToday}/${s.discoveryLimit} 回`].join(separator);
        }).catch(() => {
            // 状態が出なくても候補は見られるので、エラー帯には出さない。空のままだと読み込み中と区別が付かないので、行に書く
            if (list.isConnected) query(".discoverStatus", root).textContent = "巡回の状態を読み込めませんでした";
        });

        // 札とボタンの出し分けに使う。「監視する」を押したらこの集合にも足し、URL で足した候補にも同じ集合を使う
        const subscribedLoad = mySearchSubscribedChannelIds();
        const loaded = Promise.all([apiGet(`/api/my/discover/candidates?status=${tab.status}`), subscribedLoad]).then(([items, subscribed]) => {
            if (!list.isConnected) return;
            list.replaceChildren(...(/** @type {any[]} */ (items)).map((item) => myDiscoverCard(item, subscribed)));
            showEmptyIfNone();
        }).catch((e) => {
            if (!list.isConnected) return;
            showError(errorMessage(e));
            // 読み込みを待つ間に URL で足した候補があれば、それを残す（読み込み中の表示は足したときに消えている）
            if (!list.querySelector(".discoverCard")) {
                // 足した候補を判定した後の取り消しの行（.discoverUndo）も残す。「ちがう」にした候補はここでしか戻せず、
                // innerHTML で置き換えると取り消しの手段が消え、取り消しのボタンにあったフォーカスも body に落ちる。
                // それ以外（読み込み中の表示と、判定の後に足された「まだ候補はありません」）は、読めなかったのに無いと読めるので消す
                list.querySelectorAll(":scope > :not(.discoverUndo)").forEach((node) => node.remove());
                list.insertAdjacentHTML("beforeend",
                    '<p class="muted discoverPlaceholder">候補を読み込めませんでした。ページを再読み込みすると、もう一度読み込みます。</p>');
            }
        });

        /**
         * 判定を取り消す。一覧のカードはどれもタブの状態（tab.status）なので、その状態へ戻す。
         * apiPut は応答を返さないので、戻したカードの値は一覧の API から取り直す
         * （「ちがう」から戻すと、サーバーが消した値を YouTube から取り直すので、押す前のカードとは値が変わる）。
         *
         * @param {HTMLElement} row 取り消しの行
         * @param {HTMLButtonElement} button 取り消しのボタン
         */
        const undoDecision = async (row, button) => {
            const channelId = row.dataset.channelId || "";
            const title = row.dataset.title || channelId;
            button.disabled = true;
            try {
                await apiPut(`/api/my/discover/candidates/${encodeURIComponent(channelId)}`, { status: tab.status });
                /** @type {any[]} */
                const items = await apiGet(`/api/my/discover/candidates?status=${tab.status}`);
                if (!list.isConnected) return;
                const item = items.find((i) => i.channelId === channelId);
                if (item) {
                    const card = myDiscoverCard(item, await subscribedLoad);
                    row.replaceWith(card);
                    list.querySelector(".emptyState")?.remove();
                    // 取り消しのボタンが消えるので、戻したカードの最初の判定のボタンへフォーカスを移す（body に落とさない）
                    const first = card.querySelector("button[data-status]");
                    if (first instanceof HTMLButtonElement) first.focus();
                } else {
                    // 取り消している間に、ほかの人が判定を変えた。行が消えてフォーカスが body に落ちないよう、今のタブへ移す
                    row.remove();
                    showEmptyIfNone();
                    query(".discoverTabs a[aria-current]", root).focus();
                }
                clearError();
                showToast(`${title} の判定を取り消しました`);
            } catch (e) {
                if (!list.isConnected) return;
                showError(errorMessage(e), { reveal: true });
                button.disabled = false;
            }
        };

        list.addEventListener("click", async (event) => {
            const button = event.target instanceof Element ? event.target.closest("button") : null;
            if (!(button instanceof HTMLButtonElement)) return;
            const undoRow = button.closest(".discoverUndo");
            if (undoRow instanceof HTMLElement) {
                await undoDecision(undoRow, button);
                return;
            }
            const card = button.closest(".discoverCard");
            if (!(card instanceof HTMLElement)) return;
            const channelId = card.dataset.channelId || "";
            const title = query(".discoverTitle", card).textContent || channelId;
            // 押したボタンの場所を disabled にする前に覚える。「監視する」を札に置き換えると、
            // 押したボタンが DOM から外れてフォーカスが body へ落ちるので、同じカードの操作へ戻す
            // （判定のときはカードを取り消しの行に置き換え、フォーカスは取り消しのボタンへ移すので使わない）
            const restoreFocus = rememberFocus(list, query(".discoverTabs a[aria-current]", root));
            button.disabled = true;
            try {
                if (button.dataset.status) {
                    const status = button.dataset.status;
                    await apiPut(`/api/my/discover/candidates/${encodeURIComponent(channelId)}`, { status });
                    if (!list.isConnected) return;
                    /** @type {Record<string, string>} */
                    const done = { VTUBER: " VTuber と判定しました", REJECTED: "「ちがう」にしました", CANDIDATE: "候補に戻しました" };
                    // カードを消さずに取り消しの行へ置き換える（押し間違えをその場で戻せるように）。
                    // 押したボタンが消えるので、フォーカスは取り消しのボタンへ移す（body に落とさない）
                    const row = myDiscoverUndoRow(channelId, title, done[status]);
                    card.replaceWith(row);
                    query("button", row).focus();
                    showEmptyIfNone();
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
                showError(errorMessage(e), { reveal: true });
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
                    // 既にあった候補と、同じチャンネルの取り消しの行は、取り直した値のカードで置き換える
                    // （「ちがう」にした候補を URL で足し直すとサーバーが候補に戻すので、取り消しの行を残すとカードが 2 枚になる）
                    list.querySelectorAll(`[data-channel-id="${CSS.escape(item.channelId)}"]`).forEach((node) => node.remove());
                    list.querySelector(".emptyState, .discoverPlaceholder")?.remove();
                    list.prepend(myDiscoverCard(item, await subscribedLoad));
                }
                showToast(item.status === "VTUBER" ? `${item.title} は VTuber と判定済みです（値を取り直しました）`
                    : `${item.title} を候補に足しました`);
            } catch (e) {
                if (list.isConnected) showError(errorMessage(e), { reveal: true });
            } finally {
                submit.disabled = false;
            }
        });

        // 候補を描き終えたら解決する。「戻る」で戻ったとき、ルーターがこの後でスクロール位置を戻す
        return loaded;
    },
};
