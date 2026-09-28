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
 * 読み込んだ録画の再生回数をもう数えたか。一時停止から戻したときの {@code play} で数え直さないため、
 * 録画を読み込むたびに戻す（{@link myDockWatchedSent} と同じ考え方）。
 */
let myDockPlayCounted = false;

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
    myDockPlayCounted = false;
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
        if (!myDockRecording) return;
        // 回数が増えなくても再生には関係ないので、視聴済みの印と同じく失敗しても何も出さない
        if (!myDockPlayCounted) {
            myDockPlayCounted = true;
            apiPost(`/api/my/recordings/${myDockRecording.id}/play`, {}).catch(() => {});
        }
        if (myDockWatchedSent) return;
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
 *   一覧のように読み込んでから中身を描く画面は、最初の中身を描き終えたら解決する Promise を返す。「戻る」「進む」で開き直したとき、
 *   ルーターはその解決を待ってからスクロール位置を戻す（待たずに戻すと、中身の無い短いページで先頭付近に止まる）。
 *   Promise を返さない画面は、今までどおり先頭から出す。読み込みの失敗は画面の中で知らせ、Promise は失敗させずに解決する
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
         * 動画の要約は common.js の onlineVideosRenderKey で作る（lastObservedAt を比べる値から外す理由もそこに書いてある）。
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
                const key = JSON.stringify([onlineVideosRenderKey(page.content), streams]);
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
 * 画面ごと描き直し、描き直した画面が URL から条件を戻す（render は Promise を返さないので、「戻る」「進む」でも先頭から出す。
 * ルーターが history.state に残す値は画面を離れるときに書き直すので、bindOnlineVideoSections が null で書いても困らない）。
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
 * アーカイブの表の 1 行。列は管理画面の表と同じ並びで、削除だけが無い（録画は利用者どうしで共有しているため、
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
 * アーカイブ。管理画面のアーカイブと同じ検索一式（common.js の bindRecordingSearch）で、この端末にあるすべての録画
 * （購読していないチャンネルの録画も含む。#419）を探す。録画中・失敗の録画は開いても見られないので、API に playableOnly=true を
 * 常に付け、状態の絞り込みは置かない。
 *
 * 条件は URL（/my/archive?...）に残す。「戻る」「進む」はルーターが拾って画面ごと描き直し、描き直した画面が
 * URL から条件を戻す。ページ送り・条件の変更で履歴を積む前には今のスクロール位置を残し（writeUrl）、「戻る」で前のページの
 * 同じ位置へ戻す。render は一覧を描き終えたら解決する Promise を返す（ルーターがその後で位置を戻す）。
 * @type {MyView}
 */
const myArchiveView = {
    title: "アーカイブ",
    nav: "/my/archive",
    render(root) {
        root.innerHTML = `<h1>アーカイブ</h1>
            <p class="pageDescription">この端末に録画したすべての録画。再生中にほかの画面へ移っても、画面下で再生を続けます。</p>
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
            // ページ送り・条件の変更で履歴を積む前に今の位置を残し、「戻る」で前のページの同じ位置へ戻す。
            // 読み込み直後の整え（replace）は、今の項目に残した値（位置・from）を消さない
            writeUrl: (url, replace) => {
                if (replace) {
                    history.replaceState(history.state, "", url);
                } else {
                    myRememberScroll();
                    history.pushState(null, "", url);
                }
            },
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
        return Promise.all([
            // 購読しているチャンネルだけだと、一覧に出ている購読外の録画のチャンネルで絞り込めない
            addOptions("channelId", "/api/my/recordings/channels", (ch) => new Option(ch.channelName, String(ch.id))),
            // 件数を添えるのは、選ぶ前にどれだけ当たるか分かるようにするため（管理画面と同じ）
            addOptions("genre", "/api/my/recordings/genres", (g) => new Option(`${g.genre}（${g.count}）`, g.genre)),
        ]).then(() => {
            if (!grid.isConnected) return;
            search.restore();
            return load();
        });
    },
};

/** 再生画面の耳キスの欄が動画に付けた処理を外す関数。再生画面を出していない間は null。 */
/** @type {(() => void)|null} */
let myWatchStopSoundMarks = null;

/**
 * 再生画面。録画をドックに読み込み、下に視聴済み・お気に入りのボタン、端末に保存のリンク、耳キスの欄、
 * 詳細と同じチャンネルの録画を出す。
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
            // 無い録画（削除された）は 404 で返る。ほかの失敗と分けて伝えるため、
            // 状態コードを見られるよう apiGet を使わない
            const res = await authenticatedFetch(`/api/my/recordings/${match[1]}`);
            if (res.status === 404) {
                if (heading.isConnected) {
                    root.innerHTML = `<h1>この録画は見られません</h1>
                        <p class="pageDescription">削除されたか、存在しない録画です。</p>
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
            // 印を付ける・飛ぶにはドックの動画が要るので、耳キスの欄も再生できる録画にだけ出す
            const soundMarks = document.createElement("section");
            query(".table-scroll", root).before(soundMarks);
            myWatchStopSoundMarks = myBindSoundMarks(rec, soundMarks);
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
            // 動画 ID から URL を推測しない。Twitch の録画は URL が null で、ID を文字で出す
            ["元の配信", externalLink(rec.videoId, rec.videoUrl)],
            ["録画開始", datetimeCell(rec.startedAt)],
            ["録画終了", datetimeCell(rec.completedAt)],
            ["再生時間", formatDuration(rec.durationSeconds)],
            ["ファイルサイズ", formatFileSize(rec.fileSizeBytes)],
        ].map(([label, value]) => `<tr><th>${label}</th><td>${value}</td></tr>`).join("");
        bindDatetimeCells(root);
        myLoadRelated(rec, query(".videoGrid", root));
    },
    leave() {
        myWatchStopSoundMarks?.();
        myWatchStopSoundMarks = null;
        myDockMinimize();
    },
};

/**
 * 同じチャンネルのほかの録画を並べる。1 本見終わった後に一覧へ戻らず次を選べるようにするため。
 * カードにはアーカイブと同じ印のボタンを出す（見たかどうかを見て次を選び、その場で印も付けられるように）。
 *
 * @param {Recording} rec 再生画面の録画
 * @param {HTMLElement} grid 並べる先
 */
async function myLoadRelated(rec, grid) {
    // URL を貼って取得した録画はチャンネルに紐づかず、channelId が無い（API も絞り込めない。管理者の player.js と同じ）
    if (rec.channelId == null) {
        grid.innerHTML = '<p class="muted">この録画はチャンネルに紐づいていないため、関連する録画はありません</p>';
        return;
    }
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
 * 録画に付いた音の印 1 件（GET /api/my/recordings/{id}/sound-marks の要素）。画面で使う項目だけを書く。
 *
 * @typedef {object} MySoundMark
 * @property {number} id 印の主キー。消すときと、二度押しで前の印が返ったことを見分けるときに使う
 * @property {number} positionMs 録画の先頭からの位置（ミリ秒）
 * @property {boolean} mine ログイン中の利用者が付けた印か。消せるのは本人の印だけ
 */

/**
 * 検出器が録画に自動で付けた候補 1 件（GET /api/my/recordings/{id}/sound-candidates の candidates の要素）。
 * 画面で使う項目だけを書く。
 *
 * @typedef {object} MySoundCandidate
 * @property {number} id 候補の主キー。答えを送るときに使う
 * @property {number} positionMs 録画の先頭からの位置（ミリ秒）
 * @property {"CONFIRMED"|"REJECTED"|null} verdict 答え（耳キス・ちがう）。null ならまだ誰も答えていない
 * @property {boolean} reviewedByMe 最後に答えたのがログイン中の利用者か。取り消しを出すのは自分の答えだけにする
 *   （印の「消す」を本人の印にだけ出すのと同じ考え方）
 */

/**
 * 印・候補 1 か所の前後の区間（秒）。前後の移動で飛ぶ先は、区間の先頭にする。
 *
 * @typedef {object} MySoundRange
 * @property {number} start 区間の先頭（秒）
 * @property {number} end 区間の終わり（秒）
 */

/**
 * 人の印の区間。印の 3 秒前から 4 秒後までにする。
 * 印は音を聞いてから押すので、音より少し後ろに付く。印ちょうどへ飛ぶと音が過ぎた後から流れるため、
 * 少し手前から流して、飛んだ先で音をもう一度聞けるようにする。後ろより前を広く取るのも、音が印より前にあるため。
 *
 * @param {MySoundMark} mark 印
 * @returns {MySoundRange} 区間
 */
function mySoundMarkRange(mark) {
    return { start: Math.max(0, mark.positionMs / 1000 - 3), end: mark.positionMs / 1000 + 4 };
}

/**
 * 自動の候補の区間。候補の 2 秒前から 2 秒後までにする。
 * 人の印（{@link mySoundMarkRange}）と幅を変えるのは、候補の位置が検出器の見つけた音そのもの（音の山の時点）で、
 * 人の印のように聞いてから押す遅れが無いため。音を真ん中にして前後を同じ幅にし、人の印より短くして、
 * 数の多い候補を 1 件ずつ聞いて答える時間を減らす。
 *
 * @param {MySoundCandidate} candidate 候補
 * @returns {MySoundRange} 区間
 */
function mySoundCandidateRange(candidate) {
    return { start: Math.max(0, candidate.positionMs / 1000 - 2), end: candidate.positionMs / 1000 + 2 };
}

/**
 * 「印の所だけ再生」で流す区間（秒）。重なる区間と、間が 2 秒以下の区間は 1 つにまとめる。
 * 2 秒ほどを飛ばしても聞く時間はほとんど減らず、飛ぶたびに音が途切れるだけになるため。
 *
 * @param {MySoundRange[]} ranges 印・候補の区間（並びは問わない）
 * @returns {MySoundRange[]} まとめた区間。前から順
 */
function myMergeSoundRanges(ranges) {
    /** @type {MySoundRange[]} */
    const merged = [];
    for (const range of [...ranges].sort((a, b) => a.start - b.start)) {
        const last = merged.at(-1);
        // 人の印と候補で幅が違うので、後から始まる区間が前の区間より先に終わることがある。終わりは遅い方を取る
        if (last && range.start - last.end <= 2) last.end = Math.max(last.end, range.end);
        else merged.push({ ...range });
    }
    return merged;
}

/**
 * 再生画面の耳キスの欄（#459）。聞きながら「ここは耳キス」の印を付け、印の一覧と前後の印へ飛ぶボタンを出す。
 * 印は全員で共有し、消せるのは付けた本人だけ（API も本人の印しか消さない）。
 * 使うのはこの画面だけなので common.js へは移さない（MySubscribedChannel と同じ考え方）。
 *
 * 印を付ける・飛ぶのは、ドックが今この録画を読み込んでいるときだけにする。別の録画を読み込んでいると、
 * その録画の再生位置をこの録画の印として送ってしまうため。
 *
 * 「印の所だけ再生」（#467）は、印の前後の区間（{@link myMergeSoundRanges}）だけを続けて流す。オンの間は、
 * 手で区間の外へ動かしても次の区間へ飛ぶ（「印の所だけ」を守るため。区間の外を聞きたいときはオフにしてもらう）。
 *
 * 検出器が付けた候補（#471）も、人の印と同じ一覧に「自動」として並べ、聞いた人に「耳キス／ちがう」を答えてもらう。
 * 答えは学び直しの正例・負例になる。「ちがう」と答えた候補は、一覧・前後の移動・「印の所だけ再生」から外す。
 * 耳キスでないと分かった所なので、残すと、答えた後も同じ外れへ何度も飛ばされ、耳キスの所を聞く邪魔になるため
 * （候補は外れが多いので、残すと飛ぶ先の多くが外れになる）。答えはサーバーに残るので、学び直しには使える。
 *
 * 「候補を順に確かめる」は、未確認の候補の前後だけを 1 件ずつ流して止め、答えたら次の候補へ進む（答えを速く集めるため）。
 * この流れの間は「印の所だけ再生」をオフにする。どちらも再生位置を動かす（区間の外なら次の区間へ飛ぶ・候補の区間の
 * 終わりで止めて次の候補へ飛ぶ）ので、両方が動くと、片方が飛んだ先で、もう片方がまた飛ばしてぶつかるため。
 *
 * @param {Recording} rec 再生画面の録画
 * @param {HTMLElement} container 欄を描く入れ物
 * @returns {() => void} 動画に付けた処理を外し、「印の所だけ再生」と「候補を順に確かめる」を終える関数。動画要素は
 *   ドックの 1 つを使い回すので、画面を離れるときに呼ぶ（外さないと、ほかの画面のミニプレーヤーでも再生が区間へ飛び続け、候補の区間の終わりで止まる）
 */
function myBindSoundMarks(rec, container) {
    container.innerHTML = `<h2>耳キス <span class="muted soundMarkCount"></span></h2>
        <p class="muted">聞こえたら押してください。声や囁きの「ちゅ」も含みます。印はほかの人とも共有されます。</p>
        <p class="muted soundCandidateState" hidden></p>
        <p class="inline">
            <button type="button" class="soundMarkAdd">ここは耳キス</button>
            <button type="button" class="soundMarkPrev">前の耳キスへ</button>
            <button type="button" class="soundMarkNext">次の耳キスへ</button>
            <button type="button" class="soundMarkOnly" aria-pressed="false">印の所だけ再生</button>
            <button type="button" class="soundReviewStart">候補を順に確かめる</button>
            <span class="muted soundMarkStatus" role="status"></span>
        </p>
        <div class="soundReview" hidden>
            <p class="soundReviewText" role="status"></p>
            <p class="inline soundReviewButtons">
                <button type="button" class="soundReviewBig">耳キス</button>
                <button type="button" class="soundReviewBig">ちがう</button>
                <button type="button" class="soundReviewBig">もう一度</button>
                <button type="button">やめる</button>
            </p>
            <p class="soundReviewLast"><span></span> <button type="button">取り消し</button></p>
        </div>
        <ul></ul>`;
    const video = myDockVideo();
    const addButton = /** @type {HTMLButtonElement} */ (query(".soundMarkAdd", container));
    const prevButton = /** @type {HTMLButtonElement} */ (query(".soundMarkPrev", container));
    const nextButton = /** @type {HTMLButtonElement} */ (query(".soundMarkNext", container));
    const onlyButton = /** @type {HTMLButtonElement} */ (query(".soundMarkOnly", container));
    const reviewButton = /** @type {HTMLButtonElement} */ (query(".soundReviewStart", container));
    const reviewButtons = query(".soundReviewButtons", container);
    const [yesButton, noButton, againButton, stopButton] = reviewButtons.querySelectorAll("button");
    const lastLine = query(".soundReviewLast", container);
    const undoButton = /** @type {HTMLButtonElement} */ (query("button", lastLine));
    const list = query("ul", container);
    const path = `/api/my/recordings/${rec.id}/sound-marks`;
    const candidatePath = `/api/my/recordings/${rec.id}/sound-candidates`;
    /** @type {MySoundMark[]} */
    let marks = [];
    /** @type {MySoundCandidate[]} */
    let candidates = [];
    // 今の版の自動の検出の状態（PENDING・DONE・FAILED）。読み込むまでは null
    /** @type {string|null} */
    let candidateState = null;
    // サーバーの二度押しの判定は「探してから保存」なので、同時に届いた 2 つの要求はどちらも印を作りうる。送信中は押させない
    let sending = false;
    // 「印の所だけ再生」がオンか
    let only = false;
    // 答えを送っている候補の id。送信中は、その候補のボタン（一覧と確かめる欄の両方）を押させない
    /** @type {Set<number>} */
    const answering = new Set();
    // 「候補を順に確かめる」で聞いている候補。確かめていなければ null
    /** @type {MySoundCandidate|null} */
    let reviewing = null;
    // 聞いている候補の区間の終わり（秒）。過ぎたら 1 回だけ止める。止めた後・確かめていないときは null
    /** @type {number|null} */
    let stopAt = null;
    // 確かめる流れを終えたときの知らせ。無ければ空
    let reviewMessage = "";
    // 直前の答え。確かめる欄の「取り消し」で戻せるようにする（「ちがう」の候補は一覧から外れるので、ここでしか戻せない）。
    // verdict が null なら、取り消した後
    /** @type {{candidate: MySoundCandidate, verdict: "CONFIRMED"|"REJECTED"|null}|null} */
    let lastAnswer = null;

    const active = () => myDockRecording?.id === rec.id;
    // 前後の移動と「印の所だけ再生」の対象（人の印と、「ちがう」以外の候補）の区間。前から順
    const spots = () => [...marks.map(mySoundMarkRange),
        ...candidates.filter((c) => c.verdict !== "REJECTED").map(mySoundCandidateRange)].sort((a, b) => a.start - b.start);
    // 前後とも、印の位置ではなく飛ぶ先（区間の先頭）で比べる。印の位置で比べると、飛んだ直後（印の 3 秒前）に「次」を押したとき
    // 同じ印が選ばれ、先へ進めない。飛んだ先にいるときにその印を選び直さないよう、次は 0.5 秒、前は 1 秒の幅を取る。
    // 前の幅が広いのは、印を聞き終えた後に押せば、その印を聞き直せるようにするため（曲の頭出しと同じ）
    const nextSpot = () => spots().find((s) => s.start >= video.currentTime + 0.5);
    const prevSpot = () => spots().filter((s) => s.start <= video.currentTime - 1).at(-1);
    /** @param {boolean} on オンにするか */
    const setOnly = (on) => {
        only = on;
        onlyButton.setAttribute("aria-pressed", String(on));
    };
    const refresh = () => {
        // ドックが閉じた・別の録画を読み込んだ（どちらも emptied で来る）ときは、この録画を流せないので、「印の所だけ再生」も
        // 確かめる流れも終える。印も候補も無くなったときも、流す区間が無いので「印の所だけ再生」をオフにする
        if (!active() || !spots().length) setOnly(false);
        if (!active() && reviewing) endReview("");
        addButton.disabled = sending || !active();
        prevButton.disabled = !active() || !prevSpot();
        nextButton.disabled = !active() || !nextSpot();
        onlyButton.disabled = !active() || !spots().length || reviewing !== null;
        reviewButton.disabled = !active() || reviewing !== null || !candidates.some((c) => c.verdict === null);
    };
    // 区間の中かは、先頭の 0.1 秒前から見る。先頭へ飛んだ直後の位置が先頭よりわずかでも前に出ると、区間の外とみなして
    // 先頭へ飛び直し続け、再生が進まなくなるため。Chrome は先頭ちょうどを返す（確認済み）が、位置を内部の刻み
    // （フレームや時間の単位）に丸めて返すブラウザでは前に出うる。iPhone の Safari では確かめていない
    /** @param {number} time 再生位置（秒） */
    const inRange = (time) => myMergeSoundRanges(spots()).some((r) => r.start - 0.1 <= time && time <= r.end);
    /** @param {number} time 再生位置（秒） */
    const nextRange = (time) => myMergeSoundRanges(spots()).find((r) => r.start > time);
    // オンの間、区間の外にいたら次の区間の先頭へ飛び、次が無ければ止めてオフに戻す。最後の区間が録画の終わりを
    // またぐと、区間の中のまま再生が終わる（その後は timeupdate が来ない）ので、終わったときは区間の外と同じに扱う
    const followRanges = () => {
        const time = video.currentTime;
        if (!only || !active() || (inRange(time) && !video.ended)) return;
        const next = nextRange(time);
        if (next) {
            video.currentTime = next.start;
            return;
        }
        video.pause();
        setOnly(false);
        query(".soundMarkStatus", container).textContent = "最後の印まで再生しました";
    };
    /** @param {MySoundRange|undefined} spot 飛ぶ先 */
    const seek = (spot) => {
        if (spot && active()) video.currentTime = spot.start;
    };
    /**
     * 次に確かめる候補。time より後ろの最初の未確認の候補にし、無ければ先頭へ戻って最初の未確認の候補にする。
     * 途中から始めたときに、前の方の候補を残したまま「もうありません」で終わらないようにするため
     * @param {number} time 位置（秒）
     */
    const nextUnreviewed = (time) => {
        const unreviewed = candidates.filter((c) => c.verdict === null);
        return unreviewed.find((c) => c.positionMs / 1000 > time) ?? unreviewed[0];
    };
    /** @param {MySoundCandidate} candidate 聞く候補 */
    const listen = (candidate) => {
        const { start, end } = mySoundCandidateRange(candidate);
        reviewing = candidate;
        stopAt = end;
        video.currentTime = start;
        // 流れなかった（直後の一時停止で中断された等）ことは、流れないこと自体で分かるので何も出さない（ドックの再生ボタンと同じ）
        video.play().catch(() => {});
        render();
    };
    /** @param {string} message 終えたときの知らせ。無ければ空 */
    const endReview = (message) => {
        reviewing = null;
        stopAt = null;
        reviewMessage = message;
        render();
    };
    // 聞いている候補の区間の終わりで止める。止めるのは 1 回だけにする（止めた後に再生を押したら、そのまま流す）。
    // 区間が録画の終わりをまたぐと、終わりに届く前に再生が終わるので、そのときも止め終えたことにする
    const stopAtEnd = () => {
        if (stopAt === null || !active() || (video.currentTime < stopAt && !video.ended)) return;
        stopAt = null;
        video.pause();
    };
    /**
     * 候補に答える（null は取り消し）。答えは全員で共有し、最後の答えが有効になる（API のとおり）。
     * @param {MySoundCandidate} candidate 候補
     * @param {"CONFIRMED"|"REJECTED"|null} verdict 答え
     * @returns {Promise<boolean>} 送れたか
     */
    const answer = async (candidate, verdict) => {
        if (answering.has(candidate.id)) return false;
        answering.add(candidate.id);
        render();
        try {
            await apiPut(`${candidatePath}/${candidate.id}/verdict`, { verdict });
            // 応答の候補と同じ値になる（答えた人は本人。取り消すと答えた人も空になる）ので、手元の候補を書き換える
            candidate.verdict = verdict;
            candidate.reviewedByMe = verdict !== null;
            lastAnswer = { candidate, verdict };
            // 取り消すと未確認の候補が増えるので、終えたときの「もうありません」は古くなる
            reviewMessage = "";
            if (container.isConnected) clearError();
            return true;
        } catch (e) {
            if (container.isConnected) showError(errorMessage(e));
            return false;
        } finally {
            answering.delete(candidate.id);
            render();
        }
    };
    /** @param {"CONFIRMED"|"REJECTED"} verdict 聞いている候補への答え。答えたら次の未確認の候補へ進む */
    const answerAndNext = async (verdict) => {
        const candidate = reviewing;
        // 送っている間に「やめる」・ドックの切り替えで流れが終わっていたら、次へ進まない
        if (!candidate || !(await answer(candidate, verdict)) || reviewing !== candidate) return;
        const next = nextUnreviewed(candidate.positionMs / 1000);
        if (next) listen(next);
        else endReview("未確認の候補はもうありません");
    };

    /** @param {MySoundMark} mark 印 */
    const markItem = (mark) => {
        const time = formatDuration(mark.positionMs / 1000);
        const li = document.createElement("li");
        // 一覧を作り直した後に同じ印へフォーカスを戻すための目印（rememberFocus）
        li.dataset.focusKey = `mark-${mark.id}`;
        li.innerHTML = `<button type="button">${time}</button>`
            + (mark.mine ? ` <button type="button" aria-label="${time} の印を消す">消す</button>` : "");
        const [jumpButton, deleteButton] = li.querySelectorAll("button");
        jumpButton.addEventListener("click", () => seek(mySoundMarkRange(mark)));
        deleteButton?.addEventListener("click", async () => {
            const restoreFocus = rememberFocus(list);
            deleteButton.disabled = true;
            try {
                await apiDelete(`${path}/${mark.id}`);
                marks = marks.filter((m) => m.id !== mark.id);
                render();
                restoreFocus();
                if (container.isConnected) clearError();
            } catch (e) {
                deleteButton.disabled = false;
                if (container.isConnected) showError(errorMessage(e));
            }
        });
        return li;
    };
    /** @param {MySoundCandidate} candidate 一覧に出す候補（「ちがう」以外） */
    const candidateItem = (candidate) => {
        const time = formatDuration(candidate.positionMs / 1000);
        const li = document.createElement("li");
        // 答えた直後の作り直しで、同じ候補（無くなっていれば隣の項目）へフォーカスを戻すための目印（rememberFocus）
        li.dataset.focusKey = `candidate-${candidate.id}`;
        // 同じ名前のボタンが並ぶので、読み上げでどの候補か分かるよう、名前に時刻を入れる（印の「消す」と同じ）
        li.innerHTML = `<button type="button">${time}</button> <span class="muted">自動</span> ` + (candidate.verdict === null
            ? `<button type="button" aria-label="${time} の候補は耳キス">耳キス</button> <button type="button" aria-label="${time} の候補はちがう">ちがう</button>`
            : `確認済み${candidate.reviewedByMe ? ` <button type="button" aria-label="${time} の候補の答えを取り消す">取り消し</button>` : ""}`);
        const [jumpButton, ...answerButtons] = li.querySelectorAll("button");
        jumpButton.addEventListener("click", () => seek(mySoundCandidateRange(candidate)));
        /** @type {("CONFIRMED"|"REJECTED"|null)[]} */
        const verdicts = candidate.verdict === null ? ["CONFIRMED", "REJECTED"] : [null];
        answerButtons.forEach((button, i) => {
            button.disabled = answering.has(candidate.id);
            button.addEventListener("click", () => answer(candidate, verdicts[i]));
        });
        return li;
    };
    const render = () => {
        const unreviewed = candidates.filter((c) => c.verdict === null).length;
        query(".soundMarkCount", container).textContent = `印 ${marks.length} 件`;
        const state = query(".soundCandidateState", container);
        state.hidden = candidateState === null;
        state.textContent = (candidateState === "PENDING" ? "自動の検出を待っています"
            : candidateState === "FAILED" ? "自動の検出に失敗しました"
            : `自動の候補 ${candidates.length} 件（未確認 ${unreviewed} 件）`)
            + (candidates.length ? "。自動の候補は外れが多いので、聞いて答えてください。答えは精度を上げるのに使います。" : "");
        const restoreFocus = rememberFocus(list);
        list.replaceChildren(...[
            ...marks.map((mark) => ({ positionMs: mark.positionMs, li: markItem(mark) })),
            ...candidates.filter((c) => c.verdict !== "REJECTED").map((c) => ({ positionMs: c.positionMs, li: candidateItem(c) })),
        ].sort((a, b) => a.positionMs - b.positionMs).map((item) => item.li));
        restoreFocus();

        query(".soundReviewText", container).textContent = reviewing
            ? `${formatDuration(reviewing.positionMs / 1000)} の候補は耳キスですか？（未確認 ${unreviewed} 件）`
            : reviewMessage;
        reviewButtons.hidden = !reviewing;
        yesButton.disabled = noButton.disabled = reviewing !== null && answering.has(reviewing.id);
        lastLine.hidden = !lastAnswer;
        if (lastAnswer) {
            const time = formatDuration(lastAnswer.candidate.positionMs / 1000);
            query("span", lastLine).textContent = lastAnswer.verdict === null ? `${time} の答えを取り消しました`
                : `${time} を「${lastAnswer.verdict === "CONFIRMED" ? "耳キス" : "ちがう"}」にしました`;
            undoButton.hidden = lastAnswer.verdict === null;
            undoButton.disabled = answering.has(lastAnswer.candidate.id);
        }
        query(".soundReview", container).hidden = !reviewing && !reviewMessage && !lastAnswer;
        refresh();
    };
    /**
     * 受け取った印を一覧に入れる。同じ id の印は置き換える（二度押しでは前の印が返るので、同じ印を 2 つ並べない）。
     * 読み込みにも使うのは、読み込みより先に付けた印が、読み込んだ一覧で消えないようにするため
     * @param {MySoundMark[]} received 読み込んだ・付けた印
     */
    const merge = (received) => {
        marks = [...marks.filter((m) => !received.some((r) => r.id === m.id)), ...received]
            .sort((a, b) => a.positionMs - b.positionMs || a.id - b.id);
        render();
    };

    addButton.addEventListener("click", async () => {
        if (!active()) return;
        sending = true;
        refresh();
        try {
            const mark = await apiPost(path, { kind: "EAR_KISS", positionMs: Math.round(video.currentTime * 1000) });
            merge([mark]);
            query(".soundMarkStatus", container).textContent = `${formatDuration(mark.positionMs / 1000)} に印を付けました`;
            if (container.isConnected) clearError();
        } catch (e) {
            if (container.isConnected) showError(errorMessage(e));
        } finally {
            sending = false;
            refresh();
        }
    });
    prevButton.addEventListener("click", () => seek(prevSpot()));
    nextButton.addEventListener("click", () => seek(nextSpot()));
    onlyButton.addEventListener("click", () => {
        if (only) {
            setOnly(false);
            return;
        }
        if (!active() || !spots().length) return;
        const time = video.currentTime;
        // 最後の区間より後ろでオンにしたときは、最初の区間から流す
        if (!inRange(time)) video.currentTime = (nextRange(time) ?? myMergeSoundRanges(spots())[0]).start;
        setOnly(true);
    });
    reviewButton.addEventListener("click", () => {
        const first = nextUnreviewed(video.currentTime);
        if (!active() || !first) return;
        setOnly(false);
        listen(first);
    });
    yesButton.addEventListener("click", () => answerAndNext("CONFIRMED"));
    noButton.addEventListener("click", () => answerAndNext("REJECTED"));
    againButton.addEventListener("click", () => {
        if (reviewing && active()) listen(reviewing);
    });
    stopButton.addEventListener("click", () => endReview(""));
    undoButton.addEventListener("click", () => {
        if (lastAnswer) answer(lastAnswer.candidate, null);
    });

    refresh();
    // 読み込めなくても、ほかの欄はそのまま使えるので、エラー帯に出すだけにする。印と候補は別々に読み、片方が失敗しても、もう片方は出す
    apiGet(`${path}?kind=EAR_KISS`)
        .then(merge)
        .catch((e) => { if (container.isConnected) showError(errorMessage(e)); });
    apiGet(`${candidatePath}?kind=EAR_KISS`)
        .then((data) => {
            candidateState = data.state;
            candidates = data.candidates;
            render();
        })
        .catch((e) => { if (container.isConnected) showError(errorMessage(e)); });
    // 再生・シークで今の位置が変わると、前後に印があるかも変わる。ドックが別の録画を読み込んだ・閉じたときは
    // emptied だけが来る（位置が 0 のままなら timeupdate は来ない）ので、それでも押せるかを直す
    const events = ["timeupdate", "emptied"];
    for (const type of events) video.addEventListener(type, refresh);
    video.addEventListener("timeupdate", followRanges);
    video.addEventListener("timeupdate", stopAtEnd);
    return () => {
        setOnly(false);
        endReview("");
        for (const type of events) video.removeEventListener(type, refresh);
        video.removeEventListener("timeupdate", followRanges);
        video.removeEventListener("timeupdate", stopAtEnd);
    };
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
    // 解除の後に表を作り直したとき、隣の行へフォーカスを移すための目印（rememberFocus）
    tr.dataset.focusKey = String(ch.id);
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
            <h2 class="subscribedHeading" tabindex="-1">購読しているチャンネル</h2>
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
                const restoreFocus = rememberFocus(tbody, query(".subscribedHeading", root));
                tbody.replaceChildren(...channels.map((ch) => myChannelRow(ch, load)));
                // 読み直すたびに行を作り直すので、選ばれている並び順をここで掛け直す
                applyTableSort(table);
                // 0 件と読み込みの失敗は利用者にとって別の意味なので、はっきり分けて伝える
                query(".table-scroll", root).hidden = channels.length === 0;
                empty.innerHTML = channels.length === 0
                    ? emptyState("まだチャンネルを追加していません", "上の入力欄にチャンネルのURLを貼ると、配信の開始を見張ります")
                    : "";
                restoreFocus();
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
        return load();
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

/**
 * 端末に保存の仕事 1 件（GET /api/my/downloads/device の要素）。画面で使う項目だけを書く。
 *
 * @typedef {object} MyDeviceDownloadJob
 * @property {string} jobId 仕事の ID
 * @property {"RUNNING"|"READY"|"PARTIAL"|"FAILED"} status 状態
 * @property {string|null} title 動画のタイトル。受け付けた直後で動画の情報をまだ取れていなければ null
 * @property {string|null} fileUrl 受け取り先の URL。READY と PARTIAL のときだけ入る
 */

/**
 * 端末に保存の仕事 1 件を一覧の行にする。取得中・受け取れる・失敗の文言は、受け付けた仕事だけを見に行っていた頃の表示と同じ。
 * 途中まで（PARTIAL）の「もう一度『ダウンロードを始める』を押すと取り直します」は、一覧の行が入力欄の URL と
 * 結び付かない（画面を開き直すと入力欄は空になる）ので省いている。
 *
 * @param {MyDeviceDownloadJob} job 仕事
 * @returns {string} li 要素の HTML
 */
function myDeviceDownloadJobHtml(job) {
    const title = escapeHtml(job.title ?? "動画の情報を確認中");
    const href = escapeHtml(job.fileUrl ?? "");
    if (job.status === "READY") {
        return `<li>${title}：受け取れます。<a href="${href}">端末に保存</a></li>`;
    }
    if (job.status === "PARTIAL") {
        return `<li>${title}：途中までしか取得できませんでした（音声が無い、または途中で切れていることがあります）。<a href="${href}">それでも端末に保存</a></li>`;
    }
    if (job.status === "FAILED") {
        return `<li>${title}：失敗しました</li>`;
    }
    return `<li>${title}：取得中…</li>`;
}

/** 端末に保存の一覧を見に行くのを止める関数。見に行っていない間は null。 */
/** @type {(() => void)|null} */
let myDownloadStopPolling = null;

/**
 * 動画ダウンロード（#452。API は #450・#451）。URL を入れて、保存先をサービスか自分の端末から選ぶ。
 *
 * 端末に保存はサーバーが一時的に取得してから渡すため、画面の下に自分の仕事の一覧（GET /api/my/downloads/device）を出し、
 * 取得中の仕事がある間だけ 5 秒ごとに取り直す。受け付けたときの仕事 ID だけを覚えて見に行く作りだと、画面を移る・
 * 読み込み直すと仕事を見失い、終わったファイルを受け取れず、取得中に次を頼むと 409 になるだけだった。
 * 画面を離れたら見に行くのをやめる（離れた画面の表示を書き換えても見えず、通信だけが残る）。戻ってくると一覧から取り直す。
 * 一覧を読めなかったときもやめる（エラーのまま 5 秒ごとに問い合わせ続けないため）。
 * 一覧は aria-live="polite" にして、取得が終わったことを読み上げさせる（前は role="status" の欄に「受け取れます」を
 * 書いて読み上げていた。一覧は中身が変わったときだけ描き直すので、5 秒ごとに読み上げが繰り返されることはない）。
 *
 * 検索の視聴画面の「端末に保存」は、URL の url と destination を付けてこの画面を開く。入力欄と保存先に入れるだけで、送らない
 * （保存先を確かめてから利用者に押してもらう。サービスに保存は全員のアーカイブに入るため）。
 * @type {MyView}
 */
const myDownloadView = {
    title: "動画ダウンロード",
    nav: "/my/download",
    render(root, _match, params) {
        root.innerHTML = `<h1>動画ダウンロード</h1>
            <p class="pageDescription">YouTube・Twitch の動画の URL を入れて、保存先を選んでください。配信中・配信前の URL はダウンロードできません（配信は自動録画を使ってください）。</p>
            <p id="error" class="error" role="alert" style="display:none;"></p>
            <form id="downloadForm">
              <p><label>動画の URL<br><input type="url" id="downloadUrl" required size="60"></label></p>
              <fieldset>
                <legend>保存先</legend>
                <label><input type="radio" name="destination" value="service" checked> サービスに保存（アーカイブに追加され、全員が見られます）</label><br>
                <label><input type="radio" name="destination" value="device"> 自分の端末に保存（サービスには残りません。24 時間以内に受け取ってください）</label>
              </fieldset>
              <p><button type="submit">ダウンロードを始める</button></p>
            </form>
            <p id="downloadStatus" role="status"></p>
            <h2>端末に保存の取得</h2>
            <p class="muted">この画面を離れても取得は続きます。終わったらここから受け取ってください（24 時間を過ぎると消えます。サービスを再起動したときも一覧から消えます）。</p>
            <div id="deviceJobs" aria-live="polite"><p class="muted">読み込み中...</p></div>`;
        const form = formEl("downloadForm");
        const url = inputEl("downloadUrl");
        const status = el("downloadStatus");
        const button = /** @type {HTMLButtonElement} */ (query("button[type=submit]", form));

        // 検索の視聴画面から来たときの URL と保存先。URL は innerHTML に埋めず value に入れる（URL に書かれた HTML が効かないように）
        url.value = params.get("url") ?? "";
        const requestedDestination = params.get("destination");
        if (requestedDestination === "service" || requestedDestination === "device") {
            /** @type {HTMLInputElement} */ (query(`input[name=destination][value=${requestedDestination}]`, form)).checked = true;
        }

        const jobList = el("deviceJobs");
        /** 最後に描いた一覧の HTML。同じなら描き直さない（描き直すと、一覧のリンクに当てたフォーカスが 5 秒ごとに外れる） */
        let shownHtml = "";

        // 取得中の仕事がある間だけ、5 秒ごとに一覧を取り直す。既に見に行っていれば何もしない
        const startPolling = () => {
            if (myDownloadStopPolling) return;
            const timer = window.setInterval(loadJobs, 5_000);
            myDownloadStopPolling = () => {
                window.clearInterval(timer);
                myDownloadStopPolling = null;
            };
        };

        const loadJobs = async () => {
            try {
                /** @type {MyDeviceDownloadJob[]} */
                const jobs = await apiGet("/api/my/downloads/device");
                // 待つ間に別の画面へ移っていたら、その画面には触らない
                if (!jobList.isConnected) return;
                const html = jobs.length === 0
                    ? '<p class="muted">取得中・受け取り待ちの動画はありません。</p>'
                    : `<ul>${jobs.map(myDeviceDownloadJobHtml).join("")}</ul>`;
                if (html !== shownHtml) {
                    jobList.innerHTML = html;
                    shownHtml = html;
                }
                if (jobs.some((job) => job.status === "RUNNING")) {
                    startPolling();
                } else {
                    myDownloadStopPolling?.();
                }
            } catch (e) {
                // 移った先の画面の見に行き・エラー帯には触らない
                if (!jobList.isConnected) return;
                myDownloadStopPolling?.();
                jobList.innerHTML = '<p class="muted">読み込めませんでした。</p>';
                shownHtml = "";
                showError(errorMessage(e));
            }
        };

        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            const destination = /** @type {HTMLInputElement} */ (query("input[name=destination]:checked", form)).value;
            button.disabled = true;
            try {
                if (destination === "service") {
                    await apiPost("/api/my/downloads", { url: url.value });
                    if (!form.isConnected) return;
                    clearError();
                    showToast("ダウンロードを始めました。終わるとアーカイブに出ます");
                    status.innerHTML = 'ダウンロードを受け付けました。<a href="/my/archive">アーカイブを見る</a>';
                    return;
                }
                const job = await apiPost("/api/my/downloads/device", { url: url.value });
                if (!form.isConnected) return;
                clearError();
                if (job.status === "READY" && job.fileUrl) {
                    // 既にサービスにある録画。取り直さずに、その録画のファイルをそのまま保存させる
                    status.innerHTML = `この動画はサービスに保存済みです。受け取れます。<a href="${escapeHtml(job.fileUrl)}" download>端末に保存</a>`;
                    return;
                }
                status.textContent = "受け付けました。取得の様子は下の「端末に保存の取得」に出ます。";
                await loadJobs();
            } catch (e) {
                // 配信中の URL・同時に 2 件目（409）・空き容量不足（503）は、サーバーの文言をそのまま出す
                if (!form.isConnected) return;
                showError(errorMessage(e));
                // 409 のとき、別のタブ・端末で始めて取得中の仕事を一覧に出す
                if (destination === "device") await loadJobs();
            } finally {
                button.disabled = false;
            }
        });
        loadJobs();
    },
    leave() {
        myDownloadStopPolling?.();
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
    [/^\/my\/download\/?$/, myDownloadView],
    [/^\/my\/search\/?$/, mySearchView],
    [/^\/my\/search\/watch\/([\w-]{11})\/?$/, mySearchWatchView],
    [/^\/my\/discover\/?$/, myDiscoverView],
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

/** myRender を呼んだ回数。描き終えるのを待つ間に、別の画面へ移ったかを見分けるため。 */
let myRenderCount = 0;

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
 * 「戻る」「進む」（restoreScroll）のときは、画面を離れるときに myRememberScroll が残した位置へ戻す。一覧は読み込んでから
 * 描くので、画面の render が返した Promise が解決した後で戻す（Promise を返さない画面は戻さず、先頭から出す）。
 * 待つ間に別の画面へ移った・利用者が自分でスクロールした（位置が 0 でなくなった）ときは動かさない。
 *
 * @param {{ initial?: boolean, restoreScroll?: boolean }} [options] initial は最初の表示。ページを開いた直後はスキップリンクから
 *   始められるよう、フォーカスを動かさない。restoreScroll は「戻る」「進む」で、history.state の scrollY へ戻す
 */
function myRender({ initial = false, restoreScroll = false } = {}) {
    myCurrentView?.leave?.();
    const renderId = ++myRenderCount;
    // 描く画面が history.state を書き換えることがある（動画・配信の URL の整えは null で置き換える）ので、描く前に読んでおく
    /** @type {unknown} */
    const savedY = restoreScroll ? history.state?.scrollY : undefined;
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
        const drawn = view.render(root, match, params);
        // Promise を返さない画面（トップ・動画・配信・設定）は中身を読み込む前の短いページなので、戻すと途中の位置で止まる。
        // 今までどおり先頭から出す
        if (drawn instanceof Promise && typeof savedY === "number" && savedY > 0) {
            drawn.then(() => {
                if (renderId === myRenderCount && window.scrollY === 0) window.scrollTo(0, savedY);
            }, () => {});
        }
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
 * 今の画面のスクロール位置を、今の履歴の項目（history.state）に残す。別の画面・別の条件へ移る直前に呼ぶ。
 * 「戻る」「進む」で戻ったとき、myRender が描き終えた後でこの位置へ戻す。
 * history.state にはほかの値（移る元の from、検索で足したページの印）も入るので、置き換えずに足す。
 */
function myRememberScroll() {
    history.replaceState({ ...history.state, scrollY: Math.round(window.scrollY) }, "");
}

/**
 * このページの中の画面へ移る。リンクの横取りと、検索のフォームの送信が使う。
 *
 * 別の URL へ移るときは、今の位置を今の項目に残し（myRememberScroll）、新しい項目には移る元の URL（from）を残す。
 * 直前の履歴がどの画面かはブラウザからは読めないので、「検索結果に戻る」のようなリンクが、ブラウザの「戻る」と同じ動きを
 * してよいかを from で見分ける（my-search.js の mySearchWatchView）。
 * 同じ URL（今いる画面のメニューを押した・同じ条件で検索し直した）は、履歴を積まずに開き直す。残した位置と足したページは
 * 引き継がない（開き直したのに、前の続きが出ると分かりにくいため）。
 *
 * @param {URL} url 移り先
 */
function myNavigate(url) {
    if (url.href === location.href) {
        history.replaceState({ from: history.state?.from ?? null }, "", url);
    } else {
        myRememberScroll();
        history.pushState({ from: location.pathname + location.search }, "", url);
    }
    myRender();
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
    myNavigate(url);
});
window.addEventListener("popstate", () => myRender({ restoreScroll: true }));

// 「戻る」「進む」の位置はルーターが戻す（myRender）。ブラウザに任せると、中身を読み込む前の短いページで位置を戻そうとして
// 先頭付近に止まるうえ、ルーターが描き直すときの scrollTo(0, 0) と取り合いになる
history.scrollRestoration = "manual";
myDropContinueParam();
myDockInit();
myRender({ initial: true });
