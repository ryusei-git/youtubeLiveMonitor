// @ts-check
/** 再生対象の録画。詳細表示・関連一覧・削除のすべてがこれを起点にする。 */
/** @type {Recording|null} */
let recording = null;

const recordingId = queryParam("id");

/**
 * 詳細表の 1 行を組み立てる。
 *
 * @param {string} label 項目名
 * @param {string} valueHtml 値（HTML として差し込むので、呼び出し側でエスケープ済みにする）
 * @returns {string} 行の HTML
 */
function detailRow(label, valueHtml) {
    return `<tr><th>${label}</th><td>${valueHtml}</td></tr>`;
}

/**
 * 録画の詳細と再生対象を画面へ反映する。
 *
 * @param {Recording} rec 表示する録画
 */
function renderDetail(rec) {
    document.title = `${rec.videoTitle} - YouTube Live Monitor`;
    el("title").textContent = rec.videoTitle;

    const player = /** @type {HTMLVideoElement} */ (el("player"));
    // ファイル名に日本語や記号が入るため、パスとして安全な形に符号化する
    player.src = `/recordings/${encodeURI(rec.filePath)}`;
    bindMediaSession(player, {
        title: rec.videoTitle,
        artist: rec.channelName,
        artworkUrl: rec.thumbnailPath ? `/recordings/${encodeURI(rec.thumbnailPath)}` : null,
    });
    bindPictureInPictureButton(buttonEl("pipBtn"), player);
    // 再生を始めた時点で「見た」とみなす（最後まで見たかは問わない。一覧で未視聴を探す目印にするため）。
    // 印が付かなくても再生には関係ないので、失敗しても画面にエラーは出さない
    player.addEventListener("play", () => {
        apiPut(`/api/recordings/${rec.id}/watched`, { watched: true }).catch(() => {});
        apiPost(`/api/recordings/${rec.id}/play`, {}).catch(() => {});
    }, { once: true });

    query("#detailTable tbody").innerHTML = [
        detailRow("チャンネル", channelLink(rec.channelName, rec.channelUrl)),
        // 動画 ID から URL を推測しない。Twitch の録画は URL が null で、ID を文字で出す
        detailRow("元の配信", externalLink(rec.videoId, rec.videoUrl)),
        detailRow("録画開始", datetimeCell(rec.startedAt)),
        detailRow("録画終了", datetimeCell(rec.completedAt)),
        detailRow("再生時間", formatDuration(rec.durationSeconds)),
        detailRow("ファイルサイズ", formatFileSize(rec.fileSizeBytes)),
        detailRow("保存先", escapeHtml(rec.filePath)),
    ].join("");
    bindDatetimeCells(el("detailTable"));
}

/**
 * 同じチャンネルの他の録画を並べる。
 * 1 本見終わった後に一覧へ戻らず次を選べるようにするため。
 */
/**
 * 同じチャンネルの他の録画を並べる。
 * 1 本見終わった後に一覧へ戻らず次を選べるようにするため。
 *
 * @param {Recording} rec 再生中の録画
 */
async function loadRelated(rec) {
    const grid = el("relatedGrid");
    // URL 指定でダウンロードした動画はチャンネルに紐づかないことがある。
    // その場合 channelId が無く「同じチャンネルの録画」を引けない（API も絞り込めない）
    if (rec.channelId === null || rec.channelId === undefined) {
        grid.innerHTML = '<p class="muted">この録画はチャンネルに紐づいていないため、関連する録画はありません</p>';
        return;
    }
    try {
        const data = await apiGet(`/api/recordings?channelId=${rec.channelId}&page=0&size=12`);
        grid.innerHTML = "";
        // 再生中のものを関連に混ぜても選べないだけなので除く
        const others = /** @type {Recording[]} */ (data.content).filter((r) => r.id !== rec.id);
        if (others.length === 0) {
            grid.innerHTML = '<p class="muted">他の録画はありません</p>';
            return;
        }
        for (const r of others) {
            grid.appendChild(buildVideoCard(r, null));
        }
        bindDatetimeCells(grid);
    } catch (e) {
        showError(errorMessage(e));
    }
}

async function load() {
    if (!recordingId) {
        showError("再生する録画が指定されていません");
        return;
    }
    try {
        // 先にローカルへ受けてから使う。recording は null を取りうる型のため、
        // そのまま渡すと「null かもしれない」と扱われてしまう
        const loaded = /** @type {Recording} */ (await apiGet(`/api/recordings/${recordingId}`));
        recording = loaded;
        clearError();
        renderDetail(loaded);
        loadRelated(loaded);
    } catch (e) {
        showError(errorMessage(e));
        el("title").textContent = "録画を読み込めませんでした";
    }
}

el("deleteBtn").addEventListener("click", async () => {
    if (!recording) return;
    if (!confirm("この録画を削除しますか？（録画ファイルも一緒に削除されます）")) return;
    try {
        await apiDelete(`/api/recordings/${recording.id}`);
        // 再生対象が無くなった画面に留まっても何もできないので一覧へ戻す
        window.location.href = "/recordings.html";
    } catch (e) {
        showError(errorMessage(e));
    }
});

load();
