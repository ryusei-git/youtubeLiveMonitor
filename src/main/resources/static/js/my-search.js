// @ts-check
/*
 * 利用者画面の「検索」（/my/search）と、検索から開く視聴画面（/my/search/watch/{videoId}）（#489。API は #487）。
 *
 * my-app.js は別の作業が並行して触るため、画面はこのファイルに分け、my-app.js の myRoutes には行を足すだけにしている。
 * jsconfig.json はすべての js を 1 つのスコープで型検査するので、グローバルの名前は mySearch で始める。
 *
 * 並び方（左にサムネイル・右に情報、視聴画面は大きなプレーヤーと下に情報）だけを YouTube に近づけ、
 * 配色・ロゴ・赤い再生ボタンは真似しない。YouTube API Services の規約で、似せすぎないことが求められているため（#485）。
 */

/**
 * 検索と視聴の画面の下に出す、YouTube API サービスを使っている旨とリンク。規約（III.A.2）で、API のデータを出す画面に
 * YouTube 利用規約と Google プライバシーポリシーへのリンクを置くことが求められているため。
 */
const mySearchAttribution = `<footer class="searchAttribution muted">YouTube API サービスを使っています。
    <a href="https://www.youtube.com/t/terms" target="_blank" rel="noopener noreferrer">YouTube 利用規約</a>・
    <a href="https://policies.google.com/privacy" target="_blank" rel="noopener noreferrer">Google プライバシーポリシー</a></footer>`;

/** 視聴画面の「検索結果に戻る」の行き先。視聴画面を再読み込みしても戻れるよう、タブの中だけ残る sessionStorage に置く。 */
const MY_SEARCH_LAST_URL_KEY = "mySearchLastUrl";

/**
 * URL の条件のうち、そのまま検索 API に渡すもの。フォームの name・URL・API のキーを揃えておけば、
 * 条件を 1 つ足すときにフォームへ欄を置くだけで済む。
 */
const MY_SEARCH_PASSTHROUGH = ["q", "order", "duration", "eventType", "categoryId", "definition", "caption", "license",
    "safeSearch", "publishedAfter", "publishedBefore", "minViews", "maxViews", "minLikes", "maxSubscribers",
    "maxChannelVideos", "excludeShorts", "titleIncludes", "titleExcludes", "withinHours", "excludeSaved"];

/** 詳しい条件の欄の name。畳んだ「詳しい条件」に、指定中の数を添えるのと、URL に条件があれば開くのに使う。 */
const MY_SEARCH_DETAIL_FIELDS = ["channel", "categoryId", "definition", "caption", "license", "safeSearch",
    "minMinutes", "maxMinutes", "minViews", "maxViews", "minLikes", "maxSubscribers", "maxChannelVideos",
    "excludeShorts", "titleIncludes", "titleExcludes", "withinHours", "registered", "excludeSaved"];

/**
 * 入力されたチャンネルの URL か ID から、チャンネル ID（UC で始まる 24 文字）を取り出す。
 * search.list の channelId は本来の ID しか受け付けず、ハンドル（@foo）は別物で、画面からは ID に直せないため
 * （docs/pitfalls.md「YouTube の『ハンドル』（@foo）は本来のチャンネルIDと別物」）。
 *
 * @param {string} input 入力
 * @returns {string|null} チャンネル ID。読み取れなければ null
 */
function mySearchChannelId(input) {
    return input.match(/UC[\w-]{22}/)?.[0] ?? null;
}

/**
 * URL の条件を検索 API のクエリにする。URL を正本にするのは、戻る・再読み込みで同じクエリになり、
 * サーバーの使い回し（6 時間。「ライブ中」は 15 分）に当たって検索の回数を使わないようにするため（「1 週間以内」の起点も URL に残す）。
 *
 * @param {URLSearchParams} url 画面の URL の条件
 * @returns {URLSearchParams} API のクエリ
 */
function mySearchApiParams(url) {
    const params = new URLSearchParams();
    for (const key of MY_SEARCH_PASSTHROUGH) {
        const value = url.get(key);
        if (value) params.set(key, value);
    }
    const channelId = mySearchChannelId(url.get("channel") || "");
    if (channelId) params.set("channelId", channelId);
    // 画面は分で入れる（秒で考える人はいない）。API は秒で受け取る
    for (const [from, to] of [["minMinutes", "minDurationSec"], ["maxMinutes", "maxDurationSec"]]) {
        const minutes = Number(url.get(from));
        if (url.get(from) && Number.isFinite(minutes)) params.set(to, String(Math.round(minutes * 60)));
    }
    if (url.get("registered") === "only") params.set("onlyRegistered", "true");
    if (url.get("registered") === "exclude") params.set("excludeRegistered", "true");
    return params;
}

/**
 * 投稿日の選択から publishedAfter・publishedBefore を決める。検索した時点で 1 度だけ決めて URL に残す
 * （開くたびに「今」から数え直すと、クエリが毎回変わってサーバーの使い回しに当たらないため）。
 * 公式の条件を変えずに検索し直したときは mySearchKeptRange で前の値を引き継ぐ。
 *
 * @param {string} posted 投稿日の選択
 * @param {string} from 期間の開始日（yyyy-MM-dd）
 * @param {string} to 期間の終了日（yyyy-MM-dd）
 * @returns {{publishedAfter?: string, publishedBefore?: string}} ISO-8601（UTC）
 */
function mySearchPublishedRange(posted, from, to) {
    /** @type {Record<string, number>} */
    const hours = { day: 24, week: 24 * 7, month: 24 * 30, year: 24 * 365 };
    if (hours[posted]) {
        // 分より細かい所を切り捨てる。同じ分の中で検索し直したときも同じクエリになるように
        const after = new Date(Date.now() - hours[posted] * 3_600_000);
        after.setSeconds(0, 0);
        return { publishedAfter: after.toISOString() };
    }
    if (posted !== "custom") return {};
    /** @type {{publishedAfter?: string, publishedBefore?: string}} */
    const range = {};
    // 日付はこの端末の時刻の 0 時で区切る。終了日はその日を含めたいので、翌日の 0 時より前にする
    if (from) range.publishedAfter = new Date(`${from}T00:00:00`).toISOString();
    if (to) {
        const end = new Date(`${to}T00:00:00`);
        end.setDate(end.getDate() + 1);
        range.publishedBefore = end.toISOString();
    }
    return range;
}

/**
 * 公式の条件の欄の name。投稿日の起点（publishedAfter）を前の検索から引き継ぐかを決めるとき、前の URL と比べる。
 * from・to は「期間を指定」でしか使わず、そのときは起点を引き継がない（日付から毎回同じ値になる）ので入れない。
 */
const MY_SEARCH_OFFICIAL_FIELDS = ["q", "order", "duration", "posted", "eventType", "channel", "categoryId",
    "definition", "caption", "license", "safeSearch"];

/** 投稿日の起点を引き継ぐ長さ。サーバーの使い回し（YouTubeSearchService の SEARCH_CACHE_TTL）と同じ 6 時間。 */
const MY_SEARCH_KEEP_RANGE_MS = 6 * 3_600_000;

/**
 * 投稿日が「24 時間以内」などのとき、前の検索の起点（publishedAfter）を引き継ぐかを決める。
 * 公式の条件が前と同じなら引き継ぐ。数え直すと分が変わるだけでクエリが変わってサーバーの使い回しに当たらず、
 * このサービスの条件だけを変えた検索でも回数を使ってしまうため。
 * 6 時間より前の起点は引き継がない（サーバーの使い回しも切れていて、「24 時間以内」が大きくずれるため）。
 *
 * @param {URLSearchParams} previous 今の画面の URL の条件（前の検索）
 * @param {URLSearchParams} next これから検索する URL の条件（publishedAfter はまだ入れていない）
 * @param {{publishedAfter?: string, publishedBefore?: string}} fresh mySearchPublishedRange で今から数え直した範囲
 * @returns {{publishedAfter?: string, publishedBefore?: string}} URL に入れる範囲
 */
function mySearchKeptRange(previous, next, fresh) {
    const kept = previous.get("publishedAfter");
    if (!kept || !fresh.publishedAfter || !["day", "week", "month", "year"].includes(next.get("posted") || "")) {
        return fresh;
    }
    if (!MY_SEARCH_OFFICIAL_FIELDS.every((name) => (previous.get(name) || "") === (next.get(name) || ""))) {
        return fresh;
    }
    // 読めない値（NaN）や未来の値は引き継がない
    const age = Date.parse(fresh.publishedAfter) - Date.parse(kept);
    return age >= 0 && age < MY_SEARCH_KEEP_RANGE_MS ? { publishedAfter: kept } : fresh;
}

/**
 * 投稿からの経過を「3 日前」のように出す。YouTube の検索結果と同じく、日時より「どれだけ新しいか」が一目で分かるため。
 *
 * @param {string|null} iso 投稿日時
 * @returns {string} 経過。読めなければ空
 */
function mySearchTimeAgo(iso) {
    const time = iso ? new Date(iso).getTime() : NaN;
    if (Number.isNaN(time)) return "";
    const minutes = Math.max(0, Math.floor((Date.now() - time) / 60_000));
    /** @type {Array<[number, string]>} */
    const units = [[60 * 24 * 365, "年"], [60 * 24 * 30, "か月"], [60 * 24 * 7, "週間"], [60 * 24, "日"], [60, "時間"], [1, "分"]];
    for (const [size, label] of units) {
        if (minutes >= size) return `${Math.floor(minutes / size)} ${label}前`;
    }
    return "たった今";
}

/**
 * 検索結果を YouTube から取った時刻を「3 時間前に YouTube から取得」のように出す。サーバーは同じ条件の結果を
 * 使い回す（最大 6 時間、「ライブ中」は 15 分）ので、再生回数や「ライブ」の札がいつ時点の値かを見せるため
 * （新人発掘の画面の「N 日前に取得」と同じ考え）。
 *
 * @param {string|null} iso 応答の fetchedAt
 * @returns {string} 表示する文。読めなければ空
 */
function mySearchFetchedLabel(iso) {
    const ago = mySearchTimeAgo(iso);
    if (!ago) return "";
    return ago === "たった今" ? "たった今 YouTube から取得" : `${ago}に YouTube から取得`;
}

/**
 * @param {number|null|undefined} count 再生回数
 * @returns {string} 「再生回数 1,234 回」。取れていなければ空
 */
function mySearchViews(count) {
    return count === null || count === undefined ? "" : `再生回数 ${count.toLocaleString("ja-JP")} 回`;
}

/**
 * チャンネルのアイコンと名前。名前は YouTube のチャンネルへのリンクにする（規約で、埋め込みや結果にはチャンネル名を
 * YouTube へのリンクとして出すことが求められているため）。
 *
 * @param {any} item 検索結果の 1 件か動画の詳細
 * @returns {string} 差し込む HTML
 */
function mySearchChannel(item) {
    const url = `https://www.youtube.com/channel/${encodeURIComponent(item.channelId)}`;
    return `<span class="channelWithIcon">${channelIcon(item.channelIconUrl)}${externalLink(item.channelTitle, url)}</span>`;
}

/** 自分が購読している（マイチャンネルにある）チャンネルの札。 */
const MY_SEARCH_SUBSCRIBED_LAMP = '<span class="statusLamp">マイチャンネル</span>';

/**
 * ログイン中の利用者が購読している YouTube のチャンネル ID（UC...）を返す。
 *
 * 検索結果・動画の詳細・発掘の候補の registered は「このサービスの誰かが監視しているか」で、自分の購読とは別。
 * 個人の通知は購読した人にしか届かないので、「マイチャンネル」の札と登録のボタンは自分の購読で出し分ける。
 * 応答に購読を足さずにマイチャンネルの一覧（GET /api/my/channels）から引くのは、札の意味（マイチャンネルにあるか）と
 * 一致し、検索・発掘の API に利用者ごとの項目を持ち込まずに済むため。
 * 比べるのは youtubeChannelId（UC...）で、id（DB の主キー）ではない（docs/pitfalls.md「ID が 2 種類ある」）。
 * 読めなかったときは空の集合を返す。ボタンが出すぎるだけで、押しても「既に登録されています」が出るだけなので、
 * 検索結果や候補の表示は止めない。
 *
 * @returns {Promise<Set<string>>} チャンネル ID の集合
 */
async function mySearchSubscribedChannelIds() {
    try {
        const channels = /** @type {any[]} */ (await apiGet("/api/my/channels"));
        return new Set(channels.filter((c) => c.platform === "YOUTUBE").map((c) => c.youtubeChannelId));
    } catch {
        return new Set();
    }
}

/**
 * チャンネルの監視の札とボタン（検索の視聴画面と新人発掘の候補で使う）。
 * 自分が購読していれば「マイチャンネル」の札だけを出す。購読していなければ登録のボタンを出し、このサービスの誰かが
 * 監視していれば「サービスで監視中」の札を前に添える。registered だけでボタンを消さないのは、別の人が監視していても
 * 自分には通知が届かず、ここで登録できないと困るため。
 *
 * @param {{channelId: string, registered: boolean}} item 動画の詳細か発掘の候補
 * @param {Set<string>} subscribed 自分が購読している YouTube のチャンネル ID
 * @returns {string} 差し込む HTML
 */
function mySearchWatchChannel(item, subscribed) {
    if (subscribed.has(item.channelId)) return MY_SEARCH_SUBSCRIBED_LAMP;
    const service = item.registered ? '<span class="statusLamp serviceWatchLamp">サービスで監視中</span>' : "";
    return `${service}<button type="button" class="watchChannelBtn">監視する（マイチャンネルに登録）</button>`;
}

/**
 * 検索結果の 1 件。タイトルとサムネイルは API の値をそのまま出す（規約 III.C.5。書き換えない）。
 *
 * @param {any} item 検索結果の 1 件
 * @param {Set<string>} subscribed 自分が購読している YouTube のチャンネル ID
 * @returns {HTMLElement} 差し込む要素
 */
function mySearchResult(item, subscribed) {
    const watch = `/my/search/watch/${encodeURIComponent(item.videoId)}`;
    const badge = item.liveBroadcastContent === "live" ? '<span class="searchBadge is-live">ライブ</span>'
        : item.liveBroadcastContent === "upcoming" ? '<span class="searchBadge">配信予定</span>'
        : item.durationSeconds ? `<span class="duration">${formatDuration(item.durationSeconds)}</span>` : "";
    const channelMark = subscribed.has(item.channelId) ? "マイチャンネル" : item.registered ? "サービスで監視中" : "";
    const marks = [item.saved ? "保存済み" : "", channelMark].filter(Boolean)
        .map((label) => `<span class="statusLamp">${label}</span>`).join("");
    const result = document.createElement("article");
    result.className = "searchResult";
    result.innerHTML = `<a class="searchThumb" href="${watch}" tabindex="-1" aria-hidden="true">
            <img src="${escapeHtml(item.thumbnailUrl)}" alt="" loading="lazy" referrerpolicy="no-referrer">${badge}</a>
        <div class="searchInfo">
          <h3 class="searchTitle"><a href="${watch}">${escapeHtml(item.title)}</a></h3>
          <p class="muted">${[mySearchViews(item.viewCount), mySearchTimeAgo(item.publishedAt)].filter(Boolean).join("・")}</p>
          <p class="muted">${mySearchChannel(item)}</p>
          <p class="searchDescription muted">${escapeHtml(item.description)}</p>
          ${marks ? `<p class="searchMarks">${marks}</p>` : ""}
        </div>`;
    return result;
}

/**
 * 検索画面。条件は URL（/my/search?...）に残す。「戻る」「進む」はルーターが拾って画面ごと描き直し、描き直した画面が
 * URL から条件を戻して検索し直す（アーカイブと同じ。同じ条件はサーバーが使い回すので回数は使わない。「ライブ中」は 15 分まで）。
 * 「もっと見る」で足したページの印は history.state に残し、開き直したときに同じところまで足し直す（restorePages。
 * 1 ページ目のサーバーの使い回しが残っているときだけ）。render はそれを描き終えたら解決する Promise を返す。
 * @type {MyView}
 */
const mySearchView = {
    title: "検索",
    nav: "/my/search",
    render(root, _match, params) {
        root.innerHTML = `<h1>検索</h1>
            <p class="pageDescription">YouTube の動画を探して、この画面で再生できます。検索できる回数は 1 日に限りがあります（同じ条件で 6 時間以内に探し直したときは数えません。「ライブ中」で探したときは 15 分以内）。</p>
            <p id="error" class="error" role="alert" style="display:none;"></p>
            <form id="searchForm">
              <div class="inline searchMain">
                <input type="search" name="q" placeholder="キーワード" aria-label="キーワード">
                <button type="submit">検索</button>
              </div>
              <div class="inline">
                <select name="order" aria-label="並び順">
                  <option value="relevance">関連度順</option><option value="date">新しい順</option>
                  <option value="viewCount">再生回数順</option><option value="rating">評価順</option>
                </select>
                <select name="duration" aria-label="長さ">
                  <option value="">長さ：指定なし</option><option value="short">4 分未満</option>
                  <option value="medium">4〜20 分</option><option value="long">20 分以上</option>
                </select>
                <select name="posted" aria-label="投稿日">
                  <option value="">投稿日：指定なし</option><option value="day">24 時間以内</option>
                  <option value="week">1 週間以内</option><option value="month">1 か月以内</option>
                  <option value="year">1 年以内</option><option value="custom">期間を指定</option>
                </select>
                <label class="searchPeriod" hidden><input type="date" name="from" aria-label="期間の開始日"> 〜 <input type="date" name="to" aria-label="期間の終了日"></label>
                <select name="eventType" aria-label="ライブ">
                  <option value="">ライブ：指定なし</option><option value="live">ライブ中</option>
                  <option value="upcoming">配信予定</option><option value="completed">配信済み</option>
                </select>
              </div>
              <details class="utilityPanel searchMore">
                <summary aria-expanded="false">詳しい条件<small class="filterCount"></small></summary>
                <h2>公式の条件</h2>
                <div class="inline">
                  <input type="text" name="channel" placeholder="チャンネルの URL か ID（UC…）" aria-label="チャンネルを限定（URL か ID）" size="36">
                  <select name="categoryId" aria-label="カテゴリ">
                    <option value="">カテゴリ：指定なし</option><option value="10">音楽</option><option value="20">ゲーム</option>
                    <option value="24">エンタメ</option><option value="22">ブログ</option><option value="1">映画とアニメ</option>
                    <option value="17">スポーツ</option><option value="27">教育</option><option value="28">科学と技術</option>
                    <option value="25">ニュース</option>
                  </select>
                  <select name="safeSearch" aria-label="セーフサーチ">
                    <option value="moderate">セーフサーチ：標準</option><option value="strict">セーフサーチ：厳しく</option>
                    <option value="none">セーフサーチ：なし</option>
                  </select>
                  <label><input type="checkbox" name="definition" value="hd"> HD のみ</label>
                  <label><input type="checkbox" name="caption" value="true"> 字幕付きのみ</label>
                  <label><input type="checkbox" name="license" value="creativeCommon"> クリエイティブ・コモンズのみ</label>
                </div>
                <h2>このサービスの条件 <small class="muted">このサービスで絞り込みます</small></h2>
                <div class="inline">
                  <label>長さ（分）<input type="number" name="minMinutes" min="0" aria-label="長さの最小（分）"> 〜 <input type="number" name="maxMinutes" min="0" aria-label="長さの最大（分）"></label>
                  <label>再生回数 <input type="number" name="minViews" min="0" aria-label="再生回数の最小"> 〜 <input type="number" name="maxViews" min="0" aria-label="再生回数の最大"></label>
                  <label>高評価 <input type="number" name="minLikes" min="0" aria-label="高評価の最小"> 以上</label>
                  <label>登録者 <input type="number" name="maxSubscribers" min="0" aria-label="チャンネルの登録者の上限"> 人以下</label>
                  <label>チャンネルの動画 <input type="number" name="maxChannelVideos" min="0" aria-label="チャンネルの動画の数の上限"> 本以下</label>
                  <label>投稿から <input type="number" name="withinHours" min="1" aria-label="投稿から何時間以内"> 時間以内</label>
                </div>
                <div class="inline">
                  <input type="text" name="titleIncludes" placeholder="タイトルに含む" aria-label="タイトルに含む">
                  <input type="text" name="titleExcludes" placeholder="タイトルに含まない（カンマ区切り）" aria-label="タイトルに含まない（カンマ区切り）">
                  <select name="registered" aria-label="監視中のチャンネル">
                    <option value="">監視中のチャンネル：指定なし</option><option value="only">監視中のチャンネルだけ</option>
                    <option value="exclude">監視中のチャンネルを除く</option>
                  </select>
                  <label><input type="checkbox" name="excludeShorts" value="true"> Shorts（3 分以下）を除く</label>
                  <label><input type="checkbox" name="excludeSaved" value="true"> 保存済みを除く</label>
                  <button type="button" class="clearDetailBtn">条件をクリア</button>
                </div>
              </details>
            </form>
            <div class="inline searchSummary" hidden>
              <span class="resultCount"></span>
              <span class="statusLamp filteredMark" hidden>このサービスの条件で絞り込み済み</span>
              <span class="fetchedAt muted" hidden></span>
              <span class="quotaLeft muted"></span>
            </div>
            <div class="searchResults"></div>
            <p><button type="button" class="moreBtn" hidden>もっと見る</button></p>
            ${mySearchAttribution}`;
        const form = formEl("searchForm");
        const fields = /** @type {NodeListOf<HTMLInputElement|HTMLSelectElement>} */ (form.querySelectorAll("[name]"));
        const posted = /** @type {HTMLSelectElement} */ (query("[name=posted]", form));
        const period = /** @type {HTMLElement} */ (query(".searchPeriod", form));
        const more = /** @type {HTMLDetailsElement} */ (query(".searchMore", form));
        const results = query(".searchResults", root);
        const summary = /** @type {HTMLElement} */ (query(".searchSummary", root));
        const moreButton = /** @type {HTMLButtonElement} */ (query(".moreBtn", root));
        // 札の出し分けに使う。「もっと見る」では引き直さない（条件を変えた検索・戻る・進むは画面ごと描き直すので、そのとき引き直す）
        const subscribedLoad = mySearchSubscribedChannelIds();
        const searchButton = /** @type {HTMLButtonElement} */ (query("button[type=submit]", form));
        /** 今出している件数と、次のページの印。「もっと見る」で続きを足すため。 */
        let shown = 0;
        /** @type {string|null} */
        let nextPageToken = null;
        /** 読み込みの番号。条件を続けて変えたとき、遅れて届いた古い応答で上書きしないため（アーカイブと同じ）。 */
        let request = 0;
        /**
         * 出している結果のうち、いちばん古い取得時刻（応答の fetchedAt）。「もっと見る」で足したページは
         * 別の時刻に取ったものでありうるので、古い方を見せる（新しい方を見せると、古い結果が混ざっていても分からない）。
         * @type {string|null}
         */
        let oldestFetchedAt = null;

        /** @param {any} quota 応答の quota */
        const showQuota = (quota) => {
            // 使い切ったときは、いつ戻るかを添える（429 の文言は「16〜17 時ごろ」とおおまかなので、正確な時刻を出す）
            const reset = quota && quota.userRemaining === 0 ? `（${formatInstant(quota.resetsAt)} に戻ります）` : "";
            query(".quotaLeft", root).textContent = quota ? `今日の残り ${quota.userRemaining} 回${reset}` : "";
        };

        // URL の条件を欄へ戻す（select は選択肢に無い値だと空になるので、既定へ戻す）
        for (const field of fields) {
            const value = params.get(field.name) || "";
            if (field instanceof HTMLSelectElement) {
                field.value = Array.from(field.options).some((o) => o.value === value) ? value : field.options[0].value;
            } else if (field.type === "checkbox") {
                field.checked = value === field.value;
            } else {
                field.value = value;
            }
        }
        /** 詳しい条件の欄。「条件をクリア」で空にする範囲と、指定中の数を数える範囲。 */
        const detailFields = Array.from(fields).filter((field) => MY_SEARCH_DETAIL_FIELDS.includes(field.name));
        const syncPeriod = () => { period.hidden = posted.value !== "custom"; };
        posted.addEventListener("change", syncPeriod);
        syncPeriod();
        // 詳しい条件を指定しているときは開き、何で絞り込んでいるかを見せる（アーカイブの畳み方と同じ）
        /**
         * 詳しい条件のうち、既定から変えている欄の数を「詳しい条件」の横に出す。
         * URL ではなく欄の中身から数える。「条件をクリア」や入力で欄が変わったとき、
         * 押す前の数が残らないようにするため。
         * 既定かどうかは検索（submit）で URL に載せるかの判定と同じにする
         * （セーフサーチの「標準」は数えない）。
         * @returns {number} 指定中の欄の数
         */
        const syncDetailCount = () => {
            const count = detailFields.filter((field) => {
                const value = field instanceof HTMLInputElement && field.type === "checkbox"
                    ? (field.checked ? field.value : "") : field.value.trim();
                return !(!value || (field instanceof HTMLSelectElement && value === field.options[0].value));
            }).length;
            query(".filterCount", form).textContent = count > 0 ? `${count}件の条件を指定中` : "";
            return count;
        };
        const detailCount = syncDetailCount();
        more.open = detailCount > 0;
        const summaryEl = query("summary", more);
        const syncExpanded = () => summaryEl.setAttribute("aria-expanded", String(more.open));
        more.addEventListener("toggle", syncExpanded);
        // 入力しただけ（まだ検索していない）でも、欄の中身と件数が食い違わないようにする
        more.addEventListener("input", syncDetailCount);
        more.addEventListener("change", syncDetailCount);
        syncExpanded();

        /**
         * URL の条件で検索する。読み込み中は検索ボタンを止める。同じ条件の検索が重なると、
         * サーバーの使い回しに入る前に両方が回数を使うため。
         * @param {string|null} pageToken 続きを読むときの印。最初のページは null
         * @returns {Promise<any|null>} 描いた応答。失敗した・新しい読み込みに追い越された・画面を離れたときは null
         */
        const load = async (pageToken) => {
            const current = ++request;
            const api = mySearchApiParams(new URLSearchParams(location.search));
            if (pageToken) api.set("pageToken", pageToken);
            setBusy(results, true);
            moreButton.disabled = searchButton.disabled = true;
            try {
                const [data, subscribed] = await Promise.all([apiGet(`/api/my/search?${api}`), subscribedLoad]);
                if (current !== request || !results.isConnected) return null;
                clearError();
                if (!pageToken) {
                    results.replaceChildren();
                    shown = 0;
                    oldestFetchedAt = null;
                }
                results.append(...(/** @type {any[]} */ (data.items)).map((item) => mySearchResult(item, subscribed)));
                shown += data.items.length;
                if (shown === 0) {
                    results.innerHTML = emptyState("該当する動画はありません",
                        "キーワードを変えるか、詳しい条件を外してお試しください");
                }
                nextPageToken = data.nextPageToken;
                moreButton.hidden = !nextPageToken;
                summary.hidden = false;
                query(".resultCount", root).textContent = `約 ${shown} 件を表示`;
                /** @type {HTMLElement} */ (query(".filteredMark", root)).hidden = !data.filteredByService;
                showQuota(data.quota);
                if (data.fetchedAt && (!oldestFetchedAt || Date.parse(data.fetchedAt) < Date.parse(oldestFetchedAt))) {
                    oldestFetchedAt = data.fetchedAt;
                }
                const fetchedLabel = query(".fetchedAt", root);
                fetchedLabel.textContent = mySearchFetchedLabel(oldestFetchedAt);
                fetchedLabel.hidden = !fetchedLabel.textContent;
                return data;
            } catch (e) {
                // 上限（429）・条件の誤り（400）・API の失敗（503）は、サーバーの文言をそのまま出す
                if (current === request && results.isConnected) showError(errorMessage(e), { reveal: true });
                return null;
            } finally {
                if (current === request && results.isConnected) {
                    setBusy(results, false);
                    moreButton.disabled = searchButton.disabled = false;
                }
            }
        };
        /** 「もっと見る」で足したページの印（2 ページ目から順）。 */
        /** @type {string[]} */
        const addedTokens = [];
        /** 1 ページ目を YouTube から取った時刻（応答の fetchedAt。サーバーが使い回したときは、取った元の時刻）。 */
        /** @type {string|null} */
        let firstFetchedAt = null;
        /**
         * 足したページの印を今の履歴の項目に残す。「戻る」「進む」・再読み込みで開き直したとき、同じところまで足し直すため。
         * myRememberScroll が残したスクロール位置を消さないよう、history.state に足す。
         */
        const rememberPages = () => {
            history.replaceState({ ...history.state, searchPageTokens: [...addedTokens], searchFetchedAt: firstFetchedAt }, "");
        };
        /**
         * 1 ページ目を読み、前に「もっと見る」で足したページがあれば、同じところまで足し直す。
         * 足し直すのは、1 ページ目の取得時刻が足したときと同じ（＝サーバーの使い回しがまだ残っている）ときだけ。
         * 続きのページはその後で取ったものなので、ふつうは使い回しに当たり、検索の回数（1 人 1 日の上限）を使わない。
         * 使い回しが切れていると、足し直すページごとに回数を使うため、足し直さない。
         * 続きの印が前と違うとき（結果が変わった）・読めなかったときも、そこで止める。
         *
         * @returns {Promise<void>} 描き終えたら解決する（ルーターが「戻る」「進む」のスクロール位置をこの後で戻す）
         */
        const restorePages = async () => {
            /** @type {unknown} */
            const savedTokens = history.state?.searchPageTokens;
            const savedFetchedAt = history.state?.searchFetchedAt;
            /** @type {string[]} */
            const tokens = Array.isArray(savedTokens) ? savedTokens.filter((t) => typeof t === "string") : [];
            const first = await load(null);
            if (!first) return;
            firstFetchedAt = first.fetchedAt;
            if (tokens.length === 0) return;
            if (first.fetchedAt === savedFetchedAt) {
                for (const token of tokens) {
                    if (nextPageToken !== token || !await load(token)) break;
                    addedTokens.push(token);
                }
            }
            // 足し直せなかったページは、次に開き直したときも足し直さない
            if (addedTokens.length !== tokens.length && results.isConnected) rememberPages();
        };

        form.addEventListener("submit", (event) => {
            event.preventDefault();
            const url = new URL("/my/search", location.origin);
            for (const field of fields) {
                const value = field instanceof HTMLInputElement && field.type === "checkbox"
                    ? (field.checked ? field.value : "") : field.value.trim();
                const isDefault = !value || (field instanceof HTMLSelectElement && value === field.options[0].value);
                if (!isDefault) url.searchParams.set(field.name, value);
            }
            if (posted.value !== "custom") {
                url.searchParams.delete("from");
                url.searchParams.delete("to");
            }
            const channel = url.searchParams.get("channel");
            if (channel && !mySearchChannelId(channel)) {
                showError("チャンネルの ID（UC で始まる 24 文字）か、/channel/UC… の URL を入れてください。@ で始まるハンドルは使えません", { reveal: true });
                return;
            }
            if (!url.searchParams.get("q") && !channel) {
                showError("キーワードを入れてください（チャンネルを限定したときは空でも探せます）", { reveal: true });
                return;
            }
            // 公式の条件が前と同じなら、投稿日の起点は前の検索のものを使う（数え直すと、このサービスの条件だけを変えても回数を使うため）
            const fresh = mySearchPublishedRange(posted.value, url.searchParams.get("from") || "", url.searchParams.get("to") || "");
            const range = mySearchKeptRange(new URLSearchParams(location.search), url.searchParams, fresh);
            for (const [key, value] of Object.entries(range)) {
                url.searchParams.set(key, value);
            }
            // 画面ごと描き直す（ルーターと同じ経路）。URL から条件を戻して検索するので、戻る・再読み込みと同じ動きになる。
            // 今の結果の位置を残してから移るので、「戻る」で前の検索結果の同じ位置へ戻れる
            myNavigate(url);
        });
        // 「詳しい条件」の中のボタンなので、詳しい条件の欄だけを既定へ戻す。
        // キーワードや並び順・投稿日などは残す。検索はし直さない
        // （押すたびに検索の回数を使わないよう、検索ボタンを押したときだけ探す）
        query(".clearDetailBtn", form).addEventListener("click", () => {
            for (const field of detailFields) {
                if (field instanceof HTMLSelectElement) field.value = field.options[0].value;
                else if (field.type === "checkbox") field.checked = false;
                else field.value = "";
            }
            syncDetailCount();
        });
        moreButton.addEventListener("click", async () => {
            const token = nextPageToken;
            // 足せたページだけを残す（読めなかったページは「戻る」で足し直さない）
            if (!token || !await load(token)) return;
            addedTokens.push(token);
            rememberPages();
        });

        // 視聴画面の「検索結果に戻る」の行き先
        try { sessionStorage.setItem(MY_SEARCH_LAST_URL_KEY, location.pathname + location.search); } catch { /* 残せなくても検索画面へは戻れる */ }
        if (params.get("q") || params.get("channel")) {
            return restorePages();
        } else {
            // 検索する前に、今日あと何回探せるかを見せる。失敗しても検索はできるので黙っておく
            apiGet("/api/my/search/quota").then((quota) => {
                if (!summary.isConnected) return;
                summary.hidden = false;
                showQuota(quota);
            }).catch(() => {});
        }
    },
};

/**
 * 説明を HTML にする。URL の部分だけをリンクにし、URL もそれ以外もエスケープしてから出す（エスケープせずにつなぐと、説明に書かれた HTML が効いてしまう）。
 *
 * URL として拾うのは、URL に使える ASCII の文字だけにする。日本語の説明では「（https://x.com/foo）」「https://example.com。」のように、
 * URL の直後に空白を置かずに全角の記号や文字が続くことが多い。空白までをすべて URL とみなすと、全角の記号まで href に入り、開くと 404 になる。
 * 末尾の . , : ; ! ? ' * は文の区切りであることが多いので URL から外す。末尾の ) は、URL の中に対応する ( が無いときだけ外す
 * （「(https://example.com/a)」の ) は外し、「https://ja.wikipedia.org/wiki/Foo_(bar)」の ) は残す）。
 * 日本語を含む URL は、日本語の手前までをリンクにする（ブラウザのアドレス欄からコピーした URL は % の形になっているため、実際には少ない）。
 *
 * @param {string|null} text 説明
 * @returns {string} 差し込む HTML
 */
function mySearchLinkify(text) {
    const source = text ?? "";
    let html = "";
    let last = 0;
    for (const match of source.matchAll(/https?:\/\/[A-Za-z0-9\-._~:\/?#@!$&'()*+,;=%]+/g)) {
        // 外した末尾の記号は、URL の後ろの文として出す
        let url = match[0].replace(/[.,:;!?'*]+$/, "");
        while (url.endsWith(")") && url.split("(").length < url.split(")").length) {
            url = url.slice(0, -1).replace(/[.,:;!?'*]+$/, "");
        }
        const index = /** @type {number} */ (match.index);
        const escapedUrl = escapeHtml(url);
        html += `${escapeHtml(source.slice(last, index))}<a href="${escapedUrl}" target="_blank" rel="noopener noreferrer">${escapedUrl}</a>`;
        last = index + url.length;
    }
    return html + escapeHtml(source.slice(last));
}

/**
 * 検索から開く視聴画面。YouTube の埋め込みプレーヤーで再生し、下に動画の情報と、保存・監視のボタンを出す。
 *
 * プレーヤーは youtube-nocookie（再生するまで Cookie を置かない）にし、自動再生はしない（子ども向けの動画
 * （madeForKids）もあり、開いただけで音が出るのを避けるため）。上に重ねる表示はしない（規約）。
 * @type {MyView}
 */
const mySearchWatchView = {
    title: "再生",
    // 検索から開く画面なので、検索の中にいるものとして示す
    nav: "/my/search",
    async render(root, match) {
        // ミニプレーヤーで録画を流したまま開くと、埋め込みの再生と音が重なる。埋め込みの中で再生を押したことはこのページから
        // 見えないので、開いた時点で止める（埋め込みの再生ダイアログを開いたときと同じ。my-app.js の myPauseDockOnVideoDialog）。
        // 自動再生はしないので、開いただけでは音は出ない。録画の続きは、ミニプレーヤーの ▶ で利用者が再開できる
        myDockVideo().pause();
        const videoId = match[1];
        let back = "/my/search";
        try { back = sessionStorage.getItem(MY_SEARCH_LAST_URL_KEY) || back; } catch { /* 検索画面の最初へ戻す */ }
        root.innerHTML = `<p><a class="searchBack" href="${escapeHtml(back)}">← 検索結果に戻る</a></p>
            <p id="error" class="error" role="alert" style="display:none;"></p>
            <div class="searchPlayer"><iframe src="https://www.youtube-nocookie.com/embed/${videoId}?rel=0&amp;playsinline=1"
              title="YouTube の動画" allow="encrypted-media; fullscreen; picture-in-picture" allowfullscreen
              referrerpolicy="strict-origin-when-cross-origin"></iframe></div>
            <p class="muted searchPlayerHint">再生できないときは YouTube で開いてください。</p>
            <h1>読み込み中...</h1>
            <div class="searchWatchInfo"></div>
            ${mySearchAttribution}`;
        // 直前の履歴がその検索結果なら、ブラウザの「戻る」と同じにする。リンクとして移ると履歴が 1 つ伸び、その後に
        // ブラウザの「戻る」を押すと視聴画面へ戻ってしまう。「戻る」で戻れば、結果の位置と「もっと見る」で足したページも戻る。
        // 直前の履歴がどこかはブラウザからは読めないので、ルーターが移るときに残した from（my-app.js の myNavigate）で見分ける。
        // 新しいタブで開く操作（修飾キー・左以外のボタン）はリンクのままにする
        query(".searchBack", root).addEventListener("click", (event) => {
            if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
            if (history.state?.from !== back) return;
            event.preventDefault();
            history.back();
        });
        const heading = query("h1", root);
        const info = query(".searchWatchInfo", root);
        const watchUrl = `https://www.youtube.com/watch?v=${videoId}`;
        /** @type {any} */
        let video;
        const subscribedLoad = mySearchSubscribedChannelIds();
        try {
            // 無い動画（削除・非公開）は 404 で返る。ほかの失敗と分けて伝えるため、状態コードを見られるよう apiGet を使わない
            const res = await authenticatedFetch(`/api/my/youtube/videos/${encodeURIComponent(videoId)}`);
            if (res.status === 404) {
                if (heading.isConnected) heading.textContent = "この動画は見られません";
                return;
            }
            if (!res.ok) throw new Error(await extractError(res));
            video = await res.json();
        } catch (e) {
            if (heading.isConnected) {
                heading.textContent = "動画の情報を読み込めませんでした";
                showError(errorMessage(e));
            }
            return;
        }
        if (!heading.isConnected) return;
        const subscribed = await subscribedLoad;
        if (!heading.isConnected) return;

        document.title = `${video.title} - YouTube Live Monitor`;
        heading.textContent = video.title;
        query("iframe", root).title = video.title;
        const saved = video.saved && video.recordingId
            ? `<a href="/my/watch/${encodeURIComponent(video.recordingId)}">アーカイブで見る</a>`
            : '<button type="button" class="saveBtn">サービスに保存</button>';
        info.innerHTML = `<p>${mySearchChannel(video)}</p>
            <p class="muted">${[mySearchViews(video.viewCount), formatInstant(video.publishedAt)].filter(Boolean).join("・")}</p>
            <p class="searchWatchActions">
              <a href="${watchUrl}" target="_blank" rel="noopener noreferrer">YouTube で開く</a>
              ${saved}
              <a href="/my/download?${new URLSearchParams({ url: watchUrl, destination: "device" })}">端末に保存</a>
              ${mySearchWatchChannel(video, subscribed)}
            </p>
            <div class="searchWatchDescription">${mySearchLinkify(video.description)}</div>
            <button type="button" class="descriptionToggle" hidden>もっと見る</button>`;

        const description = query(".searchWatchDescription", info);
        const toggle = /** @type {HTMLButtonElement} */ (query(".descriptionToggle", info));
        // 3 行に収まる短い説明には「もっと見る」を出さない
        toggle.hidden = description.scrollHeight <= description.clientHeight;
        toggle.addEventListener("click", () => {
            const open = description.classList.toggle("is-open");
            toggle.textContent = open ? "一部だけ表示" : "もっと見る";
        });

        info.querySelector(".saveBtn")?.addEventListener("click", async (event) => {
            const button = /** @type {HTMLButtonElement} */ (event.currentTarget);
            button.disabled = true;
            try {
                await apiPost("/api/my/downloads", { url: watchUrl });
                if (!info.isConnected) return;
                clearError();
                showToast("保存を始めました。終わるとアーカイブに出ます");
            } catch (e) {
                // 配信中・同時に 2 件目・空き容量不足は、サーバーの文言をそのまま出す
                if (info.isConnected) showError(errorMessage(e), { reveal: true });
                button.disabled = false;
            }
        });
        info.querySelector(".watchChannelBtn")?.addEventListener("click", async (event) => {
            const button = /** @type {HTMLButtonElement} */ (event.currentTarget);
            button.disabled = true;
            try {
                await apiPost("/api/my/channels", {
                    platform: "YOUTUBE", channelInput: video.channelId, channelName: video.channelTitle,
                });
                if (!info.isConnected) return;
                clearError();
                showToast(`${video.channelTitle} をマイチャンネルに登録しました`);
                info.querySelector(".serviceWatchLamp")?.remove();
                button.outerHTML = MY_SEARCH_SUBSCRIBED_LAMP;
            } catch (e) {
                if (info.isConnected) showError(errorMessage(e), { reveal: true });
                button.disabled = false;
            }
        });
    },
};
