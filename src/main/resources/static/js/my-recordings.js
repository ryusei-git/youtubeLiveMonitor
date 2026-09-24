/**
 * 利用者向けの録画一覧。自分が購読しているチャンネルの録画だけを表示し、その場で再生する。
 *
 * <p>管理者の録画画面とは別物。あちらは全チャンネルを扱い、削除やURL指定のダウンロードもできる。
 * こちらは<b>見るだけ</b>で、範囲は自分の購読に限られる（絞り込みはサーバー側で行う）。
 *
 * <p>再生に別画面を用意していないのは、<b>同じ再生機能を2つ持ちたくない</b>ため。
 * 一覧の中に {@code <video>} を出せば、HTTP Range 対応を含めてブラウザ任せで済む。
 */

/** 再生を開いた後、閉じたときにフォーカスを戻す先のボタン。 */
/** @type {HTMLButtonElement|null} */
let lastPlayButton = null;

/** 今表示しているページ番号（0 始まり）。 */
let currentPage = 0;

/** 総ページ数。 */
let totalPages = 1;

/** 連続操作で古い検索結果が新しい条件を上書きしないようにする。 */
let latestRequest = 0;

/**
 * 録画をその場で再生する。
 *
 * @param {any} recording 録画1件
 * @param {HTMLButtonElement|null} triggerButton 再生を開始した操作の起点。
 *        閉じたときにここへフォーカスを戻す（フォーカスが失われると、
 *        キーボード操作の利用者がどこにいるか分からなくなる）
 */
function play(recording, triggerButton = null) {
    // 失敗した録画には再生できるファイルが無い。押しても無反応にせず理由を伝える
    if (!PLAYABLE_RECORDING_STATUSES.includes(recording.status)) {
        showToast("この録画は再生できるファイルが残っていません", "danger");
        return;
    }
    lastPlayButton = triggerButton;

    const box = el("playerBox");
    const video = /** @type {HTMLVideoElement} */ (el("player"));
    el("playerTitle").textContent = recording.videoTitle;
    // 保存先のパスをそのまま使う。録画ファイルの配信は静的リソース機構に任せており、
    // 購読しているチャンネルかどうかはサーバー側で判定される
    video.src = `/recordings/${encodeURI(recording.filePath)}`;
    box.style.display = "block";
    box.scrollIntoView({ behavior: "smooth", block: "start" });
    video.play().catch(() => {
        // 自動再生が拒否されることがある。その場合は利用者が再生ボタンを押せばよい
    });
}

/** 再生を止めて player を隠す。 */
function closePlayer() {
    const video = /** @type {HTMLVideoElement} */ (el("player"));
    video.pause();
    // src を空にしないと、閉じた後も裏で読み込みが続く
    video.removeAttribute("src");
    video.load();
    el("playerBox").style.display = "none";
    // 開いた場所へフォーカスを戻す。一覧は再読み込みしていないのでボタンはまだ存在する
    if (lastPlayButton && document.contains(lastPlayButton)) {
        lastPlayButton.focus();
    }
    lastPlayButton = null;
}

/** 録画を読み込んで並べる。 */
async function loadMyRecordings() {
    const request = ++latestRequest;
    const grid = el("videoGrid");
    setBusy(grid, true);
    try {
        const params = new URLSearchParams({page: String(currentPage), size: "12",
            playableOnly: String(inputEl("recordingPlayable").checked)});
        const keyword = inputEl("recordingKeyword").value.trim();
        if (keyword) params.set("keyword", keyword);
        const channelId = selectEl("recordingChannel").value;
        if (channelId) params.set("channelId", channelId);
        const data = await apiGet(`/api/my/recordings?${params}`);
        if (request !== latestRequest) return;
        if (currentPage > 0 && currentPage >= data.totalPages) {
            currentPage = Math.max(0, data.totalPages - 1);
            return loadMyRecordings();
        }
        clearError();
        totalPages = data.totalPages || 1;
        grid.replaceChildren();

        if (data.totalElements === 0) {
            grid.innerHTML = keyword || channelId || inputEl("recordingPlayable").checked
                ? emptyState("該当する録画はありません", "検索条件を変えてお試しください。")
                : emptyState("まだ録画がありません",
                    "マイチャンネルで録画を「する」にすると、条件に合う配信が自動で保存されます");
        }
        for (const recording of data.content) {
            // 削除は利用者にはさせない（保存先は共有で、他の購読者の録画でもあるため）。
            // 再生画面（/player.html）は管理者専用なのでリンクにもしない
            // 再生の起点は再生ボタン（キーボード・スクリーンリーダーで操作できる）。
            // カード全体のクリックはマウス操作の近道として残すが、ボタンや
            // リンクの上でのクリックはそちらの動作を優先する
            const card = buildVideoCard(recording, null, false, play);
            card.addEventListener("click", (ev) => {
                const target = /** @type {HTMLElement} */ (ev.target);
                if (target.closest("a, details, button")) return;
                play(recording);
            });
            grid.appendChild(card);
        }
        bindDatetimeCells(grid);

        el("resultSummary").textContent = `${data.totalElements}件`;
        updatePagination(currentPage, totalPages);
        el("pageInfo").textContent = `${currentPage + 1} / ${totalPages}`;
    } catch (e) {
        if (request === latestRequest) {
            grid.replaceChildren();
            el("resultSummary").textContent = "";
            el("pageInfo").textContent = "";
            buttonEl("prevBtn").disabled = true;
            buttonEl("nextBtn").disabled = true;
            showError(errorMessage(e));
        }
    } finally {
        if (request === latestRequest) setBusy(grid, false);
    }
}

/** チャンネル名は本人の購読 API からだけ取得する。 */
async function loadRecordingChannels() {
    const channels = await apiGet("/api/my/channels");
    const select = selectEl("recordingChannel");
    select.replaceChildren(new Option("すべて", ""));
    for (const channel of channels) select.add(new Option(channel.channelName, String(channel.id)));
}

formEl("recordingFilterForm").addEventListener("submit", event => {
    event.preventDefault();
    currentPage = 0;
    loadMyRecordings();
});

buttonEl("prevBtn").addEventListener("click", () => {
    if (currentPage > 0) { currentPage--; loadMyRecordings(); }
});
buttonEl("nextBtn").addEventListener("click", () => {
    if (currentPage + 1 < totalPages) { currentPage++; loadMyRecordings(); }
});
buttonEl("closePlayer").addEventListener("click", closePlayer);

(async () => {
    try {
        await loadRecordingChannels();
        await loadMyRecordings();
    } catch (error) {
        showError(errorMessage(error));
    }
})();
