// @ts-check
// 画面固有の状態をグローバルへ漏らさないため、全体を即時実行関数で包む。
// （各画面のスクリプトは <script> で読み込まれ、既定では同じスコープを共有するため）
(() => {
    let currentPage = 0;
    let totalPages = 1;

    /** 一覧の読み込み番号。条件を続けて変えたとき、遅れて届いた古い応答で画面を上書きしないため。 */
    let loadRequest = 0;

    /**
     * URL に残す絞り込み条件と、その入力欄。
     * 既定値（空・新しい順・24件）のときは URL に載せない。載せると条件を付けていないのに URL が長くなり、
     * チャンネル一覧からの `?channelId=` のような短いリンクと見分けにくくなるため。
     */
    const URL_FILTERS = [
        { key: "keyword", id: "keywordFilter" },
        { key: "channelId", id: "channelFilter" },
        { key: "status", id: "statusFilter" },
        { key: "genre", id: "genreFilter" },
        { key: "from", id: "fromFilter" },
        { key: "to", id: "toFilter" },
        { key: "sort", id: "sortFilter" },
        { key: "size", id: "sizeFilter" },
        { key: "watched", id: "watchedFilter" },
        { key: "favorite", id: "favoriteFilter" },
    ];

    /** 今の表示（カード / リスト）。既定のカードのときは URL に載せない。 */
    let currentView = "card";

    /** 今表示している録画。表示を切り替えたとき、一覧を読み直さずに描き直すため。 */
    /** @type {Recording[]} */
    let shownRecordings = [];

    /** 並び順・表示件数の既定値（HTML の先頭の選択肢と揃える）。この値のときは URL に載せない。 */
    const DEFAULT_SORT = "newest";
    const DEFAULT_SIZE = "24";

    /**
     * 今の条件とページを URL に書き出す。
     * 再生画面へ移って「戻る」を押したとき、同じ条件・同じページに戻れるようにするため。
     * ページは画面の表示に合わせて 1 始まりで載せる（API の 0 始まりのままだと、URL を見た人が 1 ずれて読む）。
     *
     * @param {boolean} replace 読み込み直後の整えやページ超過の補正は履歴を増やさない
     */
    function syncRecordingUrl(replace = false) {
        const url = new URL(location.href);
        for (const { key } of URL_FILTERS) url.searchParams.delete(key);
        url.searchParams.delete("page");
        url.searchParams.delete("view");
        for (const { key, id } of URL_FILTERS) {
            const value = inputOrSelectValue(id);
            const isDefault = !value || (key === "sort" && value === DEFAULT_SORT) || (key === "size" && value === DEFAULT_SIZE);
            if (!isDefault) url.searchParams.set(key, value);
        }
        if (currentPage > 0) url.searchParams.set("page", String(currentPage + 1));
        if (currentView === "list") url.searchParams.set("view", "list");
        if (url.href !== location.href) history[replace ? "replaceState" : "pushState"](null, "", url);
    }

    /**
     * URL から条件とページを入力欄へ戻す。
     * select は選択肢に無い値（削除済みチャンネル・無くなったジャンルなど）を入れると空になるため、
     * 無い値は既定に戻す。日付欄も yyyy-MM-dd 以外は空になるので、そのまま入れてよい。
     */
    function restoreRecordingUrl() {
        const params = new URLSearchParams(location.search);
        for (const { key, id } of URL_FILTERS) {
            const value = (params.get(key) || "").trim();
            const field = el(id);
            if (field instanceof HTMLSelectElement) {
                field.value = Array.from(field.options).some(o => o.value === value) ? value : field.options[0].value;
            } else if (inputEl(id).type === "checkbox") {
                inputEl(id).checked = value === "true";
            } else {
                inputEl(id).value = key === "keyword" ? value.slice(0, 200) : value;
            }
        }
        const page = Number.parseInt(params.get("page") || "", 10);
        currentPage = Number.isFinite(page) && page > 1 ? page - 1 : 0;
        currentView = params.get("view") === "list" ? "list" : "card";
        syncRecordingUrl(true);
    }

    /**
     * @param {string} id 入力欄または select の ID
     * @returns {string} 前後の空白を除いた値。チェックボックスは付いていれば "true"、外れていれば空
     */
    function inputOrSelectValue(id) {
        const field = el(id);
        if (field instanceof HTMLSelectElement) return field.value;
        if (inputEl(id).type === "checkbox") return inputEl(id).checked ? "true" : "";
        return inputEl(id).value.trim();
    }

    /**
     * ページ番号のボタンを並べる。数千件（100ページ超）でも行が溢れないよう、
     * 先頭・末尾・今のページの前後 2 つだけを出し、間は「…」で詰める。
     */
    function renderPageNumbers() {
        const container = el("pageNumbers");
        container.replaceChildren();
        const pages = [];
        for (let p = 0; p < totalPages; p++) {
            // 「…」が 1 ページ分だけを隠すことになる場合（例: 1 2 3 … 5）は、そのページを出す
            const onlyHiddenPage = Math.abs(p - currentPage) === 3 && (p === 1 || p === totalPages - 2);
            if (p === 0 || p === totalPages - 1 || Math.abs(p - currentPage) <= 2 || onlyHiddenPage) pages.push(p);
        }
        let previous = -1;
        for (const p of pages) {
            if (p - previous > 1) {
                const gap = document.createElement("span");
                gap.className = "muted";
                gap.textContent = "…";
                container.appendChild(gap);
            }
            const btn = document.createElement("button");
            btn.type = "button";
            btn.textContent = String(p + 1);
            if (p === currentPage) {
                btn.setAttribute("aria-current", "page");
                btn.disabled = true;
            } else {
                btn.addEventListener("click", () => goToPage(p));
            }
            container.appendChild(btn);
            previous = p;
        }
    }

    /** @param {number} page 移動先（0 始まり） */
    function goToPage(page) {
        currentPage = page;
        syncRecordingUrl();
        loadRecordings();
    }

    /**
     * 実行中のもの（録画・ダウンロード）があるときに一覧を読み直す間隔（ミリ秒）。
     * 完了しても画面が「録画中」のままだと終わったのか分からないため、動いている間だけ追う。
     */
    const RUNNING_REFRESH_INTERVAL_MS = 10000;

    /** 読み直しの予約。多重に走らせないよう1本だけ持ち、予約し直すたびに前の予約を捨てる。 */
    let refreshTimer = 0;

    /**
     * 実行中のものが残っていれば、一覧の読み直しを予約する。
     *
     * @param {Recording[]} recordings 今表示している録画
     */
    function scheduleRefreshWhileRunning(recordings) {
        window.clearTimeout(refreshTimer);
        if (!recordings.some((r) => r.status === "RECORDING")) return;
        refreshTimer = window.setTimeout(() => {
            loadRecordings();
            loadDiskUsage();
        }, RUNNING_REFRESH_INTERVAL_MS);
    }

    async function loadDiskUsage() {
        try {
            const data = await apiGet("/api/recordings/disk-usage");
            clearError();
            el("totalDiskUsage").textContent = formatFileSize(data.totalBytes);

            const tbody = query("#diskUsageTable tbody");
            tbody.innerHTML = "";
            for (const c of data.byChannel) {
                const tr = document.createElement("tr");
                // 削除済みは監視対象に戻せないので、目立たせて掃除を促す
                const name = c.registered
                    ? escapeHtml(c.channelName)
                    : `<span class="muted">${escapeHtml(c.channelName)}</span>`;
                tr.innerHTML = `<td>${name}</td><td>${formatFileSize(c.bytes)}</td>`;
                tbody.appendChild(tr);
            }

        } catch (e) {
            showError(errorMessage(e));
        }
    }

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

    /**
     * ジャンルの選択肢を読み込む。件数を添えるのは、選ぶ前にどれだけ当たるか分かるようにするため。
     */
    async function loadGenreOptions() {
        try {
            /** @type {{genre: string, count: number}[]} */
            const genres = await apiGet("/api/recordings/genres");
            const select = selectEl("genreFilter");
            for (const g of genres) {
                const opt = document.createElement("option");
                opt.value = g.genre;
                opt.textContent = `${g.genre}（${g.count}）`;
                select.appendChild(opt);
            }
        } catch (e) {
            showError(errorMessage(e));
        }
    }

    /** 削除後は一覧とディスク使用量の両方を引き直す */
    function afterDelete() {
        loadRecordings();
        loadDiskUsage();
    }

    /**
     * 視聴済み・お気に入りを切り替える。
     * 先に表示を変えてから API を呼ぶ（押した手応えをすぐ返すため）。一覧は読み直さず、
     * 押したボタンだけを更新する。読み直すと、絞り込み中なら押した行が消えて何が起きたか分からなくなるため。
     *
     * @param {Recording} recording 対象の録画
     * @param {RecordingMarkKind} kind 印の種類
     * @param {HTMLButtonElement} button 押されたボタン
     */
    async function toggleMark(recording, kind, button) {
        const next = !recording[kind];
        recording[kind] = next;
        renderRecordingMarkButton(button, kind, next);
        button.disabled = true;
        try {
            await apiPut(`/api/recordings/${recording.id}/${kind}`, { [kind]: next });
            clearError();
        } catch (e) {
            recording[kind] = !next;
            renderRecordingMarkButton(button, kind, !next);
            showError(errorMessage(e));
        } finally {
            button.disabled = false;
        }
    }

    /**
     * 表の 1 行を組み立てる。長さ・サイズ・状態・日時はカードと同じ関数で表し、
     * 表示を切り替えても同じ録画が同じ見た目の値で並ぶようにする。
     *
     * @param {Recording} r 録画 1 件
     * @returns {HTMLTableRowElement} 行
     */
    function buildRecordingRow(r) {
        const tr = document.createElement("tr");
        tr.innerHTML = `
            <td>${datetimeCell(r.startedAt)}</td>
            <td>${channelLink(r.channelName, r.channelUrl)}</td>
            <td><a href="/player.html?id=${r.id}">${escapeHtml(r.videoTitle)}</a></td>
            <td>${formatDuration(r.durationSeconds)}</td>
            <td>${formatFileSize(r.fileSizeBytes)}</td>
            <td>${recordingStatusLabel(r.status)}</td>
            <td>${escapeHtml(r.genre || "-")}</td>
            <td class="watchedCell"></td>
            <td class="favoriteCell"></td>
            <td class="deleteCell"></td>
        `;
        query(".watchedCell", tr).appendChild(recordingMarkButton(r, "watched", toggleMark));
        query(".favoriteCell", tr).appendChild(recordingMarkButton(r, "favorite", toggleMark));
        // カードと同じく、録画中は中断させたくないので削除ボタンを出さない（API 側も 409 で弾く）
        if (r.status !== "RECORDING") {
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "deleteBtn";
            btn.textContent = "削除";
            btn.addEventListener("click", () => deleteRecording(r, afterDelete));
            query(".deleteCell", tr).appendChild(btn);
        }
        return tr;
    }

    /** 今の表示（カード / リスト）で shownRecordings を描く。 */
    function renderRecordings() {
        const list = currentView === "list";
        buttonEl("cardViewBtn").setAttribute("aria-pressed", String(!list));
        buttonEl("listViewBtn").setAttribute("aria-pressed", String(list));

        const grid = el("videoGrid");
        const tbody = query("#recordingTable tbody");
        grid.innerHTML = "";
        tbody.innerHTML = "";
        if (shownRecordings.length === 0) {
            // 空のときは表示によらずカード枠に案内を出す（見出しだけの空の表より理由が伝わる）
            grid.hidden = false;
            el("recordingList").hidden = true;
            grid.innerHTML = emptyState(
                "該当する録画はありません",
                "絞り込み条件を外すか、上の入力欄に動画URLを貼ってダウンロードできます");
            return;
        }
        grid.hidden = list;
        el("recordingList").hidden = !list;
        for (const r of shownRecordings) {
            if (list) tbody.appendChild(buildRecordingRow(r));
            else grid.appendChild(buildVideoCard(r, afterDelete, true, null, toggleMark));
        }
        bindDatetimeCells(list ? tbody : grid);
    }

    /** @param {"card"|"list"} view 切り替え先の表示 */
    function switchView(view) {
        if (currentView === view) return;
        currentView = view;
        syncRecordingUrl();
        renderRecordings();
    }

    async function loadRecordings() {
        const request = ++loadRequest;
        try {
            const params = new URLSearchParams({ page: String(currentPage), size: selectEl("sizeFilter").value });
            for (const { key, id } of URL_FILTERS) {
                const value = inputOrSelectValue(id);
                if (value && key !== "size") params.set(key, value);
            }

            const data = await apiGet(`/api/recordings?${params}`);
            if (request !== loadRequest) return;
            // 件数が減った（URL の page が古い・最後のページの録画を消した）ときは、空のページではなく最後のページを出す
            if (currentPage > 0 && currentPage >= data.totalPages) {
                currentPage = Math.max(0, data.totalPages - 1);
                syncRecordingUrl(true);
                return loadRecordings();
            }
            clearError();
            totalPages = data.totalPages;

            shownRecordings = data.content;
            renderRecordings();
            el("resultSummary").textContent =
                data.totalElements === 0 ? "該当する録画はありません" : `${data.totalElements}件`;
            updatePagination(currentPage, totalPages);
            renderPageNumbers();
            scheduleRefreshWhileRunning(data.content);
        } catch (e) {
            if (request === loadRequest) showError(errorMessage(e));
        }
    }

    el("downloadForm").addEventListener("submit", async (ev) => {
        ev.preventDefault();
        const btn = buttonEl("downloadBtn");
        const summary = el("downloadSummary");
        const url = inputEl("downloadUrl").value.trim();
        if (!url) return;

        // メタデータの取得（yt-dlp の起動）に数秒かかるため、押しっぱなしに見えないよう状態を出す
        btn.disabled = true;
        summary.textContent = "動画情報を確認しています...";
        try {
            const res = await apiPost("/api/downloads", { url });
            clearError();
            inputEl("downloadUrl").value = "";
            const owner = res.channelName ? res.channelName : "未登録チャンネル";
            summary.textContent = `「${res.title}」（${owner}）のダウンロードを開始しました`;
            // 開始直後は一覧の先頭に「録画中」として並ぶ
            goToPage(0);
            loadDiskUsage();
        } catch (e) {
            summary.textContent = "";
            showError(errorMessage(e));
        } finally {
            btn.disabled = false;
        }
    });

    el("filterForm").addEventListener("submit", (ev) => {
        ev.preventDefault();
        goToPage(0);
    });

    el("resetBtn").addEventListener("click", () => {
        for (const { id } of URL_FILTERS) {
            const field = el(id);
            if (field instanceof HTMLSelectElement) field.value = field.options[0].value;
            else if (inputEl(id).type === "checkbox") inputEl(id).checked = false;
            else inputEl(id).value = "";
        }
        goToPage(0);
    });

    el("cardViewBtn").addEventListener("click", () => switchView("card"));
    el("listViewBtn").addEventListener("click", () => switchView("list"));

    el("prevBtn").addEventListener("click", () => {
        if (currentPage > 0) goToPage(currentPage - 1);
    });

    el("nextBtn").addEventListener("click", () => {
        if (currentPage + 1 < totalPages) goToPage(currentPage + 1);
    });

    window.addEventListener("popstate", () => {
        restoreRecordingUrl();
        loadRecordings();
    });

    buttonEl("cleanupOrphanedBtn").addEventListener("click", async () => {
        const btn = buttonEl("cleanupOrphanedBtn");
        const summary = el("orphanedSummary");

        btn.disabled = true;
        summary.textContent = "候補を確認中...";
        try {
            const preview = await apiGet("/api/recordings/orphaned/preview");
            const container = el("cleanupPreview");
            container.innerHTML = `<p>${preview.files.length}ファイル・${formatFileSize(preview.totalBytes)}が対象です。削除すると元に戻せません。</p>`
                + `<ul>${preview.files.map((/** @type {any} */ f) => `<li>${escapeHtml(f.path)}（${formatFileSize(f.bytes)}）</li>`).join("")}</ul>`
                + `<p>除外：${escapeHtml(preview.skipped.join("、") || "なし")}</p>`;
            summary.textContent = "対象を確認してから削除してください";
            if (!preview.files.length) return;
            const confirmButton = document.createElement("button");
            confirmButton.textContent = "表示したファイルを削除";
            confirmButton.className = "deleteBtn";
            container.appendChild(confirmButton);
            confirmButton.addEventListener("click", async () => {
            confirmButton.disabled = true;
            try {
            const res = await apiDelete(`/api/recordings/orphaned/confirmed?token=${encodeURIComponent(preview.token)}`);
            clearError();
            let message = `${res.deletedChannels}チャンネル・${res.deletedFiles}ファイル`
                + `（${formatFileSize(res.freedBytes)}）を削除しました`;
            if (res.skippedChannels.length > 0) {
                message += `／録画中のため${res.skippedChannels.length}件は見送りました`;
            }
            summary.textContent = message;
            container.replaceChildren();
            loadDiskUsage();
            loadRecordings();
            } catch (e) { showError(errorMessage(e)); container.replaceChildren(); }
            });
        } catch (e) {
            summary.textContent = "";
            showError(errorMessage(e));
        } finally {
            btn.disabled = false;
        }
    });

    // 選択肢が揃ってから URL を戻す（先に戻すと、チャンネル一覧から来た ?channelId= やジャンルが選択肢に無いとして捨てられる）
    Promise.all([loadChannelOptions(), loadGenreOptions()]).then(() => {
        restoreRecordingUrl();
        loadRecordings();
    });
    loadDiskUsage();
})();
