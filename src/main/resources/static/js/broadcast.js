// @ts-check
/*
 * 連続再生の画面（broadcast.html）。再生できる録画をシャッフルして 1 本ずつ流し、1 周したら並べ直して続ける。
 *
 * ブラウザのウィンドウを Discord の画面共有で映し、人が付いていなくても流れ続けることを前提にしている。
 * そのため、読み込めない録画・止まったままの再生・本番の反映中の読み込み失敗で止まりきらず、
 * 飛ばすか待ってから続ける。並び順はブラウザだけで持つ（サーバーに再生リストを持たせると、タブを閉じたときの
 * 後始末が要るため）。
 *
 * トップレベルの名前は broadcast で始める。jsconfig.json がすべての js を 1 つのスコープで型検査するため、
 * ほかの画面の js（player.js の load・recording など）と重なると型エラーになる。
 */

/** 1 回で取る件数。API の上限（RecordingController.MAX_PAGE_SIZE）に合わせ、往復を減らす。 */
const BROADCAST_PAGE_SIZE = 100;
/**
 * この本数だけ続けて読み込めなければ、飛ばすのをやめて待つ。本番の反映（コンテナの入れ替え）の間は
 * どの録画も読めないので、飛ばし続けると一瞬で 1 周して空回りする。
 */
const BROADCAST_MAX_FAILURES = 5;
/** 読み込めなかった録画の理由を読めるだけの間を置いてから次へ進む。 */
const BROADCAST_SKIP_DELAY_MS = 5000;
/** 続けて失敗したとき・再生リストを作れなかったときに、作り直すまで待つ時間。 */
const BROADCAST_RETRY_DELAY_MS = 60000;
/** 一時停止していないのにこの時間だけ再生位置が進まなければ、止まったとみなして次へ進む。 */
const BROADCAST_STALL_LIMIT_MS = 60000;

/** @type {Recording[]} 今の周の並び。 */
let broadcastQueue = [];
/** 今流している録画の broadcastQueue での位置。始める前は -1。 */
let broadcastIndex = -1;
/** @type {Recording|null} 今流している録画。 */
let broadcastCurrent = null;
/**
 * 最後に実際に流れ始めた録画の ID（視聴済みの印を付けた録画）。動画要素は 1 つを使い回すので、録画ごとに
 * once のリスナーを付けると付け外しの漏れで印が付かない・重なって付くことがある。ID で判定し、一時停止からの
 * 再開・読み込み待ちからの再開でも付け直さない。
 * @type {number|null}
 */
let broadcastMarkedId = null;
/** 続けて読み込めなかった本数。1 本でも流れ始めたら 0 に戻す。 */
let broadcastFailures = 0;
/** @type {number|null} 飛ばす・作り直すまでの待ちのタイマー。待っている間は見張りで次へ進めない。 */
let broadcastWaitTimer = null;
/** 次へ進む処理の途中か。作り直しの通信中に「次の録画へ」を押されても 2 本飛ばさないため。 */
let broadcastAdvancing = false;
/** 見張りが最後に見た再生位置と、その位置が最後に進んだ時刻。 */
let broadcastLastPosition = 0;
let broadcastLastProgressAt = 0;

/** @returns {HTMLVideoElement} 連続再生の動画要素 */
function broadcastPlayer() {
    return /** @type {HTMLVideoElement} */ (el("broadcastPlayer"));
}

/**
 * 状況の欄に 1 行出す。空文字なら消す。
 *
 * @param {string} message 出す文（エスケープ前の文）
 */
function broadcastNotify(message) {
    el("broadcastNotice").innerHTML = message ? `<p class="muted">${escapeHtml(message)}</p>` : "";
}

/**
 * 再生できる録画をすべて取る。
 *
 * <p>状態の絞り込みを API に任せないのは、再生できる状態が 2 つ（PLAYABLE_RECORDING_STATUSES）あり、
 * API の status は 1 つしか指定できないため。録画は数百件の規模なので、全件を取ってから絞る。
 *
 * @returns {Promise<Recording[]>} 再生できる録画（並びは問わない）
 */
async function broadcastFetchPlayable() {
    /** @type {Recording[]} */
    const all = [];
    for (let page = 0; ; page++) {
        const data = await apiGet(`/api/recordings?sort=newest&page=${page}&size=${BROADCAST_PAGE_SIZE}`);
        all.push(...data.content);
        if (data.last || data.content.length === 0) break;
    }
    return all.filter((r) => PLAYABLE_RECORDING_STATUSES.includes(r.status) && r.filePath);
}

/**
 * 並びを Fisher–Yates で混ぜた写しを返す（偏りなく、どの並びも同じ確率で出るため）。
 *
 * @template T
 * @param {T[]} items 混ぜる要素
 * @returns {T[]} 混ぜた写し（元の配列は変えない）
 */
function broadcastShuffle(items) {
    const result = items.slice();
    for (let i = result.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [result[i], result[j]] = [result[j], result[i]];
    }
    return result;
}

/**
 * 再生リストを作り直す。周ごとに取り直すのは、新しく増えた録画を入れ、消された録画を外すため。
 * 作り直した先頭が最後に流れた録画なら 2 本目と入れ替え、同じ録画が続けて流れないようにする。
 * 今の録画（broadcastCurrent）と比べないのは、周の最後が読み込めずに飛ばした録画だと、その前に流れた録画が
 * 先頭に来たときに見逃すため。
 */
async function broadcastRebuildQueue() {
    const queue = broadcastShuffle(await broadcastFetchPlayable());
    if (queue.length > 1 && queue[0].id === broadcastMarkedId) {
        [queue[0], queue[1]] = [queue[1], queue[0]];
    }
    broadcastQueue = queue;
    broadcastIndex = -1;
}

/** 待ちのタイマーを取り消す。手で次へ進めたときに、待っていた「次へ」が重ねて走らないため。 */
function broadcastCancelWait() {
    if (broadcastWaitTimer !== null) {
        clearTimeout(broadcastWaitTimer);
        broadcastWaitTimer = null;
    }
}

/**
 * しばらく待ってから次へ進む。
 *
 * @param {number} delayMs   待つ時間
 * @param {boolean} rebuild  true なら今の周を打ち切り、再生リストを作り直してから流す
 */
function broadcastNextAfter(delayMs, rebuild) {
    broadcastCancelWait();
    broadcastWaitTimer = setTimeout(() => {
        broadcastWaitTimer = null;
        if (rebuild) broadcastIndex = broadcastQueue.length;
        broadcastNext();
    }, delayMs);
}

/**
 * 次の録画へ進む。今の周を流し終えていれば、再生リストを作り直してから流す。
 */
async function broadcastNext() {
    if (broadcastAdvancing) return;
    broadcastAdvancing = true;
    broadcastCancelWait();
    try {
        broadcastIndex++;
        if (broadcastIndex >= broadcastQueue.length) {
            try {
                await broadcastRebuildQueue();
                clearError();
            } catch (e) {
                // 401 は apiGet がログイン画面へ移す。それ以外（通信の途切れ・サーバーの再起動中）は待ってから作り直す
                showError(errorMessage(e));
                broadcastNotify("再生リストを作れなかったので、1 分後に作り直します");
                broadcastNextAfter(BROADCAST_RETRY_DELAY_MS, true);
                return;
            }
            if (broadcastQueue.length === 0) {
                broadcastShowEmpty();
                // 録画が終われば流せるようになるので、見放さずに待ってから作り直す
                broadcastNextAfter(BROADCAST_RETRY_DELAY_MS, true);
                return;
            }
            broadcastIndex = 0;
        }
        broadcastPlay(broadcastQueue[broadcastIndex]);
    } finally {
        broadcastAdvancing = false;
    }
}

/** 録画が 1 本も無いときに、黒い動画の枠の代わりに理由を出す。 */
function broadcastShowEmpty() {
    broadcastCurrent = null;
    const player = broadcastPlayer();
    player.pause();
    player.removeAttribute("src");
    player.hidden = true;
    el("broadcastNow").hidden = true;
    el("broadcastControls").hidden = true;
    el("broadcastNotice").innerHTML = emptyState("再生できる録画がありません", "録画が終わると、ここで流せるようになります。");
    document.title = "連続再生 - YouTube Live Monitor";
}

/**
 * 録画を動画の枠に読み込んで流し、「再生中」と「次」の欄を更新する。
 *
 * @param {Recording} rec 流す録画
 */
function broadcastPlay(rec) {
    broadcastCurrent = rec;
    const player = broadcastPlayer();
    player.hidden = false;
    el("broadcastNow").hidden = false;
    el("broadcastControls").hidden = false;
    broadcastNotify("");

    // ファイル名に日本語や記号が入るため、パスとして安全な形に符号化する（player.js の bindPlayer と同じ）
    player.src = `/recordings/${encodeURI(rec.filePath)}`;
    bindMediaSession(player, {
        title: rec.videoTitle,
        artist: rec.channelName,
        artworkUrl: rec.thumbnailPath ? `/recordings/${encodeURI(rec.thumbnailPath)}` : null,
    });
    document.title = `${rec.videoTitle} - 連続再生`;

    el("broadcastTitle").textContent = rec.videoTitle;
    el("broadcastMeta").innerHTML =
        `${escapeHtml(rec.channelName)} ・ ${datetimeCell(rec.startedAt)} ・ ${escapeHtml(formatDuration(rec.durationSeconds))}`;
    bindDatetimeCells(el("broadcastMeta"));
    const upNext = broadcastQueue[broadcastIndex + 1];
    el("broadcastUpNext").textContent = upNext
        ? `次: ${upNext.videoTitle}`
        : "次: 1 周したので、並べ直して最初から流します";

    broadcastLastPosition = 0;
    broadcastLastProgressAt = Date.now();
    player.play().catch((e) => {
        // 自動再生の制限で断られたとき（画面を開き直した直後など）は、もう一度ボタンで始めてもらう。
        // 読み込みの失敗は error イベントの側で扱うので、ここでは何もしない
        if (e instanceof DOMException && e.name === "NotAllowedError") {
            buttonEl("broadcastStartBtn").hidden = false;
            broadcastNotify("ブラウザに自動での再生を止められました。「再生を始める」を押してください");
        }
    });
}

/**
 * 読み込めなかった録画を飛ばす。続けて失敗したら、飛ばすのをやめて待ってから作り直す。
 */
async function broadcastHandleError() {
    const rec = broadcastCurrent;
    if (!rec) return;
    const reason = await describeVideoError(broadcastPlayer());
    // null は失敗していない・読み込みを止めただけ・ログイン画面へ移るとき。どれも飛ばす理由にならない
    if (!reason) return;
    // 理由を調べている間に、手で次へ進められていたら何もしない
    if (broadcastCurrent !== rec) return;
    broadcastFailures++;
    if (broadcastFailures >= BROADCAST_MAX_FAILURES) {
        broadcastFailures = 0;
        broadcastNotify(`再生できない録画が続いたので、1 分後に再生リストを作り直します（${reason}）`);
        broadcastNextAfter(BROADCAST_RETRY_DELAY_MS, true);
        return;
    }
    broadcastNotify(`「${rec.videoTitle}」を再生できなかったので、次の録画へ進みます（${reason}）`);
    broadcastNextAfter(BROADCAST_SKIP_DELAY_MS, false);
}

/**
 * 再生位置が進んでいるかを見張り、止まったままなら次へ進む。
 * 回線の途切れやファイルの途中の破損で、黒い画面のまま朝まで止まり続けるのを避けるため。
 * 利用者が一時停止しているとき・飛ばすのを待っているときは見張らない。
 */
function broadcastWatchProgress() {
    const player = broadcastPlayer();
    const now = Date.now();
    if (!broadcastCurrent || player.paused || player.ended || broadcastWaitTimer !== null || broadcastAdvancing
            || player.currentTime !== broadcastLastPosition) {
        broadcastLastPosition = player.currentTime;
        broadcastLastProgressAt = now;
        return;
    }
    if (now - broadcastLastProgressAt < BROADCAST_STALL_LIMIT_MS) return;
    // すぐ次へ進むと、次の録画を流すときに知らせが消えて読めないので、読み込めなかったときと同じく間を置く
    broadcastNotify(`「${broadcastCurrent.videoTitle}」の再生が止まったので、次の録画へ進みます`);
    broadcastNextAfter(BROADCAST_SKIP_DELAY_MS, false);
}

/** 動画要素と画面の操作をつなぐ。動画要素は 1 つを使い回すので、イベントはここで 1 回だけ付ける。 */
function broadcastInit() {
    const player = broadcastPlayer();
    player.addEventListener("ended", () => broadcastNext());
    // 流れ始めた時点で「見た」とみなす（再生画面の player.js と同じ考え方）。player.js のように play で付けないのは、
    // play は読み込みの前に出るため、ファイルが無くて飛ばした録画にまで印が付くから。印が付かなくても流すのには
    // 関係ないので、失敗しても画面には出さない
    player.addEventListener("playing", () => {
        broadcastFailures = 0;
        const rec = broadcastCurrent;
        if (!rec || broadcastMarkedId === rec.id) return;
        broadcastMarkedId = rec.id;
        apiPut(`/api/recordings/${rec.id}/watched`, { watched: true }).catch(() => {});
        apiPost(`/api/recordings/${rec.id}/play`, {}).catch(() => {});
    });
    player.addEventListener("error", () => { broadcastHandleError(); });

    buttonEl("broadcastSkipBtn").addEventListener("click", () => broadcastNext());
    buttonEl("broadcastFullscreenBtn").addEventListener("click", () => {
        el("broadcastStage").requestFullscreen().catch((e) => showError(`全画面にできませんでした（${errorMessage(e)}）`));
    });

    const start = buttonEl("broadcastStartBtn");
    start.addEventListener("click", async () => {
        start.hidden = true;
        // 自動再生の制限で止められた後の押し直しなら、並びはそのままで今の録画を流し直す
        if (broadcastCurrent) {
            broadcastPlayer().play().catch(() => {});
            broadcastNotify("");
            return;
        }
        broadcastNotify("再生リストを作っています…");
        await broadcastNext();
    });

    setInterval(broadcastWatchProgress, 5000);
}

broadcastInit();
