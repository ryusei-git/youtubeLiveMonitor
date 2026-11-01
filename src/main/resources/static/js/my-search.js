// @ts-check
/*
 * 利用者画面の「検索」（/my/search）と、検索から開く視聴画面（/my/search/watch/{videoId}）（#489。API は #487）、
 * 動画・配信のカードから開く視聴画面（/my/videos/watch/{id}。#876）。
 *
 * my-app.js は別の作業が並行して触るため、画面はこのファイルに分け、my-app.js の myRoutes には行を足すだけにしている。
 * jsconfig.json はすべての js を 1 つのスコープで型検査するので、グローバルの名前は mySearch か myVideoWatch で始める。
 *
 * 並び・大きさ・部品の形・無彩色の配色は YouTube に寄せ、ロゴ・名前の流用・赤い再生ボタン・赤の配色は使わない
 * （#879 で #485 の線引きを改めた）。Branding Guidelines が trade dress（look and feel・特徴的な配色の組み合わせ）の模倣を、
 * Developer Policies III.I.1 が体験の再現を禁じているため。視聴画面の操作は YouTube のものと混ぜない（III.C.2）。
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
 * 検索結果を YouTube から取った時刻を「3 時間前に YouTube から取得」のように出す。サーバーは同じ条件の結果を
 * 使い回す（最大 6 時間、「ライブ中」は 15 分）ので、再生回数や「ライブ」の札がいつ時点の値かを見せるため
 * （新人発掘の画面の「N 日前に取得」と同じ考え）。
 *
 * @param {string|null} iso 応答の fetchedAt
 * @returns {string} 表示する文。読めなければ空
 */
function mySearchFetchedLabel(iso) {
    const ago = formatTimeAgo(iso);
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

/** 自分が購読している（登録チャンネルにある）チャンネルの札。 */
const MY_SEARCH_SUBSCRIBED_LAMP = '<span class="statusLamp">登録済み</span>';

/**
 * ログイン中の利用者が購読している YouTube のチャンネル ID（UC...）を返す。
 *
 * 検索結果・動画の詳細・発掘の候補の registered は「このサービスの誰かが監視しているか」で、自分の購読とは別。
 * 個人の通知は購読した人にしか届かないので、「登録済み」の札と登録のボタンは自分の購読で出し分ける。
 * 応答に購読を足さずに購読の一覧（GET /api/my/channels）から引くのは、札の意味（登録チャンネルにあるか）と
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
 * チャンネルの監視の札とボタン（視聴画面と新人発掘の候補で使う）。
 * 自分が購読していれば「登録済み」の札だけを出す。購読していなければ登録のボタンを出し、このサービスの誰かが
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
    return `${service}<button type="button" class="watchChannelBtn">監視する（登録チャンネルに追加）</button>`;
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
    const channelMark = subscribed.has(item.channelId) ? "登録済み" : item.registered ? "サービスで監視中" : "";
    const marks = [item.saved ? "保存済み" : "", channelMark].filter(Boolean)
        .map((label) => `<span class="statusLamp">${label}</span>`).join("");
    const result = document.createElement("article");
    result.className = "searchResult";
    result.innerHTML = `<a class="searchThumb" href="${watch}" tabindex="-1" aria-hidden="true">
            <img src="${escapeHtml(item.thumbnailUrl)}" alt="" loading="lazy" referrerpolicy="no-referrer">${badge}</a>
        <div class="searchInfo">
          <h3 class="searchTitle"><a href="${watch}">${escapeHtml(item.title)}</a></h3>
          <p class="muted">${[mySearchViews(item.viewCount), formatTimeAgo(item.publishedAt)].filter(Boolean).join("・")}</p>
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
                <button type="submit">検索</button>
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

        // ヘッダーの検索欄と本文の語の欄を同じ語にする（PC の幅はヘッダーの欄、760px 以下は本文の欄で入力する）。
        // ヘッダーから本文へ写す側は my-app.js の myBindHeaderSearch。語の欄はこの画面と一緒に消えるので、外す処理は要らない
        const headerWord = inputEl("headerSearchInput");
        headerWord.value = params.get("q") || "";
        query("[name=q]", form).addEventListener("input", (event) => {
            headerWord.value = /** @type {HTMLInputElement} */ (event.currentTarget).value;
        });

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
        // 詳しい条件を指定しているときは開き、何で絞り込んでいるかを見せる（アーカイブの畳み方と同じ）
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
    leave() {
        // ヘッダーは画面を移っても残るので、検索の画面を離れたら語を残さない
        inputEl("headerSearchInput").value = "";
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
 * @param {(text: string) => string} [renderText] URL の外の文字を HTML にする関数。必ずエスケープすること
 * @returns {string} 差し込む HTML
 */
function mySearchLinkify(text, renderText = escapeHtml) {
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
        html += `${renderText(source.slice(last, index))}<a href="${escapedUrl}" target="_blank" rel="noopener noreferrer">${escapedUrl}</a>`;
        last = index + url.length;
    }
    return html + renderText(source.slice(last));
}

/**
 * 説明の時刻（12:34・1:02:03）の形。前後が数字や : のものは時刻とみなさない（123:45・1:02:03:04 など）。
 * 分・秒が 60 以上のものも形の時点で外す。split の結果に時刻も入るよう、全体を 1 つの組にする。
 */
const MY_SEARCH_TIME = /((?<![\d:])(?:\d{1,2}:[0-5]\d|[0-5]?\d):[0-5]\d(?![\d:]))/;

/**
 * 説明の URL の外の文字を HTML にする。時刻は押すとその位置へ飛ぶボタンにする（章の目次を説明に書く動画が多いため）。
 * 押したときの動きは {@link mySearchBindWatchControls} が付ける。
 *
 * @param {string} text URL の外の文字
 * @returns {string} 差し込む HTML
 */
function mySearchTimeButtons(text) {
    return text.split(MY_SEARCH_TIME).map((part, index) => {
        if (index % 2 === 0) return escapeHtml(part);
        const seconds = part.split(":").reduce((sum, value) => sum * 60 + Number(value), 0);
        return `<button type="button" class="watchTime" data-seconds="${seconds}">${part}</button>`;
    }).join("");
}

/**
 * 視聴画面の骨組み。検索から開く画面と動画・配信から開く画面で、同じ配置（YouTube の視聴ページの並び）にする。
 * 埋め込みプレーヤーの中の見た目は変えられず、上に重ねる表示も規約で禁じられているので、周りの配置で一体感を出す。
 * h1 は最初から置く。ルーターが描いた直後にフォーカスを移す先で、読み込んだ後も作り直さず文字だけを変える。
 *
 * @param {{before?: string, after?: string, secondary?: boolean}} [options] before は左の列の先頭（プレーヤーと左端をそろえる）、
 *   after は配置の下に置く HTML。secondary は右の列の場所を取るか（読み込んだ後に列が増えてプレーヤーの幅が変わらないよう、
 *   先に 2 列にしておく）
 * @returns {string} 差し込む HTML
 */
function mySearchWatchLayout({ before = "", after = "", secondary = false } = {}) {
    return `<div class="watchPage">
          <div class="watchPrimary">
            ${before}<p id="error" class="error" role="alert" style="display:none;"></p>
            <div class="watchPlayer"></div>
            <p class="muted watchHint" hidden>再生できないときは配信元で開いてください。</p>
            <h1 class="watchTitle">読み込み中...</h1>
            <div class="watchOwner"><div class="watchChannel"></div><div class="watchActions"></div></div>
            <div class="watchDescription"></div>
          </div>
          ${secondary ? '<aside class="watchSecondary" aria-labelledby="watchSecondaryHeading" hidden></aside>' : ""}
        </div>${after}`;
}

/** 埋め込みプレーヤーのオリジン。命令の送り先で、応答もここから来たものだけを読む。 */
const MY_SEARCH_PLAYER_ORIGIN = "https://www.youtube-nocookie.com";

/** 応答の info のうち読む項目（{@link MySearchPlayerState}）。 */
const MY_SEARCH_PLAYER_INFO_KEYS = ["currentTime", "duration", "playerState", "playbackRate", "availablePlaybackRates", "muted"];

/** listening を送り直す間隔（ミリ秒）。プレーヤーの準備が整う前に届いた分は捨てられる（公式のスクリプトも送り直している）。 */
const MY_SEARCH_PLAYER_RETRY_MS = 250;

/** listening を諦めるまでの長さ（ミリ秒）。これを過ぎても待ち受けは残すが、送り続けない。 */
const MY_SEARCH_PLAYER_GIVE_UP_MS = 10000;

/**
 * 埋め込みプレーヤーの状態。currentTime のほかは、プレーヤーから届いた値だけを持つ（速度は頼むだけで変わらないことが
 * あるので、届いた値を正とする）。
 *
 * @typedef {object} MySearchPlayerState
 * @property {number} currentTime 再生位置（秒）
 * @property {number} duration 長さ（秒）。分からなければ 0
 * @property {number} playerState 0 終了・1 再生中・2 一時停止・3 読み込み中・5 頭出し。届く前は -1
 * @property {number} playbackRate 再生速度（倍）
 * @property {number[]} availablePlaybackRates 使える再生速度
 * @property {boolean} muted 消音か
 * @property {boolean} live 配信中か（videoData.isLive）。最初の状態では配信でも false で、再生が始まると true で届く
 * @property {boolean} failed 再生できない（onError が届いた）か。再生できない動画でも、状態は onError の前に届く
 */

/**
 * @typedef {object} MySearchPlayer
 * @property {MySearchPlayerState} state 届いた状態
 * @property {(() => void)|null} onInfo 状態が届くたびに呼ぶ処理
 * @property {(func: string, ...args: unknown[]) => void} command プレーヤーの関数を呼ぶ
 * @property {(seconds: number) => void} seekTo 指定の位置へ動かす
 * @property {(seconds: number) => void} seekBy 今の位置から進める（負なら戻す）
 * @property {() => void} stop 待ち受けと送り直しのタイマーを外す
 */

/**
 * YouTube の埋め込みプレーヤーを postMessage で操作する。
 *
 * 公式の iframe_api は CSP（script-src 'self'）で読めないので、その中身と同じやりとりだけを行う。送り先と送り元を
 * プレーヤーのオリジンに限り、ほかの窓から届いた文字列を状態として読まない。iframe を外すと contentWindow は null に
 * なるので、別の動画へ移った後に前のプレーヤーの応答が届いても、source の比較で落ちる。
 * 応答が来ないとき（YouTube 側の変更など）は onReady を呼ばないだけで、埋め込みそのものの再生はそのまま動く。
 *
 * 止めている間にシークしても、プレーヤーは currentTime を送ってこない（再開すると正しい位置から始まる）。そのため
 * seekTo は持っている位置も動かす。動かさないと、止めたまま J や L を続けて押したときに同じ位置から数え直す。
 *
 * @param {HTMLIFrameElement} frame 埋め込みの iframe（読み込みが終わる前に渡す）
 * @param {() => void} onReady availablePlaybackRates が届いたら 1 回だけ呼ぶ処理
 * @returns {MySearchPlayer} 操作
 */
function mySearchBindPlayer(frame, onReady) {
    /** @type {MySearchPlayerState} */
    const state = {
        currentTime: 0, duration: 0, playerState: -1, playbackRate: 1, availablePlaybackRates: [], muted: false, live: false, failed: false,
    };
    let ready = false;
    /** @type {number|undefined} */
    let retryTimer;

    /** @param {Record<string, unknown>} message 送る内容 */
    const send = (message) => {
        frame.contentWindow?.postMessage(JSON.stringify({ ...message, id: 1, channel: "widget" }), MY_SEARCH_PLAYER_ORIGIN);
    };
    /** @type {(func: string, ...args: unknown[]) => void} */
    const command = (func, ...args) => send({ event: "command", func, args });
    /** load より前は別のオリジンの空のページなので送れない（送るとコンソールにエラーが出る）。 */
    const listen = () => {
        const startedAt = Date.now();
        send({ event: "listening" });
        retryTimer = window.setInterval(() => {
            if (Date.now() - startedAt >= MY_SEARCH_PLAYER_GIVE_UP_MS) window.clearInterval(retryTimer);
            else send({ event: "listening" });
        }, MY_SEARCH_PLAYER_RETRY_MS);
    };
    /** @param {MessageEvent} event 届いたメッセージ */
    const onMessage = (event) => {
        if (event.source !== frame.contentWindow || event.origin !== MY_SEARCH_PLAYER_ORIGIN) return;
        /** @type {any} */
        let data;
        try {
            data = JSON.parse(event.data);
        } catch {
            return;
        }
        window.clearInterval(retryTimer);
        if (data?.event === "onError") state.failed = true;
        // info は状態のオブジェクトのほか、onError などでは数値で届く
        const info = data?.info && typeof data.info === "object" ? data.info : {};
        for (const key of MY_SEARCH_PLAYER_INFO_KEYS) {
            if (key in info) Object.assign(state, { [key]: info[key] });
        }
        if (typeof info.videoData?.isLive === "boolean") state.live = info.videoData.isLive;
        if (!ready && "availablePlaybackRates" in info) {
            ready = true;
            onReady();
        }
        player.onInfo?.();
    };
    /** @param {number} seconds 動かす位置（秒） */
    const seekTo = (seconds) => {
        const upper = state.duration > 0 ? state.duration : Infinity;
        state.currentTime = Math.min(Math.max(0, seconds), upper);
        command("seekTo", state.currentTime, true);
    };
    /** @type {MySearchPlayer} */
    const player = {
        state,
        onInfo: null,
        command,
        seekTo,
        seekBy: (seconds) => seekTo(state.currentTime + seconds),
        stop() {
            window.removeEventListener("message", onMessage);
            frame.removeEventListener("load", listen);
            window.clearInterval(retryTimer);
        },
    };
    window.addEventListener("message", onMessage);
    frame.addEventListener("load", listen, { once: true });
    return player;
}

/** 続きの位置を残す localStorage のキー。値は動画 ID ごとの {position, duration, savedAt}。 */
const MY_SEARCH_POSITIONS_KEY = "ylm.videoPositions";

/** 続きの位置を残す動画の数。端末に増え続けないよう、古いものから捨てる。 */
const MY_SEARCH_POSITIONS_LIMIT = 200;

/**
 * 残してある続きの位置を読む。録画と違ってサーバーには送らない（テーブルを足すほどの価値が今は無いため）。
 *
 * @returns {Record<string, {position: number, duration: number, savedAt: number}>} 動画 ID ごとの位置。読めなければ空
 */
function mySearchVideoPositions() {
    try {
        const saved = JSON.parse(localStorage.getItem(MY_SEARCH_POSITIONS_KEY) ?? "{}");
        return saved && typeof saved === "object" ? saved : {};
    } catch {
        return {};
    }
}

/**
 * 続きの位置を新しい順に上限まで残す。呼ぶ側は読み直した値へ足して渡すので、同じ動画を 2 つのタブで開いたら後から
 * 残した位置が勝ち、別の動画の位置は消えない。
 *
 * @param {Record<string, {position: number, duration: number, savedAt: number}>} positions 動画 ID ごとの位置
 */
function mySearchWriteVideoPositions(positions) {
    const newest = Object.entries(positions).sort(([, a], [, b]) => b.savedAt - a.savedAt).slice(0, MY_SEARCH_POSITIONS_LIMIT);
    try {
        localStorage.setItem(MY_SEARCH_POSITIONS_KEY, JSON.stringify(Object.fromEntries(newest)));
    } catch {
        // 残せなくても再生は続ける（プライベートブラウズなど）
    }
}

/**
 * 動画を開くときの続きの位置を決める。
 *
 * @param {string} videoId 動画 ID
 * @returns {number} 始める位置（秒）。先頭から始めるなら 0
 */
function mySearchResumeStart(videoId) {
    const saved = mySearchVideoPositions()[videoId];
    return saved ? myResumeStartSeconds(saved.position, saved.duration) ?? 0 : 0;
}

/**
 * 今の視聴画面を離れるときの後始末。{@link mySearchBindWatchControls} が入れ、YouTube の埋め込みが無い画面では null。
 * @type {(() => void)|null}
 */
let mySearchLeaveWatch = null;

/**
 * プレーヤーの下の操作の列と、キー操作・続きの位置・説明の時刻を結びつける。
 *
 * 録画の再生画面と同じ操作を、埋め込みの外から行う。上に重ねるのは規約で禁じられているので、列はプレーヤーの下に
 * 置く。すべてプレーヤーの応答（onReady）を待ってから効かせる。応答が来ないときは列を出さず、キーも横取りせず、
 * 埋め込みの再生だけが残る。
 *
 * @param {HTMLElement} root 画面の入れ物
 * @param {HTMLIFrameElement} frame 埋め込みの iframe（読み込みが終わる前に渡す）
 * @param {string} videoId 動画 ID
 */
function mySearchBindWatchControls(root, frame, videoId) {
    const controls = document.createElement("div");
    controls.className = "watchControls";
    controls.hidden = true;
    controls.innerHTML = `<button type="button" class="watchBack10" title="10秒戻す（J キーでも）">-10秒</button>
        <button type="button" class="watchForward10" title="10秒進める（L キーでも）">+10秒</button>
        <label class="dockRateLabel">速度 <select title="再生速度（&lt; と &gt; のキーでも変えられます）"></select></label>`;
    query(".watchPlayer", root).after(controls);
    const rateLabel = /** @type {HTMLElement} */ (query(".dockRateLabel", controls));
    const rateSelect = /** @type {HTMLSelectElement} */ (query("select", controls));
    const description = query(".watchDescription", root);
    /** 速度の選択肢。プレーヤーが使える速度のうち、録画の選択肢にもあるもの。 */
    /** @type {number[]} */
    let rates = [];
    /** 保存された速度をプレーヤーへ頼んだか。状態が届くたびに頼むと、プレーヤーの歯車で変えた速度を戻してしまう。 */
    let savedRateApplied = false;
    /** 再生中に位置を残す間隔を数える起点（Date.now() の値）。 */
    let positionSavedAt = Date.now();
    let lastPlayerState = -1;

    /**
     * 続きの位置を残す。終わった動画は位置を消す（次は先頭から）。
     * 配信中は戻る先が無いので残さない。冒頭は、広告の時間で前に見た位置を上書きしないよう残さない。
     */
    const savePosition = () => {
        const { currentTime, duration, playerState, live } = player.state;
        const ended = playerState === 0;
        if (!ended && (live || duration <= 0 || currentTime < MY_RESUME_MIN_SECONDS)) return;
        // 読み直してから足す（理由は mySearchWriteVideoPositions）
        const positions = mySearchVideoPositions();
        if (ended) delete positions[videoId];
        else positions[videoId] = { position: Math.floor(currentTime), duration, savedAt: Date.now() };
        mySearchWriteVideoPositions(positions);
        positionSavedAt = Date.now();
    };
    /** @param {number} rate 使う速度（倍） */
    const setRate = (rate) => {
        player.command("setPlaybackRate", rate);
        try {
            localStorage.setItem(MY_PLAYBACK_RATE_KEY, String(rate));
        } catch {
            // 残せなくても、開いている間の速度は変わる
        }
    };
    /**
     * 速度の表示を状態に合わせ、保存された速度を頼む。
     * 配信は使える速度が全部届くので、選択肢の数では見分けられない。配信の印（live）は再生が始まるまで届かないが、
     * 配信の長さは再生が始まるまで 0 で届くので、長さが分かるまで速度を出さず、配信に保存された速度を頼まない。
     */
    const syncRate = () => {
        const { duration, live, playbackRate } = player.state;
        rateLabel.hidden = rates.length <= 1 || duration <= 0 || live;
        // プレーヤーの歯車で変えた速度も映す。選択肢に無い速度（カスタムの速度）は録画と同じく表示しない
        if (rates.includes(playbackRate) && rateSelect.value !== String(playbackRate)) rateSelect.value = String(playbackRate);
        if (rateLabel.hidden || savedRateApplied) return;
        savedRateApplied = true;
        const saved = myLoadPlaybackRate();
        if (saved !== 1 && rates.includes(saved)) player.command("setPlaybackRate", saved);
    };
    /** @param {KeyboardEvent} ev キー入力 */
    const onKey = (ev) => {
        // 応答が来ない・再生できないときは、スペースのスクロールなどを横取りしない
        if (controls.hidden) return;
        myPlayerShortcut(ev, {
            // 読み込み中（3）も止める側に数える。再生を頼んだ後の待ち時間は、動画要素の paused と同じく再生中として扱う
            togglePlay: () => player.command([1, 3].includes(player.state.playerState) ? "pauseVideo" : "playVideo"),
            seekBy: player.seekBy,
            stepRate: (step) => {
                if (rateLabel.hidden) return;
                const index = rates.indexOf(player.state.playbackRate);
                const next = rates[(index === -1 ? rates.indexOf(1) : index) + step];
                if (next !== undefined) setRate(next);
            },
            toggleMute: () => player.command(player.state.muted ? "unMute" : "mute"),
            toggleFullscreen: () => myToggleFullscreen(frame),
            seekToRatio: (ratio) => {
                // 配信の duration は長さではない（数か月分の値が届き、戻れる範囲はその一部だけ）
                if (player.state.duration > 0 && !player.state.live) player.seekTo(player.state.duration * ratio);
            },
        });
    };

    const player = mySearchBindPlayer(frame, () => {
        rates = MY_PLAYBACK_RATES.filter((rate) => player.state.availablePlaybackRates.includes(rate));
        for (const rate of rates) rateSelect.add(new Option(rate === 1 ? "標準" : `${rate}倍`, String(rate)));
        controls.hidden = false;
    });
    player.onInfo = () => {
        if (player.state.failed) controls.hidden = true;
        description.classList.toggle("is-seekable", !controls.hidden);
        syncRate();
        const { playerState } = player.state;
        const stopped = playerState !== lastPlayerState && (playerState === 0 || playerState === 2);
        lastPlayerState = playerState;
        if (stopped || (playerState === 1 && Date.now() - positionSavedAt >= MY_POSITION_SAVE_INTERVAL_MS)) savePosition();
    };
    query(".watchBack10", controls).addEventListener("click", () => player.seekBy(-10));
    query(".watchForward10", controls).addEventListener("click", () => player.seekBy(10));
    rateSelect.addEventListener("change", () => setRate(Number(rateSelect.value)));
    description.addEventListener("click", (event) => {
        const time = event.target instanceof Element ? event.target.closest(".watchTime") : null;
        if (!(time instanceof HTMLElement) || controls.hidden) return;
        const seconds = Number(time.dataset.seconds);
        if (player.state.duration > 0 && seconds > player.state.duration) return;
        player.seekTo(seconds);
        // 長い説明を読み進めた所から押すと飛んだ先が見えないので、プレーヤーが画面の外なら戻す（見えていれば動かない）
        frame.scrollIntoView({ block: "nearest" });
    });
    document.addEventListener("keydown", onKey, true);
    window.addEventListener("pagehide", savePosition);
    mySearchLeaveWatch = () => {
        savePosition();
        player.stop();
        document.removeEventListener("keydown", onKey, true);
        window.removeEventListener("pagehide", savePosition);
        mySearchLeaveWatch = null;
    };
}

/**
 * 視聴画面にプレーヤーと、その下の案内を置く。
 *
 * @param {HTMLElement} root 画面の入れ物
 * @param {string} source 埋め込みの URL
 * @param {string} title プレーヤーの読み上げ名
 * @param {string} allow プレーヤーに許す機能
 * @param {{videoId: string, start: number}|null} [youtube] YouTube の埋め込みのときの動画 ID と、URL に入れた始める
 *   位置（秒）。Twitch の埋め込みには公開された操作の仕組みが無いので、操作の列は YouTube にだけ出す
 */
function mySearchPlaceWatchFrame(root, source, title, allow, youtube = null) {
    const frame = document.createElement("iframe");
    frame.src = source;
    frame.title = title;
    frame.allow = allow;
    frame.allowFullscreen = true;
    // サイト全体の same-origin の方針は保ち、埋め込みにだけ必要な Referer を送る
    frame.referrerPolicy = "strict-origin-when-cross-origin";
    query(".watchPlayer", root).append(frame);
    const hint = query(".watchHint", root);
    hint.hidden = false;
    if (!youtube) return;
    // スクリプトが効くのは YouTube の埋め込みだけなので、Twitch には出さない
    const adSkip = adSkipNotice();
    if (adSkip) hint.before(adSkip);
    mySearchBindWatchControls(root, frame, youtube.videoId);
    if (youtube.start > 0) showToast(`前回の続き（${formatDuration(youtube.start)}）から再生します`);
}

/**
 * 視聴画面の説明の箱を描く。
 *
 * @param {HTMLElement} root 画面の入れ物
 * @param {string} stats 1 行目（再生回数と日時）
 * @param {string|null} [description] 説明文
 */
function mySearchRenderWatchDescription(root, stats, description = null) {
    const box = query(".watchDescription", root);
    box.innerHTML = `<p class="watchStats">${escapeHtml(stats)}</p>
        <div class="watchDescriptionText">${mySearchLinkify(description, mySearchTimeButtons)}</div>
        <button type="button" class="descriptionToggle" hidden>もっと見る</button>`;
    const text = query(".watchDescriptionText", box);
    const toggle = /** @type {HTMLButtonElement} */ (query(".descriptionToggle", box));
    // 3 行に収まる短い説明には「もっと見る」を出さない
    toggle.hidden = text.scrollHeight <= text.clientHeight;
    toggle.addEventListener("click", () => {
        const open = text.classList.toggle("is-open");
        toggle.textContent = open ? "一部だけ表示" : "もっと見る";
    });
}

/**
 * 「端末に保存」のリンク。動画ダウンロードの画面へ、URL と保存先を入れた状態で移る。
 *
 * @param {string} watchUrl 動画の URL
 * @returns {string} 差し込む HTML
 */
function mySearchDeviceSaveLink(watchUrl) {
    return `<a href="/my/download?${escapeHtml(String(new URLSearchParams({ url: watchUrl, destination: "device" })))}">端末に保存</a>`;
}

/**
 * 視聴画面の操作のボタンを描き、押したときの動きを付ける。
 *
 * @param {HTMLElement} root 画面の入れ物
 * @param {any} video 動画の詳細（/api/my/youtube/videos/{videoId} の応答）
 * @param {Set<string>} subscribed 自分が購読している YouTube のチャンネル ID
 * @param {string} watchUrl 動画の URL
 */
function mySearchRenderWatchActions(root, video, subscribed, watchUrl) {
    const actions = query(".watchActions", root);
    const saved = video.saved && video.recordingId
        ? `<a href="/my/watch/${encodeURIComponent(video.recordingId)}">アーカイブで見る</a>`
        : '<button type="button" class="saveBtn">サービスに保存</button>';
    actions.innerHTML = `${externalLink("YouTube で開く", watchUrl)}
        ${saved}
        ${mySearchDeviceSaveLink(watchUrl)}
        ${mySearchWatchChannel(video, subscribed)}`;

    actions.querySelector(".saveBtn")?.addEventListener("click", async (event) => {
        const button = /** @type {HTMLButtonElement} */ (event.currentTarget);
        button.disabled = true;
        try {
            await apiPost("/api/my/downloads", { url: watchUrl });
            if (!actions.isConnected) return;
            clearError();
            showToast("保存を始めました。終わるとアーカイブに出ます");
        } catch (e) {
            // 配信中・同時に 2 件目・空き容量不足は、サーバーの文言をそのまま出す
            if (actions.isConnected) showError(errorMessage(e), { reveal: true });
            button.disabled = false;
        }
    });
    actions.querySelector(".watchChannelBtn")?.addEventListener("click", async (event) => {
        const button = /** @type {HTMLButtonElement} */ (event.currentTarget);
        button.disabled = true;
        try {
            await apiPost("/api/my/channels", {
                platform: "YOUTUBE", channelInput: video.channelId, channelName: video.channelTitle,
            });
            // 画面を離れた後でも購読は変わっているので、下で戻る前にメニューの行を合わせる
            myLoadNavChannels({ force: true });
            if (!actions.isConnected) return;
            clearError();
            showToast(`${video.channelTitle} を登録チャンネルに追加しました`);
            actions.querySelector(".serviceWatchLamp")?.remove();
            button.outerHTML = MY_SEARCH_SUBSCRIBED_LAMP;
        } catch (e) {
            if (actions.isConnected) showError(errorMessage(e), { reveal: true });
            button.disabled = false;
        }
    });
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
        // 見えないので、開いた時点で止める。自動再生はしないので、開いただけでは音は出ない。録画の続きは、ミニプレーヤーの ▶ で
        // 利用者が再開できる
        myDockVideo().pause();
        const videoId = match[1];
        let back = "/my/search";
        try { back = sessionStorage.getItem(MY_SEARCH_LAST_URL_KEY) || back; } catch { /* 検索画面の最初へ戻す */ }
        root.innerHTML = mySearchWatchLayout({
            before: `<p class="watchBack"><a class="searchBack" href="${escapeHtml(back)}">← 検索結果に戻る</a></p>`,
            after: mySearchAttribution,
        });
        // 続きの位置は seekTo でなく start で渡す。頭出しの状態で seekTo を呼ぶと、再生が始まってしまうため。
        // allow の autoplay は開いたときの自動再生には使わない（URL に autoplay=1 が無いので始まらない）。これが無いと、
        // 操作の列・キー・説明の時刻からの再生を、利用者の操作の後でもブラウザが断る（押したのは親のページで、iframe の
        // 中ではないため）
        const start = mySearchResumeStart(videoId);
        mySearchPlaceWatchFrame(root, youtubeEmbedUrl(videoId, { start }), "YouTube の動画",
            "autoplay; encrypted-media; fullscreen; picture-in-picture", { videoId, start });
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
        query(".watchChannel", root).innerHTML = mySearchChannel(video);
        mySearchRenderWatchActions(root, video, subscribed, watchUrl);
        mySearchRenderWatchDescription(root, [mySearchViews(video.viewCount), formatInstant(video.publishedAt)].filter(Boolean).join("・"),
            video.description);
    },
    leave() {
        mySearchLeaveWatch?.();
    },
};

/**
 * 動画・配信の視聴画面の、状態の 1 行。
 *
 * @param {any} video /api/my/videos/{id} の応答
 * @returns {string} 配信中なら「配信中」、配信予定なら開始予定、それ以外は公開日時
 */
function myVideoWatchWhen(video) {
    return video.state === "LIVE" ? "配信中" : onlineVideoStatus(video).when;
}

/**
 * チャンネルのアイコンと名前。名前は配信元のチャンネルへのリンクにする（規約については mySearchChannel）。
 *
 * @param {any} video /api/my/videos/{id} の応答
 * @param {string|null} iconUrl アイコンの URL
 * @returns {string} 差し込む HTML
 */
function myVideoWatchChannel(video, iconUrl) {
    return `<span class="channelWithIcon">${channelIcon(iconUrl)}${externalLink(video.channelName, video.channelUrl)}</span>`;
}

/**
 * YouTube の動画の詳細を読み、説明文・再生回数・アイコン・操作のボタンを埋める。
 *
 * 再生はできるので、読めなくても（429・503・404 など）エラー帯には出さず、手元の情報で描いたままにする。
 * 回数を使い切った日や API キーの無い環境で、再生の画面にエラーが出続けないようにするため。
 *
 * @param {HTMLElement} root 画面の入れ物
 * @param {any} video /api/my/videos/{id} の応答
 * @returns {Promise<void>} 描き終えたら解決する
 */
async function myVideoWatchLoadDetail(root, video) {
    const box = query(".watchDescription", root);
    /** @type {any} */
    let detail;
    try {
        detail = await apiGet(`/api/my/youtube/videos/${encodeURIComponent(video.id.replace(/^YOUTUBE_/, ""))}`);
    } catch {
        return;
    }
    if (!box.isConnected) return;
    const subscribed = await mySearchSubscribedChannelIds();
    if (!box.isConnected) return;
    if (detail.channelIconUrl && detail.channelIconUrl !== video.channelIconUrl) {
        query(".watchChannel", root).innerHTML = myVideoWatchChannel(video, detail.channelIconUrl);
    }
    mySearchRenderWatchActions(root, detail, subscribed, video.watchUrl);
    mySearchRenderWatchDescription(root, [mySearchViews(detail.viewCount), myVideoWatchWhen(video)].filter(Boolean).join("・"),
        detail.description);
}

/**
 * 右の列の 1 件。全体を 1 つのリンクにする。
 *
 * @param {any} video /api/videos の 1 件
 * @returns {HTMLElement} 差し込む要素
 */
function myVideoWatchNextItem(video) {
    const { state, when } = onlineVideoStatus(video);
    const link = document.createElement("a");
    link.className = "watchNext";
    link.href = `/my/videos/watch/${encodeURIComponent(video.id)}`;
    link.innerHTML = `<span class="watchNextThumb">${video.thumbnailRetryExhausted ? "" : `<img src="${escapeHtml(video.thumbnailUrl)}" alt="" loading="lazy">`}</span>
        <span class="watchNextInfo">
          <span class="watchNextTitle">${escapeHtml(video.title)}</span>
          <span class="muted">${escapeHtml(video.channelName)}</span>
          <span class="muted watchNextStatus"><span>${escapeHtml(state)}</span><span>${escapeHtml(when)}</span></span>
        </span>`;
    // 読めない画像の枠は、代わりの地の色のままにする
    link.querySelector("img")?.addEventListener("error", (event) => {
        /** @type {HTMLElement} */ (event.currentTarget).remove();
    });
    return link;
}

/**
 * 右の列（同じチャンネルの動画）を読んで描く。補助の列なので、読めないとき・並べる動画が無いときは列ごと消して
 * 1 列の配置にし、エラー帯には出さない。
 *
 * @param {HTMLElement} root 画面の入れ物
 * @param {any} video /api/my/videos/{id} の応答
 * @returns {Promise<void>} 描き終えたら解決する
 */
async function myVideoWatchLoadSecondary(root, video) {
    const aside = query(".watchSecondary", root);
    /** @type {any[]} */
    let videos = [];
    try {
        const page = await apiGet(`/api/videos?channelId=${encodeURIComponent(video.channelId)}&size=13`);
        videos = page.content.filter(/** @param {any} other */ (other) => other.id !== video.id && other.playable
            && other.contentKind !== "UPCOMING" && other.contentKind !== "MISSING");
    } catch {
        // 列を出さない
    }
    if (!aside.isConnected) return;
    if (videos.length === 0) {
        aside.remove();
        return;
    }
    aside.innerHTML = '<h2 id="watchSecondaryHeading">同じチャンネルの動画</h2><div class="watchNextList"></div>';
    query(".watchNextList", aside).append(...videos.map(myVideoWatchNextItem));
    aside.hidden = false;
}

/**
 * 動画・配信のカードから開く視聴画面。録画していない動画を、公式の埋め込みプレーヤーで再生する。
 *
 * 一覧の上に出すダイアログをやめて通常の画面にしたのは、サービスの枠の中で再生し、新しいタブで開けて、ブラウザの
 * 「戻る」で一覧の同じ位置へ戻れるようにするため（#876）。カードを押して開く画面なので自動再生する
 * （検索から開く画面は自動再生しない。理由は mySearchWatchView）。
 * 動画本体の読み取りの失敗だけをエラー帯に出す。詳細と右の列は補助なので、失敗しても再生と題名は残す。
 * @type {MyView}
 */
const myVideoWatchView = {
    title: "再生",
    nav: "/my/videos",
    async render(root, match) {
        // 録画と音が重なるので止める（理由は mySearchWatchView）。録画の続きは、ミニプレーヤーの ▶ で利用者が再開できる
        myDockVideo().pause();
        root.innerHTML = mySearchWatchLayout({ secondary: true });
        const heading = query("h1", root);
        /** 動画を出せないときは、プレーヤーと右の列の場所を片付ける。 */
        const clearPlayer = () => {
            query(".watchPlayer", root).remove();
            query(".watchSecondary", root).remove();
        };
        /** @type {any} */
        let video;
        try {
            // 無い・見る権限の無い動画は 404 で返る。ほかの失敗と分けて伝えるため、状態コードを見られるよう apiGet を使わない
            const res = await authenticatedFetch(`/api/my/videos/${encodeURIComponent(match[1])}`);
            if (res.status === 404) {
                if (!heading.isConnected) return;
                clearPlayer();
                heading.textContent = "この動画は見られません";
                heading.insertAdjacentHTML("afterend", '<p><a href="/my/videos">登録チャンネルへ戻る</a></p>');
                return;
            }
            if (!res.ok) throw new Error(await extractError(res));
            video = await res.json();
        } catch (e) {
            if (heading.isConnected) {
                clearPlayer();
                heading.textContent = "動画の情報を読み込めませんでした";
                showError(errorMessage(e));
            }
            return;
        }
        if (!heading.isConnected) return;

        document.title = `${video.title} - YouTube Live Monitor`;
        heading.textContent = video.title;
        const youtube = video.platform === "YOUTUBE";
        const videoId = video.id.replace(/^YOUTUBE_/, "");
        const start = video.playable && youtube ? mySearchResumeStart(videoId) : 0;
        const source = video.playable ? embeddedVideoUrl(video.watchUrl, { start }) : null;
        if (source) {
            mySearchPlaceWatchFrame(root, source, video.title, "autoplay; encrypted-media; fullscreen; picture-in-picture",
                youtube ? { videoId, start } : null);
        } else {
            query(".watchPlayer", root).outerHTML = emptyState("この動画はここでは再生できません", "配信元で開いてください。");
        }
        query(".watchChannel", root).innerHTML = myVideoWatchChannel(video, video.channelIconUrl);
        query(".watchActions", root).innerHTML = externalLink(`${video.platformLabel} で開く`, video.watchUrl)
            + (youtube ? mySearchDeviceSaveLink(video.watchUrl) : "");
        mySearchRenderWatchDescription(root, myVideoWatchWhen(video));
        // YouTube API サービスのデータを出す画面に要る表示（mySearchAttribution）。Twitch の動画には当てはまらない
        if (youtube) query(".watchPage", root).insertAdjacentHTML("afterend", mySearchAttribution);
        await Promise.all([youtube ? myVideoWatchLoadDetail(root, video) : null, myVideoWatchLoadSecondary(root, video)]);
    },
    leave() {
        mySearchLeaveWatch?.();
    },
};
