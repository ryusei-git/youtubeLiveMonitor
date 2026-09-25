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

/**
 * 再生を始めて視聴済みを付け終えたときに呼ぶ処理。再生画面が、自分のボタンの表示を合わせるために入れる。
 * 付けられなかったときは呼ばない（ボタンには保存された状態を出すため）。
 * @type {((recordingId: number) => void)|null}
 */
let myDockOnWatched = null;

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
        const id = myDockRecording.id;
        apiPut(`/api/my/recordings/${id}/watched`, { watched: true })
            .then(() => myDockOnWatched?.(id))
            .catch(() => {});
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

/**
 * 配信・動画のカード（common.js の buildOnlineVideoCard）を並べた入れ物で、埋め込みの再生を開いたらミニプレーヤーの録画を止める。
 * 再生はモーダルのダイアログで開くので、開いている間はダイアログの外（ミニプレーヤー）を押せず、録画と音が重なるため。
 * カードのボタンがダイアログを開いた後に届くので、開けなかったとき（埋め込めない URL）は止めない
 *
 * @param {Element} container カードを並べた入れ物。画面を描くたびに作り直すもの（main#view に付けると、離れた後も残る）
 */
function myPauseDockOnVideoDialog(container) {
    container.addEventListener("click", () => {
        if (document.querySelector("dialog[open]")) myDockVideo().pause();
    });
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
            <section class="livePanel"><h2>配信中</h2><div class="videoGrid"><p class="muted">読み込み中...</p></div></section>
            <section class="upcomingPanel">
                <h2>配信予定</h2>
                <p class="muted">YouTube の待機所（配信開始前の予約枠）から読み取った、7 日以内の開始予定です。Twitch は対象外です。</p>
                <div><p class="muted">読み込み中...</p></div>
            </section>`;
        const live = query(".livePanel .videoGrid", root);
        const upcoming = query(".upcomingPanel > div", root);
        myPauseDockOnVideoDialog(live);
        /**
         * 前回描いた内容の要約。同じなら描き直さない（描き直すとフォーカスしていたカードが DOM から外れ、body へ飛ぶため）。
         * lastObservedAt は巡回のたびに配信中の動画ごとに書き換わる（OnlineVideoService.observe）が画面には出ないので、比べる値から外す。
         */
        let lastKey = "";
        const load = async () => {
            try {
                const [page, streams] = await Promise.all([
                    // 購読は 1 人 50 件までなので、API の上限の 100 件で全部入る（ページ送りを置かない）
                    apiGet("/api/videos?liveOnly=true&size=100"),
                    apiGet("/api/my/upcoming"),
                ]);
                if (!live.isConnected) return;
                const key = JSON.stringify([page.content.map((/** @type {any} */ v) => ({ ...v, lastObservedAt: null })), streams]);
                if (key === lastKey) {
                    clearError();
                    return;
                }
                lastKey = key;
                clearError();
                renderLiveVideoCards(live, page, emptyState("配信中のチャンネルはありません"));
                renderUpcomingStreams(streams, upcoming, emptyState("7 日以内の配信予定はありません"));
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

/** 動画・配信の自動更新を止める関数。動画・配信を出していない間は null。 */
/** @type {(() => void)|null} */
let myVideosStopRefresh = null;

/**
 * 動画・配信。購読しているチャンネルの YouTube・Twitch 上の配信中・配信予定、配信済み、投稿済み（録画ではない）。
 * 中身と動きは管理者の動画一覧（videos.html）と同じもの（common.js の bindOnlineVideoSections）で、API が購読で絞る。
 *
 * 絞り込みは URL（/my/videos?keyword=...&channelId=...）に残す。アーカイブと同じく「戻る」「進む」はルーターが拾って
 * 画面ごと描き直し、描き直した画面が URL から条件を戻す（ルーターは history.state を使わないので pushState でよい）。
 * 配信は分単位で始まり・終わるため、開いている間は 1 分ごとに読み直し、離れたら止める（トップと同じ）。
 * @type {MyView}
 */
const myVideosView = {
    title: "動画・配信",
    nav: "/my/videos",
    render(root) {
        const sectionHtml = [["now", "配信中・配信予定"], ["streams", "配信済み"], ["uploads", "投稿済み"]].map(([name, label]) => `
            <section class="videoSection" aria-labelledby="${name}Heading">
              <div class="videoSectionHead">
                <h2 id="${name}Heading">${label} <span class="muted" id="${name}Summary"></span></h2>
                <div class="videoPager"><button type="button" id="${name}Prev" aria-label="${label}の前のページ">前へ</button><span id="${name}Page" class="muted"></span><button type="button" id="${name}Next" aria-label="${label}の次のページ">次へ</button></div>
              </div>
              <div id="${name}Grid" class="videoGrid"></div>
            </section>`).join("");
        root.innerHTML = `<div class="pageHead"><h1>動画・配信</h1><button type="button" id="refreshVideosBtn">表示を更新</button></div>
            <p class="pageDescription">購読しているチャンネルの新着動画と配信を、この画面で再生できます。</p>
            <p id="error" class="error" role="alert" style="display:none;"></p>
            <p id="collectionNotice" class="muted" role="status">取得状況を確認しています…</p>
            <p id="videoRefreshStatus" class="muted" role="status">表示を読み込み中…</p>
            <form id="videoFilterForm" class="inline">
              <label>検索 <input id="videoKeyword" type="search" placeholder="タイトル・チャンネル名" maxlength="200"></label>
              <label>チャンネル <select id="videoChannel"><option value="">すべて</option></select></label>
              <button type="submit">検索</button>
            </form>
            ${sectionHtml}
            <p class="muted">新着動画は約10分ごとに取得します。収集を開始してから公開された動画と、監視中に見つかった配信を保存します。この一覧の収集では動画本体を保存しません（自動録画の設定は別です）。</p>`;
        root.querySelectorAll(".videoGrid").forEach(myPauseDockOnVideoDialog);
        const videos = bindOnlineVideoSections();
        videos.start();
        myVideosStopRefresh = startVisibleRefresh(() => videos.loadAll(false, false));
    },
    leave() {
        myVideosStopRefresh?.();
        myVideosStopRefresh = null;
    },
};

/**
 * 視聴済み・お気に入りを切り替える（アーカイブのカードと表・再生画面のボタン）。
 * 管理画面（recordings.js の toggleMark）と同じく、先に表示を変えてから API を呼び、一覧は読み直さない
 * （絞り込み中に読み直すと、押した録画が消えて何が起きたか分からなくなるため）。違うのは API のパスだけ。
 *
 * @param {Recording} recording 対象の録画
 * @param {RecordingMarkKind} kind 印の種類
 * @param {HTMLButtonElement} button 押されたボタン
 */
async function myToggleMark(recording, kind, button) {
    const next = !recording[kind];
    recording[kind] = next;
    renderRecordingMarkButton(button, kind, next);
    button.disabled = true;
    try {
        await apiPut(`/api/my/recordings/${recording.id}/${kind}`, { [kind]: next });
        // 待つ間に別の画面へ移っていたら、その画面のエラー帯には触らない
        if (button.isConnected) clearError();
    } catch (e) {
        recording[kind] = !next;
        renderRecordingMarkButton(button, kind, !next);
        if (button.isConnected) showError(errorMessage(e));
    } finally {
        button.disabled = false;
    }
}

/**
 * アーカイブの表の 1 行。列は管理画面の表と同じ並びで、削除だけが無い（録画は購読者どうしで共有しているため、
 * 利用者には消させない）。題名はカードと同じく再生画面（/my/watch/ID）へのリンクにする。
 *
 * @param {Recording} r 録画 1 件
 * @returns {HTMLTableRowElement} 行
 */
function myArchiveRow(r) {
    const tr = document.createElement("tr");
    tr.innerHTML = `<td>${datetimeCell(r.startedAt)}</td>
        <td>${channelLink(r.channelName, r.channelUrl)}</td>
        <td><a href="${myWatchPath(r)}">${escapeHtml(r.videoTitle)}</a></td>
        <td>${formatDuration(r.durationSeconds)}</td>
        <td>${formatFileSize(r.fileSizeBytes)}</td>
        <td>${recordingStatusLabel(r.status)}</td>
        <td>${escapeHtml(r.genre || "-")}</td>`;
    for (const kind of RECORDING_MARK_KINDS) tr.insertCell().appendChild(recordingMarkButton(r, kind, myToggleMark));
    return tr;
}

/**
 * アーカイブ。管理画面のアーカイブと同じ検索一式（common.js の bindRecordingSearch）で、購読しているチャンネルの
 * 録画を探す。録画中・失敗の録画は開いても見られないので、API に playableOnly=true を常に付け、状態の絞り込みは置かない。
 *
 * 条件は URL（/my/archive?...）に残す。「戻る」「進む」はルーターが拾って画面ごと描き直し、描き直した画面が
 * URL から条件を戻す。ルーターは history.state を使わないので、URL の書き方は管理画面と同じ（pushState）でよい。
 * @type {MyView}
 */
const myArchiveView = {
    title: "アーカイブ",
    nav: "/my/archive",
    render(root) {
        root.innerHTML = `<h1>アーカイブ</h1>
            <p class="pageDescription">購読しているチャンネルの録画。再生中にほかの画面へ移っても、画面下で再生を続けます。</p>
            <p id="error" class="error" role="alert" style="display:none;"></p>
            <form id="filterForm" class="inline">
              <input type="search" name="keyword" placeholder="タイトル・チャンネル名で検索" aria-label="タイトル・チャンネル名で検索">
              <button type="submit">検索</button>
              <details class="filterMore">
                <summary aria-expanded="false">絞り込み・並び順<small class="filterCount"></small></summary>
                <div class="inline">
                  <select name="channelId" aria-label="チャンネル"><option value="">全チャンネル</option></select>
                  <select name="genre" aria-label="ジャンル"><option value="">すべてのジャンル</option></select>
                  <label>期間 <input type="date" name="from" aria-label="期間の開始日"> 〜 <input type="date" name="to" aria-label="期間の終了日"></label>
                  <select name="sort" aria-label="並び順">
                    <option value="newest">新しい順</option>
                    <option value="oldest">古い順</option>
                    <option value="longest">長い順</option>
                    <option value="largest">サイズが大きい順</option>
                  </select>
                  <select name="size" aria-label="表示件数">
                    <option value="24">24件ずつ</option>
                    <option value="48">48件ずつ</option>
                    <option value="96">96件ずつ</option>
                  </select>
                  <select name="watched" aria-label="視聴">
                    <option value="">すべて</option>
                    <option value="unwatched">未視聴</option>
                    <option value="watched">視聴済み</option>
                  </select>
                  <label><input type="checkbox" name="favorite"> お気に入りのみ</label>
                  <button type="reset">条件をクリア</button>
                </div>
              </details>
            </form>
            <div class="inline">
              <div class="viewToggle" role="group" aria-label="表示">
                <button type="button" class="cardViewBtn" aria-pressed="true">カード</button>
                <button type="button" class="listViewBtn" aria-pressed="false">リスト</button>
              </div>
              <span class="resultSummary muted"></span>
            </div>
            <div class="videoGrid"></div>
            <div class="table-scroll" hidden>
              <table>
                <thead><tr><th>開始日時</th><th>チャンネル</th><th>タイトル</th><th>長さ</th><th>サイズ</th><th>状態</th><th>ジャンル</th><th>視聴</th><th>★</th></tr></thead>
                <tbody></tbody>
              </table>
            </div>
            <nav class="inline pager" aria-label="ページ送り">
              <button type="button" class="prevBtn">前へ</button>
              <span class="pageNumbers" style="display:contents"></span>
              <button type="button" class="nextBtn">次へ</button>
            </nav>`;
        const grid = query(".videoGrid", root);
        const summary = query(".resultSummary", root);
        /** 読み込みの番号。条件を続けて変えたとき、遅れて届いた古い応答で上書きしないため（管理画面と同じ）。 */
        let request = 0;
        const load = async () => {
            const current = ++request;
            try {
                const params = search.apiParams();
                params.set("playableOnly", "true");
                const data = await apiGet(`/api/my/recordings?${params}`);
                if (current !== request || !grid.isConnected) return;
                // ページが範囲を超えていた（URL の page が古いなど）ときは、最後のページに直して読み直す
                if (!search.show(data)) return load();
                clearError();
                summary.textContent = data.totalElements === 0 ? "" : `${data.totalElements}件`;
            } catch (e) {
                if (current === request && grid.isConnected) showError(errorMessage(e));
            }
        };
        const search = bindRecordingSearch({
            form: formEl("filterForm"),
            viewToggle: query(".viewToggle", root),
            grid,
            list: query(".table-scroll", root),
            pager: query(".pager", root),
            load,
            buildCard: (r) => buildVideoCard(r, null, true, null, myToggleMark, myWatchPath),
            buildRow: myArchiveRow,
            empty: emptyState("該当する録画はありません",
                "絞り込みを外してお試しください。マイチャンネルで自動録画をオンにすると、条件に合う配信が自動で保存されます"),
        });
        /**
         * 選択肢を API から足す。失敗しても一覧は出す（その選択肢で絞れないだけで、ほかの条件では探せる。管理画面と同じ）。
         *
         * @param {string} name 選択欄の name
         * @param {string} path 選択肢の元を返す API
         * @param {(item: any) => HTMLOptionElement} toOption 1 件を選択肢にする
         */
        const addOptions = async (name, path, toOption) => {
            const select = /** @type {HTMLSelectElement} */ (query(`select[name="${name}"]`, root));
            try {
                for (const item of await apiGet(path)) select.add(toOption(item));
            } catch (e) {
                if (select.isConnected) showError(errorMessage(e));
            }
        };
        // 選択肢が揃ってから URL の条件を戻す（先に戻すと、チャンネル・ジャンルが選択肢に無い値として捨てられる）
        Promise.all([
            addOptions("channelId", "/api/my/channels", (ch) => new Option(ch.channelName, String(ch.id))),
            // 件数を添えるのは、選ぶ前にどれだけ当たるか分かるようにするため（管理画面と同じ）
            addOptions("genre", "/api/my/recordings/genres", (g) => new Option(`${g.genre}（${g.count}）`, g.genre)),
        ]).then(() => {
            if (!grid.isConnected) return;
            search.restore();
            load();
        });
    },
};

/**
 * 再生画面。録画をドックに読み込み、下に視聴済み・お気に入りのボタン、端末に保存のリンク、詳細と同じチャンネルの録画を出す。
 * ボタンはアーカイブのカードと同じもの（common.js の recordingMarkButton）で、押したときの動きも同じ（myToggleMark）。
 * @type {MyView}
 */
const myWatchView = {
    title: "再生",
    // 録画を選んで開く画面なので、アーカイブの中にいるものとして示す（管理画面の再生画面も同じ）
    nav: "/my/archive",
    async render(root, match) {
        root.innerHTML = `<p id="error" class="error" role="alert" style="display:none;"></p>
            <h1>読み込み中...</h1>
            <p class="watchMarks"></p>
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
                        <p class="pageDescription">購読していないチャンネルの録画か、削除された録画です。</p>
                        <p><a href="/my/archive">アーカイブへ戻る</a></p>`;
                }
                return;
            }
            if (!res.ok) throw new Error(await extractError(res));
            rec = await res.json();
        } catch (e) {
            if (heading.isConnected) {
                heading.textContent = "録画を読み込めませんでした";
                heading.insertAdjacentHTML("afterend", '<p><a href="/my/archive">アーカイブへ戻る</a></p>');
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
            // 端末のファイルとして保存させる。ブラウザの中（IndexedDB など）に貯める方式は、iPhone の Safari が
            // しばらく使わないサイトのデータを消すことがあり、数 GB の録画を確実には残せないため採らない（#163）。
            // 同じオリジンのファイルなので download 属性だけで保存になり、見てよいかの確認も再生と同じものが効く
            query(".watchMarks", root).insertAdjacentHTML("afterend", `<p>
                <a href="/recordings/${escapeHtml(encodeURI(rec.filePath))}" download="${escapeHtml(recordingDownloadName(rec))}">端末に保存（${formatFileSize(rec.fileSizeBytes)}）</a><br>
                <span class="muted">iPhone では「ファイル」アプリの「ダウンロード」に保存されます。写真に入れるときは、ファイルを開いて共有→「ビデオを保存」。</span></p>`);
        } else {
            showError("この録画は再生できるファイルが残っていません");
        }
        // 入れ物は段落にする。.inline に入れると、狭い画面ではボタンが幅いっぱいに縦に積まれ（フォーム向けの決まり）、カードの印と見た目が変わる
        const watchedButton = recordingMarkButton(rec, "watched", myToggleMark);
        query(".watchMarks", root).append(watchedButton, recordingMarkButton(rec, "favorite", myToggleMark));
        // 再生を始めて付いた視聴済みを、このボタンにも出す。rec も変えないと、次に押したときに外れず、視聴済みをもう一度送る。
        // 前の再生画面が入れた処理はここで置き換わる（離れた画面のボタンを書き換えても見えないので、離れるときに外さない）
        myDockOnWatched = (id) => {
            if (id !== rec.id) return;
            rec.watched = true;
            renderRecordingMarkButton(watchedButton, "watched", true);
        };
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
 * カードにはアーカイブと同じ印のボタンを出す（見たかどうかを見て次を選び、その場で印も付けられるように）。
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
        grid.replaceChildren(...others.map((r) => buildVideoCard(r, null, true, null, myToggleMark, myWatchPath)));
        bindDatetimeCells(grid);
    } catch (e) {
        if (grid.isConnected) showError(errorMessage(e));
    }
}

/**
 * 購読しているチャンネル 1 件（GET /api/my/channels の要素）。画面で使う項目だけを書く。
 *
 * @typedef {object} MySubscribedChannel
 * @property {number} id チャンネルの主キー。録画の希望の変更・解除・アーカイブの絞り込みに使う
 * @property {string} platformLabel プラットフォームの表示名
 * @property {string} channelName チャンネル名
 * @property {boolean} currentlyLive 最後に確かめた時点で配信中か
 * @property {string|null} lastCheckedAt 最後に確かめた時刻。一度も確かめていなければ null
 * @property {boolean} detectionFailing 配信状態を判定できていない状態が続いているか
 * @property {string} subscribedAt 購読した時刻
 * @property {boolean} recordEnabled 自分が自動録画を希望しているか
 * @property {string|null} recordTitleKeywords 自分の絞り込みキーワード。未設定なら null
 * @property {boolean} notifyEnabled 配信開始を自分の Webhook へ通知するか
 * @property {string|null} channelUrl チャンネルページの URL。組み立てられなければ null
 * @property {string|null} channelIconUrl アイコンの URL。まだ読み取れていなければ null
 * @property {number} recordingCount 再生できる録画の件数
 * @property {boolean} recordingNow そのチャンネルの配信を今録画しているか
 */

/**
 * 購読しているチャンネルの状態の表示。使うのはこの画面だけなので common.js へは移さない。
 * 「配信していない」と「判定できなかった」は必ず分ける。まとめると、検知が壊れていても平常運転に見える。
 *
 * @param {MySubscribedChannel} ch 購読しているチャンネル
 * @returns {string} セルへ差し込む HTML
 */
function myChannelStateLabel(ch) {
    if (ch.detectionFailing) {
        return statusLamp("failed", "確認できません",
            "配信状態を判定できていません。しばらくしても直らない場合は管理者に連絡してください。");
    }
    if (!ch.lastCheckedAt) return statusLamp("unknown", "未確認", "まだ一度も確認していません");
    if (ch.currentlyLive && ch.recordingNow) {
        return statusLamp("live", "配信中・録画中", "最終確認時点で配信中で、録画しています。終わるとアーカイブに並びます");
    }
    return ch.currentlyLive
        ? statusLamp("live", "配信中", "最終確認時点で配信中です")
        : statusLamp("idle", "配信していません");
}

/**
 * マイチャンネルの表の 1 行。録画の希望・キーワード・通知は押した行だけを書き換え、一覧は読み直さない
 * （apiPut は応答を読まないので、応答の録画数 0 で表が上書きされることもない）。
 *
 * @param {MySubscribedChannel} ch 購読しているチャンネル
 * @param {() => void} reload 一覧を読み直す。解除の後に呼ぶ
 * @returns {HTMLTableRowElement} 行
 */
function myChannelRow(ch, reload) {
    const tr = document.createElement("tr");
    // アイコンは名前のセルに並べる（配信予定の表と同じ）。名前の並べ替えはセルの文字で比べる（アイコンは文字を持たない）。
    // 配信元は管理画面の表と同じく、プラットフォーム名をチャンネルページへのリンクにする（プラットフォームと
    // リンクで列を分けると表が横に長くなり、よく使う画面の幅でも解除のボタンが横にはみ出すため）。
    // 購読した日は日付だけを出し、時刻は title に回す（時刻まで出すと表が横に長くなり、よく使う幅で解除がはみ出す）。
    // 並べ替えは秒までの値で比べる（数字の並びを数として比べるため、秒未満の桁数が行ごとに違うと正しく並ばない）
    const subscribed = escapeHtml(formatDateTimeSimple(ch.subscribedAt));
    tr.innerHTML = `<td><span class="channelWithIcon">${channelIcon(ch.channelIconUrl)}<a href="/my/archive?channelId=${ch.id}">${escapeHtml(ch.channelName)}</a></span></td>
        <td>${externalLink(ch.platformLabel, ch.channelUrl)}</td>
        <td>${myChannelStateLabel(ch)}</td>
        <td data-sort-value="${ch.recordingCount}">${ch.recordingCount}件</td>
        <td data-sort-value="${subscribed}" title="${subscribed}">${subscribed.slice(0, 10)}</td>
        <td><button type="button" class="recordBtn" aria-pressed="${ch.recordEnabled}">自動録画: ${ch.recordEnabled ? "オン" : "オフ"}</button></td>
        <td class="titleFilterCell">${titleFilterButton(ch.recordTitleKeywords || "")}</td>
        <td><button type="button" class="notifyBtn" aria-pressed="${ch.notifyEnabled}">通知: ${ch.notifyEnabled ? "オン" : "オフ"}</button></td>
        <td><button type="button" class="unsubscribeBtn">解除</button></td>`;

    const recordBtn = /** @type {HTMLButtonElement} */ (query(".recordBtn", tr));
    recordBtn.addEventListener("click", async () => {
        const next = !ch.recordEnabled;
        recordBtn.disabled = true;
        try {
            // 変えるのは自分の希望だけ。キーワードは今の値をそのまま送る
            await apiPut(`/api/my/channels/${ch.id}/record`, { enabled: next, titleKeywords: ch.recordTitleKeywords || "" });
            ch.recordEnabled = next;
            recordBtn.textContent = `自動録画: ${next ? "オン" : "オフ"}`;
            recordBtn.setAttribute("aria-pressed", String(next));
            if (recordBtn.isConnected) clearError();
            showToast(next ? "この配信者の録画を始めます" : "この配信者の録画をやめます");
        } catch (e) {
            if (recordBtn.isConnected) showError(errorMessage(e));
        } finally {
            recordBtn.disabled = false;
        }
    });

    // 通知は購読者ごとに自分の Webhook へ送るので、録画と違って他の人の設定に左右されない
    const notifyBtn = /** @type {HTMLButtonElement} */ (query(".notifyBtn", tr));
    notifyBtn.addEventListener("click", async () => {
        const next = !ch.notifyEnabled;
        notifyBtn.disabled = true;
        try {
            await apiPut(`/api/my/channels/${ch.id}/notify`, { enabled: next });
            ch.notifyEnabled = next;
            notifyBtn.textContent = `通知: ${next ? "オン" : "オフ"}`;
            notifyBtn.setAttribute("aria-pressed", String(next));
            if (notifyBtn.isConnected) clearError();
            showToast(next ? "通知をオンにしました" : "通知をオフにしました");
        } catch (e) {
            if (notifyBtn.isConnected) showError(errorMessage(e));
        } finally {
            notifyBtn.disabled = false;
        }
    });

    const filterCell = /** @type {HTMLTableCellElement} */ (query(".titleFilterCell", tr));
    // 保存先は自分の購読で、ほかの人の設定には影響しない。保存できたら行の値も変える
    // （変えないと、続けて録画の希望を切り替えたときに古いキーワードで上書きする）
    filterCell.addEventListener("click", () => editTitleFilterCell(filterCell, ch.recordTitleKeywords || "",
        async (value) => {
            await apiPut(`/api/my/channels/${ch.id}/record`, { enabled: ch.recordEnabled, titleKeywords: value });
            ch.recordTitleKeywords = value;
        }));

    query(".unsubscribeBtn", tr).addEventListener("click", async () => {
        // 「解除」が何をするのかを明示する（旧画面と同じ文言）。管理画面の「削除」と取り違えられると困る
        if (!confirm(`${ch.channelName} の購読を解除しますか？\n解除されるのはあなたの購読だけで、チャンネルの監視そのものは続きます。`)) return;
        try {
            await apiDelete(`/api/my/channels/${ch.id}`);
            if (tr.isConnected) clearError();
            showToast(`${ch.channelName} の購読を解除しました`, "danger");
            reload();
        } catch (e) {
            if (tr.isConnected) showError(errorMessage(e));
        }
    });
    return tr;
}

/**
 * マイチャンネル。購読しているチャンネルの表と、購読の追加・解除・録画の希望・キーワード。
 *
 * 並べ替えは全件を持っている表の中だけで行い（common.js の makeTableSortable）、選んだ列と向きを URL の sort に残す。
 * 残すのは replaceState にする。見出しを押すたびに履歴を積むと、「戻る」で並べ替えを 1 つずつ戻ることになり、
 * そのたびにルーターが画面ごと読み直すため。
 *
 * 購読の追加の応答は録画数が 0 で返る（件数を数えない作り）。既に録画のあるチャンネルが 0 件に化けないよう、
 * 追加の後は応答を表に使わず一覧を読み直す。
 * @type {MyView}
 */
const myChannelsView = {
    title: "マイチャンネル",
    nav: "/my/channels",
    render(root, match, params) {
        root.innerHTML = `<h1>マイチャンネル</h1>
            <p class="pageDescription">購読しているチャンネルと、自分の録画の希望。</p>
            <p id="error" class="error" role="alert" style="display:none;"></p>
            <h2>チャンネルを追加</h2>
            <p class="muted">チャンネルページの URL をそのまま貼り付けられます（例: https://www.youtube.com/@foo、https://www.twitch.tv/foo）。配信が始まると、この一覧の状態が「配信中」に変わります。</p>
            <form id="addForm" class="inline">
              <select id="addPlatform" aria-label="配信プラットフォーム"></select>
              <input type="text" id="addChannelId" placeholder="URL / @ハンドル / チャンネルID" required aria-label="チャンネルURL・ハンドル・ID">
              <input type="text" id="addChannelName" placeholder="表示名（省略可）" aria-label="表示名">
              <label><input type="checkbox" id="addRecordEnabled"> 配信を自動で録画する</label>
              <button type="submit">追加</button>
            </form>
            <h2>購読しているチャンネル</h2>
            <p class="muted">
              自動録画をオンにすると、条件に合う配信が自動で保存されます。保存された録画は<a href="/my/archive">アーカイブ</a>から見られます。<br>
              設定はあなた専用です。ただし<strong>同じチャンネルを他の人も録画している場合、
              あなたがオフにしても録画自体は続きます</strong>（保存先が共通のため）。
            </p>
            <div class="table-scroll">
              <table>
                <thead><tr>
                  <th data-sort="text" data-key="name">チャンネル名</th><th>配信元</th><th>状態</th>
                  <th data-sort="number" data-key="recordings">録画数</th><th data-sort="text" data-key="subscribed">購読した日</th>
                  <th>自動録画</th><th>キーワード</th><th>通知</th><th></th>
                </tr></thead>
                <tbody></tbody>
              </table>
            </div>
            <div class="channelEmpty"></div>`;
        const table = /** @type {HTMLTableElement} */ (query("table", root));
        const tbody = query("tbody", table);
        const empty = query(".channelEmpty", root);

        makeTableSortable(table);
        const sortHeaders = /** @type {HTMLTableCellElement[]} */ ([...table.querySelectorAll("thead th[data-key]")]);
        // 並べ替えの状態は makeTableSortable と同じく表の data-sort-column・data-sort-direction に持つ。
        // URL の sort（例: recordings-desc）が読めなければ、購読した日の新しい順（API の並びと同じ）にする
        const [key, direction] = (params.get("sort") ?? "").split("-");
        const fromUrl = sortHeaders.find((th) => th.dataset.key === key && (direction === "asc" || direction === "desc"));
        const initial = fromUrl ?? sortHeaders.find((th) => th.dataset.key === "subscribed");
        table.dataset.sortColumn = String(initial?.cellIndex);
        table.dataset.sortDirection = fromUrl && direction === "asc" ? "ascending" : "descending";
        query("thead", table).addEventListener("click", (event) => {
            // 見出しのボタン（makeTableSortable が作る）の処理が先に済み、data-sort-* は押した後の状態になっている
            if (!(event.target instanceof Element && event.target.closest(".sortButton"))) return;
            const th = sortHeaders.find((h) => String(h.cellIndex) === table.dataset.sortColumn);
            const url = new URL(location.href);
            url.searchParams.set("sort", `${th?.dataset.key}-${table.dataset.sortDirection === "descending" ? "desc" : "asc"}`);
            history.replaceState(null, "", url);
        });

        /** 読み込みの番号。追加・解除を続けたとき、遅れて届いた古い一覧で上書きしないため。 */
        let request = 0;
        const load = async () => {
            const current = ++request;
            setBusy(table, true);
            try {
                /** @type {MySubscribedChannel[]} */
                const channels = await apiGet("/api/my/channels");
                if (current !== request || !table.isConnected) return;
                tbody.replaceChildren(...channels.map((ch) => myChannelRow(ch, load)));
                // 読み直すたびに行を作り直すので、選ばれている並び順をここで掛け直す
                applyTableSort(table);
                // 0 件と読み込みの失敗は利用者にとって別の意味なので、はっきり分けて伝える
                query(".table-scroll", root).hidden = channels.length === 0;
                empty.innerHTML = channels.length === 0
                    ? emptyState("まだチャンネルを追加していません", "上の入力欄にチャンネルのURLを貼ると、配信の開始を見張ります")
                    : "";
            } catch (e) {
                if (current === request && table.isConnected) showError(errorMessage(e));
            } finally {
                if (current === request) setBusy(table, false);
            }
        };

        const form = formEl("addForm");
        const platform = selectEl("addPlatform");
        const channelInput = inputEl("addChannelId");
        const channelName = inputEl("addChannelName");
        const recordEnabled = inputEl("addRecordEnabled");
        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            const submit = /** @type {HTMLButtonElement} */ (query("button[type=submit]", form));
            submit.disabled = true;
            try {
                const added = await apiPost("/api/my/channels", {
                    platform: platform.value, channelInput: channelInput.value.trim(), channelName: channelName.value.trim(),
                    recordEnabled: recordEnabled.checked });
                if (!form.isConnected) return;
                clearError();
                showToast(recordEnabled.checked
                    ? `${added.channelName} を追加しました。配信を自動で録画します`
                    : `${added.channelName} を追加しました。録画するには表の「自動録画: オフ」を押してオンにしてください`);
                channelInput.value = "";
                channelName.value = "";
                load();
            } catch (e) {
                if (form.isConnected) showError(errorMessage(e));
            } finally {
                submit.disabled = false;
            }
        });

        // 選択肢はサーバーが返したものだけを使う（決め打ちすると、対応するプラットフォームが増えるたびに画面の修正が要る）
        apiGet("/api/platforms")
            .then((/** @type {Array<{name: string, label: string}>} */ options) =>
                platform.replaceChildren(...options.map((p) => new Option(p.label, p.name))))
            .catch((e) => { if (platform.isConnected) showError(errorMessage(e)); });
        load();
    },
};

/**
 * 通知の設定。自分の Discord の Webhook の登録・解除・テスト送信（#177）。登録すると、購読しているチャンネルの
 * 配信開始がその Webhook へ届く（#149）。
 *
 * 登録した URL は API が返さない（URL を知っていれば誰でもそのチャンネルへ投稿できるため）。管理画面の設定と同じく、
 * 入力欄は保存のたびに空へ戻し、登録の有無は「設定済み」「未設定」だけで示す。
 *
 * 入力欄は管理画面と違って type="password" にしない。ブラウザのパスワード管理が、保存してあるログインのパスワードを
 * この欄へ自動で入れたり、Webhook の URL をパスワードとして保存するよう勧めたりすることがあるため。
 * autocomplete="off" は、入力の履歴に URL を残させないため。
 * @type {MyView}
 */
const myNotificationSettingsView = {
    title: "通知の設定",
    nav: "/my/settings/notifications",
    render(root) {
        root.innerHTML = `<h1>通知の設定</h1>
            <p class="pageDescription">購読しているチャンネルの配信が始まると、登録した Discord の Webhook へ知らせます（マイチャンネルで通知をオンにしているチャンネルが対象）。通知が要らなければ登録しなくてかまいません。</p>
            <p id="error" class="error" role="alert" style="display:none;"></p>
            <h2>Discord の Webhook</h2>
            <p>状態: <strong id="webhookState">読み込み中...</strong></p>
            <p class="muted" id="webhookHistory"></p>
            <p class="error" role="alert" id="webhookWarning" hidden></p>
            <form id="webhookForm" class="inline">
              <label>Webhook の URL <input type="text" id="webhookUrl" size="60" required autocomplete="off" placeholder="https://discord.com/api/webhooks/..."></label>
              <button type="submit" id="webhookSaveBtn">保存</button>
            </form>
            <p class="muted">
              Discord で「ウェブフック URL をコピー」した URL を、そのまま貼り付けてください（https://discord.com/api/webhooks/ で始まります）。<br>
              保存した URL は、この画面にも表示しません。
            </p>
            <div class="inline" id="webhookActions" hidden>
              <button type="button" id="webhookTestBtn" disabled>テスト送信</button>
              <button type="button" id="webhookRemoveBtn" class="removeBtn" disabled>解除</button>
            </div>
            <h2>Webhook の作り方</h2>
            <p class="muted">
              Discord で、通知を受け取りたいサーバーの「サーバー設定」→「連携サービス」→「ウェブフック」を開き、「新しいウェブフック」を作ります。<br>
              送り先のチャンネルを選んで「ウェブフック URL をコピー」を押し、上の欄に貼り付けて保存します。保存したら「テスト送信」で届くか確かめられます。<br>
              作るには、そのサーバーの「ウェブフックの管理」の権限が要ります（自分で作ったサーバーなら持っています）。
            </p>`;
        const state = el("webhookState");
        const testBtn = buttonEl("webhookTestBtn");
        const removeBtn = buttonEl("webhookRemoveBtn");
        const saveBtn = buttonEl("webhookSaveBtn");
        const input = inputEl("webhookUrl");
        const history = el("webhookHistory");
        const warning = el("webhookWarning");
        const actions = el("webhookActions");
        /**
         * 設定の状態（GET・PUT・DELETE の応答）。Webhook の URL は含まない。
         * failing（最近の通知が届いていないか）はサーバーが判定する。条件を画面ごとにずらさないため。
         * @typedef {{configured: boolean, lastDeliveredAt: string|null, lastFailedAt: string|null, failing: boolean}} NotificationSettings
         */
        /** 設定の状態。読み込めるまでは null */
        /** @type {NotificationSettings|null} */
        let settings = null;
        /**
         * 表示を configured に合わせる。テスト送信・解除は登録した Webhook への操作なので、登録しているときだけ出す
         * （未設定のときに押せないボタンを先に見せると、最初にやる保存が下に押し出されるため）
         */
        const showState = () => {
            if (settings !== null) {
                state.textContent = settings.configured ? "設定済み" : "未設定";
                history.textContent = `最後に届けた日時: ${settings.lastDeliveredAt ? formatInstant(settings.lastDeliveredAt) : "まだありません"}`;
                warning.textContent = `最近の通知が届いていません（${formatInstant(settings.lastFailedAt)}）。Discord 側で Webhook かチャンネルが削除された可能性があります。Webhook を作り直して保存し、「テスト送信」で確かめてください。`;
                warning.hidden = !settings.failing;
            }
            testBtn.disabled = removeBtn.disabled = settings?.configured !== true;
            actions.hidden = settings?.configured !== true;
        };
        /**
         * ボタンの操作を行い、失敗はエラー帯に出す。通信の間は押したボタンを止める（続けて押して二重に送らないため）。
         *
         * @param {HTMLButtonElement} button 押されたボタン
         * @param {() => Promise<void>} action 行う操作
         */
        const run = async (button, action) => {
            button.disabled = true;
            try {
                await action();
                // 待つ間に別の画面へ移っていたら、その画面のエラー帯には触らない
                if (button.isConnected) clearError();
            } catch (e) {
                if (button.isConnected) showError(errorMessage(e));
            } finally {
                saveBtn.disabled = false;
                showState();
            }
        };

        formEl("webhookForm").addEventListener("submit", (event) => {
            event.preventDefault();
            // 不正な URL の 400 は、受け付ける形を書いたサーバーの文言をそのまま出す
            run(saveBtn, async () => {
                await apiPut("/api/my/notification-settings", { webhookUrl: input.value });
                input.value = "";
                // 登録し直すと過去の失敗が消えるので、警告を消すために登録後の状態を読み直す（apiPut は応答の本文を返さない）
                settings = await apiGet("/api/my/notification-settings");
                showToast("Webhook を保存しました");
            });
        });
        testBtn.addEventListener("click", () => run(testBtn, async () => {
            // Discord に届かなかった理由（502）は Discord の応答のままなので、何の失敗かを頭に足す
            await apiPost("/api/my/notification-settings/test", {}).catch((e) => {
                throw new Error(`テストの通知を送れませんでした: ${errorMessage(e)}`);
            });
            showToast("テストの通知を送りました。Discord に届いたか確かめてください");
        }));
        removeBtn.addEventListener("click", () => {
            if (!confirm("Discord の Webhook の登録を解除しますか？\n解除すると、配信開始の通知が届かなくなります。")) return;
            run(removeBtn, async () => {
                settings = await apiDelete("/api/my/notification-settings");
                showToast("Webhook の登録を解除しました", "danger");
            });
        });

        apiGet("/api/my/notification-settings")
            .then((/** @type {NotificationSettings} */ loaded) => {
                // 読み込みを待つ間に保存・解除が済んでいれば、そちらの方が新しい
                settings ??= loaded;
                showState();
            })
            .catch((e) => {
                if (!state.isConnected) return;
                state.textContent = "読み込めませんでした";
                showError(errorMessage(e));
            });
    },
};

/**
 * アカウントの設定。自分のパスワードを変える（#322。API は #321）。
 *
 * パスワードは見せないので、入力欄は成功・失敗にかかわらず送信後に空へ戻す。
 * 確認欄はサーバーへ送らない。打ち間違いに気づかないまま変えると本人がログインできなくなるため、画面だけで確かめる。
 * @type {MyView}
 */
const myAccountSettingsView = {
    title: "アカウント",
    nav: "/my/settings/account",
    render(root) {
        root.innerHTML = `<h1>アカウント</h1>
            <p class="pageDescription">ログインのパスワードを変えます。変えると、ほかの端末やブラウザではログインし直しが必要になります。</p>
            <p id="error" class="error" role="alert" style="display:none;"></p>
            <h2>パスワードの変更</h2>
            <form id="passwordForm">
              <p><label>今のパスワード<br>
                <input type="password" id="currentPassword" autocomplete="current-password" required size="28"></label></p>
              <p><label>新しいパスワード（8 文字以上）<br>
                <input type="password" id="newPassword" autocomplete="new-password" required minlength="8" size="28"></label></p>
              <p><label>新しいパスワード（確認）<br>
                <input type="password" id="newPasswordConfirm" autocomplete="new-password" required minlength="8" size="28"></label></p>
              <p><button type="submit">変更する</button></p>
            </form>`;
        const form = formEl("passwordForm");
        const current = inputEl("currentPassword");
        const next = inputEl("newPassword");
        const confirmInput = inputEl("newPasswordConfirm");
        const button = /** @type {HTMLButtonElement} */ (query("button[type=submit]", form));

        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            if (next.value !== confirmInput.value) {
                showError("新しいパスワードが一致しません");
                return;
            }
            button.disabled = true;
            try {
                await apiPut("/api/my/password", { currentPassword: current.value, newPassword: next.value });
                // 待つ間に別の画面へ移っていたら、その画面のエラー帯には触らない
                if (form.isConnected) {
                    clearError();
                    showToast("パスワードを変更しました");
                }
            } catch (e) {
                // 今のパスワードの誤りなどの 400 は、サーバーの文言をそのまま出す
                if (form.isConnected) showError(errorMessage(e));
            } finally {
                button.disabled = false;
                current.value = next.value = confirmInput.value = "";
            }
        });
    },
};

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
 * 最後の行は、どれにも合わない /my 配下（URL の打ち間違いなど）。
 * @type {Array<[RegExp, MyView]>}
 */
const myRoutes = [
    [/^\/my\/?$/, myTopView],
    [/^\/my\/videos\/?$/, myVideosView],
    [/^\/my\/archive\/?$/, myArchiveView],
    [/^\/my\/watch\/(\d+)\/?$/, myWatchView],
    [/^\/my\/channels\/?$/, myChannelsView],
    [/^\/my\/settings\/notifications\/?$/, myNotificationSettingsView],
    [/^\/my\/settings\/account\/?$/, myAccountSettingsView],
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

/**
 * 今の URL の画面を描く。リンクの横取り・戻る／進む・起動時のすべてがここを通る。
 * 切り替えたら新しい画面の見出しへフォーカスを移す。移さないと、スクリーンリーダーには画面が
 * 変わったことが伝わらず、キーボードでは Tab がメニューの続きへ進み、本文のリンクから移ったときは
 * フォーカスしていた要素が消えて body に落ちる（WCAG 2.4.3 / 4.1.3）。
 *
 * @param {{ initial?: boolean }} [options] initial は最初の表示。ページを開いた直後はスキップリンクから
 *   始められるよう、フォーカスを動かさない
 */
function myRender({ initial = false } = {}) {
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
        if (!initial) {
            // 見出しへ移すと画面名が読み上げられる。見出しの無い画面は描き替え先（tabindex=-1 付き）へ
            const heading = root.querySelector("h1");
            const target = heading instanceof HTMLElement ? heading : root;
            if (target === heading) heading.tabIndex = -1;
            target.focus({ preventScroll: true });
        }
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
window.addEventListener("popstate", () => myRender());

myDropContinueParam();
myDockInit();
myRender({ initial: true });
