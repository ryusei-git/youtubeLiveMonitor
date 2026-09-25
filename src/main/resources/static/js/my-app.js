// @ts-check
/*
 * 利用者画面の 1 枚のページ（my.html、#146）。URL に応じて中身（main#view）だけを描き替え、
 * 再生中の動画は画面下のミニプレーヤー（#playerDock）に残す。
 *
 * 画面ごとに別の HTML だと、ページを移った時点で動画が消える（小窓も閉じる）。アプリ内の別の画面を
 * 見ながら再生を続けられるよう、ページは読み込み直さずに History API で URL だけを切り替える
 * （画面ごとの URL・戻る・ブックマークは残る）。
 *
 * jsconfig.json はすべての js を 1 つのスコープで型検査するため、グローバルの名前は my で始める
 * （ほかのページのスクリプトと名前がぶつかると型検査のエラーになる）。
 */

/* ============================================================
   再生ドック
   動画要素は #playerDock の中の 1 つだけ。DOM から外したり別の親へ移したりすると再生が止まり、
   小窓も閉じるため、大きさはクラスだけで切り替える。画面はここの関数を呼ぶだけにする。
   ============================================================ */

/** ドックに読み込んでいる録画。何も読み込んでいなければ null。 */
/** @type {Recording|null} */
let myDockRecording = null;

/** 読み込んだ録画の視聴済みを送ったか。一時停止と再開のたびに送らないため。 */
let myDockWatchedSent = false;

/** @returns {HTMLVideoElement} ドックの動画要素 */
function myDockVideo() {
    return /** @type {HTMLVideoElement} */ (el("dockVideo"));
}

/**
 * 録画をドックに読み込む。自動では再生しない（管理者の再生画面と同じく、利用者が再生を押す）。
 * 同じ録画を読み込んでいれば何もしない。ミニプレーヤーから再生画面へ戻ったときに読み込み直すと、
 * 再生が途切れて先頭へ戻るため。
 *
 * @param {Recording} rec 読み込む録画
 */
function myDockLoad(rec) {
    if (myDockRecording?.id === rec.id) return;
    myDockRecording = rec;
    myDockWatchedSent = false;
    const video = myDockVideo();
    // ファイル名に日本語や記号が入るため、パスとして安全な形に符号化する
    video.src = `/recordings/${encodeURI(rec.filePath)}`;
    const title = /** @type {HTMLAnchorElement} */ (el("dockTitle"));
    title.textContent = rec.videoTitle;
    title.href = myWatchPath(rec);
    bindMediaSession(video, {
        title: rec.videoTitle,
        artist: rec.channelName,
        artworkUrl: rec.thumbnailPath ? `/recordings/${encodeURI(rec.thumbnailPath)}` : null,
    });
}

/**
 * ドックを出し、大きさを切り替える。
 *
 * @param {"full"|"mini"} mode full は再生画面での通常の大きさ、mini はほかの画面での画面下のミニプレーヤー
 */
function myDockShow(mode) {
    const dock = el("playerDock");
    dock.classList.toggle("is-full", mode === "full");
    dock.classList.toggle("is-mini", mode === "mini");
    // ブラウザ標準の操作はミニプレーヤーの大きさでは小さすぎて押せないため、ドックのボタンに任せる
    myDockVideo().controls = mode === "full";
    dock.hidden = false;
}

/** 再生画面を離れるときに呼ぶ。何か読み込んでいればミニプレーヤーにする。 */
function myDockMinimize() {
    if (myDockRecording) myDockShow("mini");
}

/** 再生を止めてドックを消す。 */
function myDockClose() {
    const video = myDockVideo();
    // 小窓は src を外しても開いたまま残る（Chrome で確認）ため、先に閉じる
    if (document.pictureInPictureElement === video) document.exitPictureInPicture().catch(() => {});
    video.pause();
    // src を空にしないと、閉じた後も裏で読み込みが続く
    video.removeAttribute("src");
    video.load();
    myDockRecording = null;
    const dock = el("playerDock");
    dock.hidden = true;
    dock.classList.remove("is-full", "is-mini");
}

/** ドックのボタンと動画のイベントを結びつける。動画要素は 1 つのままなので、起動時に 1 回だけ呼ぶ。 */
function myDockInit() {
    const video = myDockVideo();
    const toggle = buttonEl("dockToggle");
    const renderToggle = () => {
        const label = video.paused ? "再生" : "一時停止";
        toggle.textContent = video.paused ? "▶" : "❚❚";
        toggle.setAttribute("aria-label", label);
        toggle.title = label;
    };
    // 別の録画を読み込んだときは pause が来ずに止まる（emptied だけが来る）
    for (const type of ["play", "pause", "emptied"]) video.addEventListener(type, renderToggle);
    toggle.addEventListener("click", () => {
        // 直後の一時停止で中断された等の失敗は、再生されないこと自体で分かるため何も出さない
        if (video.paused) video.play().catch(() => {});
        else video.pause();
    });
    // 再生を始めた時点で「見た」とみなす（管理者の再生画面と同じ）。
    // 印が付かなくても再生には関係ないので、失敗しても画面にエラーは出さない
    video.addEventListener("play", () => {
        if (!myDockRecording || myDockWatchedSent) return;
        myDockWatchedSent = true;
        apiPut(`/api/my/recordings/${myDockRecording.id}/watched`, { watched: true }).catch(() => {});
    });
    buttonEl("dockClose").addEventListener("click", myDockClose);
    bindPictureInPictureButton(buttonEl("dockPip"), video);
}

/* ============================================================
   画面
   ============================================================ */

/**
 * 利用者画面の 1 画面。
 *
 * @typedef {object} MyView
 * @property {string} title 画面名（document.title に使う）
 * @property {string} [nav] メニューで今いる画面として印を付ける項目の href。メニューに無い画面は省く
 * @property {(root: HTMLElement, match: RegExpMatchArray, params: URLSearchParams) => unknown} render
 *   main#view に中身を描く。読み込みを待った後は、描いた要素がまだページにあるときだけ結果を反映する
 *   （待つ間に別の画面へ移っていると、古い画面の結果が今の画面を上書きするため）
 * @property {() => void} [leave] 画面を離れる前の後始末（タイマー・イベント・ドックの大きさ）
 */

/**
 * 録画の再生画面の URL。
 *
 * @param {Recording} rec 録画
 * @returns {string} 再生画面のパス
 */
function myWatchPath(rec) {
    return `/my/watch/${rec.id}`;
}

/** トップの自動更新を止める関数。トップを出していない間は null。 */
/** @type {(() => void)|null} */
let myTopStopRefresh = null;

/**
 * トップ。購読しているチャンネルの配信中と配信予定を出す。
 * 配信は分単位で始まり・終わるため、開いている間は 1 分ごとに読み直す（管理画面のダッシュボードと同じ間隔）。
 * 配信予定も一緒に読み直すのは、始まった配信が「配信予定」に残ったまま「配信中」にも並ぶのを避けるため。
 * @type {MyView}
 */
const myTopView = {
    title: "トップ",
    nav: "/my",
    render(root) {
        root.innerHTML = `<h1>トップ</h1>
            <p class="pageDescription">購読しているチャンネルの配信中と配信予定。開いている間は 1 分ごとに更新します。</p>
            <p id="error" class="error" role="alert" style="display:none;"></p>
            <section class="livePanel"><h2>配信中</h2><div class="videoGrid"></div></section>
            <section class="upcomingPanel">
                <h2>配信予定 <span class="hint" title="YouTube の待機所（配信開始前の予約枠）から読み取った、7 日以内の開始予定です。Twitch は対象外です。">ⓘ</span></h2>
                <div></div>
            </section>`;
        const live = query(".livePanel .videoGrid", root);
        const upcoming = query(".upcomingPanel > div", root);
        // 配信はダイアログで開く。開いている間はダイアログの外（ミニプレーヤー）を押せず、録画と音が重なるため止める。
        // カードのボタンがダイアログを開いた後に届くので、開けなかったとき（埋め込めない URL）は止めない
        live.addEventListener("click", () => {
            if (document.querySelector("dialog[open]")) myDockVideo().pause();
        });
        const load = async () => {
            try {
                const [page, streams] = await Promise.all([
                    // 購読は 1 人 50 件までなので、API の上限の 100 件で全部入る（ページ送りを置かない）
                    apiGet("/api/videos?liveOnly=true&size=100"),
                    apiGet("/api/my/upcoming"),
                ]);
                if (!live.isConnected) return;
                clearError();
                const none = emptyState("今はありません");
                renderLiveVideoCards(live, page, none);
                renderUpcomingStreams(streams, upcoming, none);
            } catch (e) {
                if (live.isConnected) showError(errorMessage(e));
            }
        };
        load();
        myTopStopRefresh = startVisibleRefresh(load);
    },
    leave() {
        myTopStopRefresh?.();
        myTopStopRefresh = null;
    },
};

/**
 * 録画一覧（仮）。第 2 陣で検索一式を備えたアーカイブ画面に置き換える。
 * @type {MyView}
 */
const myArchiveView = {
    title: "アーカイブ",
    nav: "/my/archive",
    async render(root) {
        root.innerHTML = `<h1>アーカイブ</h1>
            <p class="pageDescription">購読しているチャンネルの録画（新しい順に 24 件）。再生中にほかの画面へ移っても、画面下で再生を続けます。</p>
            <p id="error" class="error" role="alert" style="display:none;"></p>
            <div class="videoGrid"></div>`;
        const grid = query(".videoGrid", root);
        setBusy(grid, true);
        try {
            const data = await apiGet("/api/my/recordings?playableOnly=true&size=24");
            if (!grid.isConnected) return;
            if (data.content.length === 0) {
                grid.innerHTML = emptyState("再生できる録画はまだありません",
                    "マイチャンネルで録画を「する」にすると、条件に合う配信が自動で保存されます");
            }
            for (const rec of /** @type {Recording[]} */ (data.content)) {
                grid.appendChild(buildVideoCard(rec, null, true, null, null, myWatchPath));
            }
            bindDatetimeCells(grid);
        } catch (e) {
            if (grid.isConnected) showError(errorMessage(e));
        } finally {
            setBusy(grid, false);
        }
    },
};

/**
 * 再生画面。録画をドックに読み込み、下に詳細と同じチャンネルの録画を出す。
 * @type {MyView}
 */
const myWatchView = {
    title: "再生",
    // 録画を選んで開く画面なので、アーカイブの中にいるものとして示す（管理画面の再生画面も同じ）
    nav: "/my/archive",
    async render(root, match) {
        root.innerHTML = `<p id="error" class="error" role="alert" style="display:none;"></p>
            <h1>読み込み中...</h1>
            <div class="table-scroll"><table><tbody></tbody></table></div>
            <h2>同じチャンネルの録画</h2>
            <div class="videoGrid"></div>`;
        const heading = query("h1", root);
        /** @type {Recording} */
        let rec;
        try {
            // 見られない録画（購読していない・削除された）は 404 で返る。ほかの失敗と分けて伝えるため、
            // 状態コードを見られるよう apiGet を使わない
            const res = await authenticatedFetch(`/api/my/recordings/${match[1]}`);
            if (res.status === 404) {
                if (heading.isConnected) {
                    root.innerHTML = `<h1>この録画は見られません</h1>
                        <p class="pageDescription">購読していないチャンネルの録画か、削除された録画です。</p>`;
                }
                return;
            }
            if (!res.ok) throw new Error(await extractError(res));
            rec = await res.json();
        } catch (e) {
            if (heading.isConnected) {
                heading.textContent = "録画を読み込めませんでした";
                showError(errorMessage(e));
            }
            return;
        }
        if (!heading.isConnected) return;

        document.title = `${rec.videoTitle} - YouTube Live Monitor`;
        heading.textContent = rec.videoTitle;
        // 録画中・失敗の録画には再生できるファイルが無い。ドックに読み込むと壊れた動画が残るため読み込まない
        if (PLAYABLE_RECORDING_STATUSES.includes(rec.status)) {
            myDockLoad(rec);
            myDockShow("full");
        } else {
            showError("この録画は再生できるファイルが残っていません");
        }
        query("tbody", root).innerHTML = [
            ["チャンネル", channelLink(rec.channelName, rec.channelUrl)],
            ["元の配信", videoLink(rec.videoId)],
            ["録画開始", datetimeCell(rec.startedAt)],
            ["録画終了", datetimeCell(rec.completedAt)],
            ["再生時間", formatDuration(rec.durationSeconds)],
            ["ファイルサイズ", formatFileSize(rec.fileSizeBytes)],
        ].map(([label, value]) => `<tr><th>${label}</th><td>${value}</td></tr>`).join("");
        bindDatetimeCells(root);
        myLoadRelated(rec, query(".videoGrid", root));
    },
    leave: myDockMinimize,
};

/**
 * 同じチャンネルのほかの録画を並べる。1 本見終わった後に一覧へ戻らず次を選べるようにするため。
 *
 * @param {Recording} rec 再生画面の録画
 * @param {HTMLElement} grid 並べる先
 */
async function myLoadRelated(rec, grid) {
    try {
        const data = await apiGet(`/api/my/recordings?channelId=${rec.channelId}&playableOnly=true&size=12`);
        if (!grid.isConnected) return;
        // 今見ている録画を「ほかの録画」に並べても選ぶ意味が無いので除く
        const others = /** @type {Recording[]} */ (data.content).filter((r) => r.id !== rec.id);
        if (others.length === 0) {
            grid.innerHTML = '<p class="muted">他の録画はありません</p>';
            return;
        }
        grid.replaceChildren(...others.map((r) => buildVideoCard(r, null, true, null, null, myWatchPath)));
        bindDatetimeCells(grid);
    } catch (e) {
        if (grid.isConnected) showError(errorMessage(e));
    }
}

/** @type {MyView} */
const myNotFoundView = {
    title: "ページが見つかりません",
    render(root) {
        root.innerHTML = `<h1>ページが見つかりません</h1>
            <p class="pageDescription">URL を確かめてください。<a href="/my">トップへ</a></p>`;
    },
};

/* ============================================================
   画面の切り替え（ルーター）
   ============================================================ */

/**
 * ルートの表。上から順にパスと照らし、最初に合った画面を出す。
 * 最後の行は、どれにも合わない /my 配下（第 2 陣で足す /my/channels など）。
 * @type {Array<[RegExp, MyView]>}
 */
const myRoutes = [
    [/^\/my\/?$/, myTopView],
    [/^\/my\/archive\/?$/, myArchiveView],
    [/^\/my\/watch\/(\d+)\/?$/, myWatchView],
    [/^/, myNotFoundView],
];

/** 今出している画面。離れるときに leave() を呼ぶため覚えておく。 */
/** @type {MyView|null} */
let myCurrentView = null;

/**
 * メニューの今いる画面の項目に印（active）を付け替える。ページを読み込み直さずに画面を移るため、
 * HTML に書いた印では最初に開いた画面にしか合わない。
 *
 * @param {string|undefined} href 今いる画面の項目の href。メニューに無い画面では undefined
 */
function myMarkNav(href) {
    document.querySelectorAll(".globalnav a").forEach((link) => {
        link.classList.toggle("active", link.getAttribute("href") === href);
        link.removeAttribute("aria-current");
    });
    // aria-current を付け直すのと、狭い画面で今いる項目を見える位置へ寄せるのは共通の処理に任せる
    decorateStudioNavigation();
}

/** 今の URL の画面を描く。リンクの横取り・戻る／進む・起動時のすべてがここを通る。 */
function myRender() {
    myCurrentView?.leave?.();
    const params = new URLSearchParams(location.search);
    for (const [pattern, view] of myRoutes) {
        const match = location.pathname.match(pattern);
        if (!match) continue;
        myCurrentView = view;
        document.title = `${view.title} - YouTube Live Monitor`;
        const root = el("view");
        root.replaceChildren();
        window.scrollTo(0, 0);
        myMarkNav(view.nav);
        view.render(root, match, params);
        return;
    }
}

/**
 * ログイン後の復帰で付く continue を URL から取り除く。Spring Security は保存した URL の末尾に
 * 付けて戻す（/my/archive?genre=x&continue）。検索条件ではなく、残すとブックマークにも付いて回る。
 */
function myDropContinueParam() {
    const url = new URL(location.href);
    if (!url.searchParams.has("continue")) return;
    url.searchParams.delete("continue");
    history.replaceState(null, "", url);
}

// このページの中の画面（/my と /my/...）へのリンクは、ページを読み込み直さずに切り替える。
// 新しいタブ・ウィンドウで開く操作（修飾キー・左以外のボタン・target・download）はブラウザに任せる
document.addEventListener("click", (event) => {
    if (event.defaultPrevented || event.button !== 0
        || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    const link = event.target instanceof Element ? event.target.closest("a[href]") : null;
    if (!(link instanceof HTMLAnchorElement) || link.hasAttribute("target") || link.hasAttribute("download")) return;
    const url = new URL(link.href);
    // /my-channels.html なども /my で始まるため、/my そのものか /my/ の下かで見分ける
    if (url.origin !== location.origin || !/^\/my(\/|$)/.test(url.pathname)) return;
    // 同じ画面の中の移動（「本文へ移動」など）はブラウザに任せる
    if (url.pathname === location.pathname && url.search === location.search && url.hash) return;
    event.preventDefault();
    if (url.href !== location.href) history.pushState(null, "", url);
    myRender();
});
window.addEventListener("popstate", myRender);

myDropContinueParam();
myDockInit();
myRender();
