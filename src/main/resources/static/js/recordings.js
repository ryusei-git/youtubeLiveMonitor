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
    ];

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
        for (const { key, id } of URL_FILTERS) {
            const value = inputOrSelectValue(id);
            const isDefault = !value || (key === "sort" && value === DEFAULT_SORT) || (key === "size" && value === DEFAULT_SIZE);
            if (!isDefault) url.searchParams.set(key, value);
        }
        if (currentPage > 0) url.searchParams.set("page", String(currentPage + 1));
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
            } else {
                inputEl(id).value = key === "keyword" ? value.slice(0, 200) : value;
            }
        }
        const page = Number.parseInt(params.get("page") || "", 10);
        currentPage = Number.isFinite(page) && page > 1 ? page - 1 : 0;
        syncRecordingUrl(true);
    }

    /**
     * @param {string} id 入力欄または select の ID
     * @returns {string} 前後の空白を除いた値
     */
    function inputOrSelectValue(id) {
        const field = el(id);
        return field instanceof HTMLSelectElement ? field.value : inputEl(id).value.trim();
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
            if (p === 0 || p === totalPages - 1 || Math.abs(p - currentPage) <= 2) pages.push(p);
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

            const grid = el("videoGrid");
            grid.innerHTML = "";
            for (const r of data.content) {
                // 削除後は一覧とディスク使用量の両方を引き直す
                grid.appendChild(buildVideoCard(r, () => {
                    loadRecordings();
                    loadDiskUsage();
                }));
            }
            bindDatetimeCells(grid);

            if (data.totalElements === 0) {
                grid.innerHTML = emptyState(
                    "該当する録画はありません",
                    "絞り込み条件を外すか、上の入力欄に動画URLを貼ってダウンロードできます");
            }
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
            else inputEl(id).value = "";
        }
        goToPage(0);
    });

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
