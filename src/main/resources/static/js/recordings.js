// @ts-check
// 画面固有の状態をグローバルへ漏らさないため、全体を即時実行関数で包む。
// （各画面のスクリプトは <script> で読み込まれ、既定では同じスコープを共有するため）
(() => {
    /** 一覧の読み込み番号。条件を続けて変えたとき、遅れて届いた古い応答で画面を上書きしないため。 */
    let loadRequest = 0;

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

    async function loadRecordings() {
        const request = ++loadRequest;
        try {
            const data = await apiGet(`/api/recordings?${search.apiParams()}`);
            if (request !== loadRequest) return;
            // ページが範囲を超えていた（URL の page が古いなど）ときは、最後のページに直して読み直す
            if (!search.show(data)) return loadRecordings();
            clearError();
            el("resultSummary").textContent =
                data.totalElements === 0 ? "該当する録画はありません" : `${data.totalElements}件`;
            scheduleRefreshWhileRunning(data.content);
        } catch (e) {
            if (request === loadRequest) showError(errorMessage(e));
        }
    }

    // 条件のフォーム・URL・ページ送り・カード／リストは利用者のアーカイブと共通（common.js）。
    // 状態の絞り込みは、この画面のフォームにだけ置いている
    const search = bindRecordingSearch({
        form: formEl("filterForm"),
        viewToggle: query(".viewToggle"),
        grid: el("videoGrid"),
        list: el("recordingList"),
        pager: el("pager"),
        load: loadRecordings,
        buildCard: (r) => buildVideoCard(r, afterDelete, true, null, toggleMark),
        buildRow: buildRecordingRow,
        empty: emptyState(
            "該当する録画はありません",
            "絞り込み条件を外すか、上の入力欄に動画URLを貼ってダウンロードできます"),
    });

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
            search.goToPage(0);
            loadDiskUsage();
        } catch (e) {
            summary.textContent = "";
            showError(errorMessage(e));
        } finally {
            btn.disabled = false;
        }
    });

    window.addEventListener("popstate", () => {
        search.restore();
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
                } catch (e) {
                    showError(errorMessage(e));
                    container.replaceChildren();
                }
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
        search.restore();
        loadRecordings();
    });
    loadDiskUsage();
})();
