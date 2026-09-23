// @ts-check
// 画面固有の状態をグローバルへ漏らさないため、全体を即時実行関数で包む。
// （各画面のスクリプトは <script> で読み込まれ、既定では同じスコープを共有するため）
(() => {
    let currentPage = 0;
    const pageSize = 24;
    let totalPages = 1;

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

    async function loadRecordings() {
        try {
            const params = new URLSearchParams({ page: String(currentPage), size: String(pageSize) });
            const channelId = selectEl("channelFilter").value;
            const keyword = inputEl("keywordFilter").value.trim();
            const status = selectEl("statusFilter").value;
            if (channelId) params.set("channelId", channelId);
            if (keyword) params.set("keyword", keyword);
            if (status) params.set("status", status);

            const data = await apiGet(`/api/recordings?${params}`);
            clearError();
            totalPages = data.totalPages || 1;

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
            el("pageInfo").textContent = `${currentPage + 1} / ${totalPages}`;
            scheduleRefreshWhileRunning(data.content);
        } catch (e) {
            showError(errorMessage(e));
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
            currentPage = 0;
            loadRecordings();
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
        currentPage = 0;
        loadRecordings();
    });

    el("resetBtn").addEventListener("click", () => {
        inputEl("keywordFilter").value = "";
        selectEl("channelFilter").value = "";
        selectEl("statusFilter").value = "";
        currentPage = 0;
        loadRecordings();
    });

    el("prevBtn").addEventListener("click", () => {
        if (currentPage > 0) {
            currentPage--;
            loadRecordings();
        }
    });

    el("nextBtn").addEventListener("click", () => {
        if (currentPage + 1 < totalPages) {
            currentPage++;
            loadRecordings();
        }
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

    loadChannelOptions();
    loadRecordings();
    loadDiskUsage();
})();
