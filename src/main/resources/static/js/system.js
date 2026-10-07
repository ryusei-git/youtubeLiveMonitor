// @ts-check
// 画面固有の状態をグローバルへ漏らさないため、全体を即時実行関数で包む（audit.js と同じ）。
// 各画面のスクリプトは <script> で読み込まれ、既定では common.js と同じスコープを共有するため。
(() => {
    /**
     * 左メニューのサービス 1 件（Java の {@code SystemProcessesResponse.ServiceStatus}）。
     * @typedef {object} HubServiceStatus
     * @property {string} name メニューに出す名前
     * @property {string|null} url トップ画面の URL。無ければ null
     * @property {"RUNNING"|"STOPPED"|"UNKNOWN"} status 動いているか
     * @property {boolean} self この画面を動かしているサービス自身か
     */
    /**
     * {@code GET /api/system/processes} の応答のうち、この画面の骨組みで使う分。
     * @typedef {object} HostProcesses
     * @property {string} hostName 端末名
     * @property {number} uptimeSeconds 端末の稼働時間（秒）
     * @property {HubServiceStatus[]} services 左メニューのサービス。先頭はこのサービス自身
     */

    /**
     * 最終更新を「更新できていません」に変えるまでの時間（ミリ秒）。10 秒ごとの読み直しが
     * 1 回失敗しただけでは出さず、30 秒（3 回分）読めなかったら出す。一時の失敗（通信の瞬断
     * など）で毎回警告を出すと、警告に慣れて本当に止まったときに気付けなくなるため
     * （レビュー #7）。
     */
    const STALE_MILLIS = 30_000;

    /** 警告のアイコン（三角に !）。最終更新が古いことを、色だけでなく形でも伝えるため。 */
    const WARNING_ICON = '<svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor"'
        + ' stroke-width="1.5" stroke-linejoin="round" stroke-linecap="round" aria-hidden="true">'
        + '<path d="M8 1.8l6.5 11.7h-13zM8 6.3v3.2M8 11.5v.2"/></svg>';

    /**
     * 新しいタブで開くことを示すアイコン。押す前に、この画面から離れないことが分かるように
     * する（レビュー #55）。
     */
    const EXTERNAL_ICON = '<svg class="navExternal" viewBox="0 0 16 16" fill="none"'
        + ' stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" stroke-linecap="round"'
        + ' aria-hidden="true"><path d="M9.5 1.5h5v5M14.5 1.5L8 8M12.5 9.5v5h-11v-11h5"/></svg>';

    /**
     * サービスの状態ごとの名前・印・形。色だけで見分けさせず、チェック・横棒・点線の丸と
     * 形でも分ける（色覚の違いがあっても読めるように。レビュー #35）。
     * @type {Record<HubServiceStatus["status"], {label: string, className: string, shape: string}>}
     */
    const SERVICE_STATE = {
        RUNNING: {
            label: "稼働中", className: "is-running",
            shape: '<circle cx="8" cy="8" r="6.5"/><path d="M5 8l2 2 4-4"/>',
        },
        STOPPED: {
            label: "停止済み", className: "is-stopped",
            shape: '<circle cx="8" cy="8" r="6.5"/><path d="M5 8h6"/>',
        },
        UNKNOWN: {
            label: "状態不明", className: "is-unknown",
            shape: '<circle cx="8" cy="8" r="6.5" stroke-dasharray="2 2"/>',
        },
    };

    /** @type {{measuredAt: string} | null} {@code /api/dashboard/resources} の応答。未読は null */
    let resources = null;
    /** @type {HostProcesses | null} {@code /api/system/processes} の応答。未読なら null */
    let system = null;
    /** 2 つの API を両方読めた時刻（ミリ秒）。まだなら 0 */
    let lastSuccessAt = 0;
    /** 1 回でも読み込みを終えたか。最初の読み込みの最中は、まだ失敗とは言えないので警告しない */
    let settled = false;
    /** 読み込み中か。自動の読み直しの途中で「表示を更新」を押されても、同じ要求を重ねない */
    let loading = false;
    /** 左メニューに最後に描いた HTML。変わったときだけ描き直す（理由は renderNav） */
    let lastNavHtml = "";

    const sideNav = el("sideNav");
    const navToggle = buttonEl("navToggle");
    const navScrim = el("navScrim");
    const main = el("mainContent");
    const refreshButton = buttonEl("refreshButton");
    /** 左メニューを帯の下に重ねて開け閉めする幅。system.css の @media と同じ値にする */
    const narrow = window.matchMedia("(max-width: 760px)");

    /**
     * 端末の稼働時間を、桁に合わせて 2 つまでの単位で出す。日をまたいで動いている端末で秒まで
     * 並べても読む必要が無く、10 秒ごとに数字が動いて目を引くだけのため。
     * @param {number} seconds 秒
     * @returns {string} 「N 秒」「N 分」「N 時間 M 分」「N 日 M 時間」
     */
    function formatElapsed(seconds) {
        if (seconds < 60) return `${Math.floor(seconds)} 秒`;
        const minutes = Math.floor(seconds / 60);
        if (minutes < 60) return `${minutes} 分`;
        const hours = Math.floor(minutes / 60);
        if (hours < 24) return `${hours} 時間 ${minutes % 60} 分`;
        return `${Math.floor(hours / 24)} 日 ${hours % 24} 時間`;
    }

    /**
     * 時刻を HH:mm（seconds が true なら HH:mm:ss）にする。日付を出さないのは、1 分ごとの
     * 計測の値を見る画面で、今日の値かを疑う場面が無いため。
     * @param {string}  value   サーバーの日時（ISO 形式。時差なしの LocalDateTime）
     * @param {boolean} [seconds] 秒まで出すか
     * @returns {string} 整形した時刻
     */
    function formatClock(value, seconds = false) {
        const date = new Date(value);
        const parts = [date.getHours(), date.getMinutes(), ...(seconds ? [date.getSeconds()] : [])];
        return parts.map((n) => String(n).padStart(2, "0")).join(":");
    }

    /**
     * 見出しの端末名・稼働時間・最終更新を描く。
     *
     * <p>最終更新は、画面が読みに行った時刻ではなく計測した時刻（{@code measuredAt}）を出す。
     * 値は 1 分ごとに計測するので、読みに行った時刻を出すと、10 秒ごとに新しくなったように
     * 見えて、値も新しいと誤解させるため（レビュー #40）。読めなくなって古くなったときは、
     * 警告と「更新できていません」を前に付け、見ている値がいつのものかは残す（レビュー #7）。
     */
    function renderHeader() {
        el("hostName").textContent = system ? system.hostName : "-";
        el("uptime").textContent = system ? formatElapsed(system.uptimeSeconds) : "-";
        const updatedAt = el("updatedAt");
        const measured = resources ? formatClock(resources.measuredAt, true) : "";
        const stale = settled && (lastSuccessAt === 0 || Date.now() - lastSuccessAt > STALE_MILLIS);
        updatedAt.classList.toggle("is-stale", stale);
        if (stale) {
            const message = measured ? `更新できていません · ${measured}` : "更新できていません";
            updatedAt.innerHTML = WARNING_ICON + escapeHtml(message);
        } else {
            updatedAt.textContent = measured || "-";
        }
    }

    /**
     * 相対の URL（{@code /index.html} など）を、開く先が分かるように絶対の URL にする。
     * サーバーは URL の書き出しだけを確かめるので、{@code http://ryusei:99999/}（ポートの打ち間違い）
     * のような解釈できない URL も届く。そのとき例外で左メニューの全件を描けなくならないよう、
     * 書かれたままを返す。
     * @param {string} url サービスの URL
     * @returns {string} 絶対の URL。解釈できなければ url のまま
     */
    function absoluteUrl(url) {
        try {
            return new URL(url, location.href).href;
        } catch {
            return url;
        }
    }

    /**
     * 左メニューのサービスを描く。
     *
     * <p>描いた HTML が前と同じなら描き直さない。10 秒ごとに描き直すと、キーボードでリンクに
     * フォーカスを置いている間にリンクが作り直され、フォーカスが消えるため。
     * 停止済みのサービスもリンクを残すのは、状態の判定（作業フォルダーでの照合）が外れて
     * いても、画面を開いて確かめられるようにするため（レビュー #55）。
     */
    function renderNav() {
        const html = system ? system.services.map((service) => {
            const state = SERVICE_STATE[service.status];
            const icon = `<svg class="navStatus ${state.className}" viewBox="0 0 16 16" fill="none"`
                + ' stroke="currentColor" stroke-width="1.5" stroke-linecap="round"'
                + ` stroke-linejoin="round" role="img" aria-label="${state.label}">`
                + `${state.shape}</svg>`;
            const name = `<span class="navName">${escapeHtml(service.name)}</span>`;
            if (!service.url) {
                return `<li><span class="navItem" title="${state.label} · 画面の無いサービスです">`
                    + `${icon}${name}</span></li>`;
            }
            const title = `${state.label} · ${absoluteUrl(service.url)} を開きます`;
            return `<li><a class="navItem" href="${escapeHtml(service.url)}" target="_blank"`
                + ` rel="noopener noreferrer" title="${escapeHtml(title)}">`
                + `${icon}${name}${EXTERNAL_ICON}</a></li>`;
        }).join("") : '<li><span class="navItem">取得できませんでした</span></li>';
        if (html === lastNavHtml) return;
        el("serviceNav").innerHTML = html;
        lastNavHtml = html;
    }

    /**
     * 計測の値と左メニューの状態を読み直す。片方だけ失敗したときは、読めた方だけ描く
     * （もう片方は前の値のまま）。最終更新の警告は、両方を読めた時刻から数える。
     *
     * <p>読んでいる間はボタンを回す。押したのに何も変わらないように見えないよう、読み終えた
     * ことを最終更新の書き換えと合わせて見せるため（レビュー #43）。止めるのは disabled でなく
     * aria-disabled にする。disabled にするとフォーカスがボタンから外れ、キーボードで押した人や、
     * ボタンにフォーカスを置いている人が、10 秒ごとの読み直しのたびに位置を失うため（押しても
     * loading の間は何もしないので、押せること自体は問題にならない）。
     */
    async function refresh() {
        if (loading) return;
        loading = true;
        const icon = refreshButton.innerHTML;
        refreshButton.setAttribute("aria-disabled", "true");
        refreshButton.innerHTML = '<span class="spinner" aria-hidden="true"></span>';
        try {
            const [resourcesResult, systemResult] = await Promise.allSettled([
                apiGet("/api/dashboard/resources"),
                apiGet("/api/system/processes"),
            ]);
            if (resourcesResult.status === "fulfilled") resources = resourcesResult.value;
            if (systemResult.status === "fulfilled") system = systemResult.value;
            if (resourcesResult.status === "fulfilled" && systemResult.status === "fulfilled") {
                lastSuccessAt = Date.now();
            }
            settled = true;
            renderHeader();
            renderNav();
        } finally {
            refreshButton.innerHTML = icon;
            refreshButton.removeAttribute("aria-disabled");
            loading = false;
        }
    }

    /**
     * 狭い画面の左メニューを開け閉めする。
     *
     * <p>開いている間は本文を inert にし、Tab で幕の後ろへ抜けないようにする。閉じている間は
     * 左メニューを inert にし、画面の外へ隠れた項目へ Tab で入らないようにする（レビュー #31）。
     * 広い画面では左メニューが常に見えているので inert にしない。
     *
     * @param {boolean} open           開くなら true
     * @param {boolean} [restoreFocus] 閉じた後に ≡ へフォーカスを戻すか。幕・Esc・項目で閉じた
     *                                 とき、フォーカスが隠れた項目や消えた幕に残らないように
     *                                 するため
     */
    function setNavOpen(open, restoreFocus = false) {
        sideNav.classList.toggle("is-open", open);
        navScrim.hidden = !open;
        main.inert = open;
        sideNav.inert = narrow.matches && !open;
        navToggle.setAttribute("aria-expanded", String(open));
        navToggle.setAttribute("aria-label", open ? "メニューを閉じる" : "メニューを開く");
        if (open) query("a", sideNav).focus();
        else if (restoreFocus) navToggle.focus();
    }

    /**
     * 開いた「？」の吹き出しが右へはみ出す分だけ左へずらす。「？」は見出しの右端に来ることが
     * あり、そのまま開くと画面の外へ切れて読めないため。
     * @param {HTMLDetailsElement} help 開いた「？」
     */
    function placePopover(help) {
        const body = query(".popoverBody", help);
        body.style.left = "";
        const overflow = body.getBoundingClientRect().right
            - (document.documentElement.clientWidth - 16);
        if (overflow > 0) body.style.left = `calc(-12px - ${overflow}px)`;
    }

    /** @returns {HTMLDetailsElement[]} 開いている「？」 */
    const openHelps = () => [...document.querySelectorAll("details.help[open]")]
        .filter((help) => help instanceof HTMLDetailsElement);

    // 「？」は、外を押す・画面の大きさが変わる・フォーカスが外へ出る・Esc のどれでも閉じる。
    // 開いたままだと下の値に重なって読めないため（レビュー #49）。
    document.querySelectorAll("details.help").forEach((help) => {
        if (!(help instanceof HTMLDetailsElement)) return;
        help.addEventListener("toggle", () => { if (help.open) placePopover(help); });
        help.addEventListener("focusout", (event) => {
            // 吹き出しの文を押したとき（フォーカスの移り先が無い）は閉じない。
            // 外を押したときは下の click で閉じる
            const next = event.relatedTarget;
            if (next instanceof Node && !help.contains(next)) help.open = false;
        });
    });
    document.addEventListener("click", (event) => {
        for (const help of openHelps()) {
            if (!(event.target instanceof Node && help.contains(event.target))) help.open = false;
        }
    });
    window.addEventListener("resize", () => { for (const help of openHelps()) help.open = false; });

    document.addEventListener("keydown", (event) => {
        if (event.key !== "Escape") return;
        const helps = openHelps();
        if (helps.length) {
            for (const help of helps) {
                help.open = false;
                query("summary", help).focus();
            }
        } else if (sideNav.classList.contains("is-open")) {
            setNavOpen(false, true);
        }
    });

    navToggle.addEventListener("click",
        () => setNavOpen(!sideNav.classList.contains("is-open"), true));
    navScrim.addEventListener("click", () => setNavOpen(false, true));
    sideNav.addEventListener("click", (event) => {
        // 「端末の状態」は同じ画面の中へ移るだけなので、閉じないとメニューが本文に重なったまま
        // になる
        const link = event.target instanceof Element && event.target.closest("a");
        if (!link || !sideNav.classList.contains("is-open")) return;
        setNavOpen(false);
        // #mainContent へのリンクは、既定の動き（クリックの処理の後）でフォーカスが本文へ移る。
        // それより後で ≡ へ戻すため、既定の動きが終わるのを待つ
        window.setTimeout(() => navToggle.focus());
    });
    // 広い画面へ変わったら閉じて inert を外す（広い画面では左メニューが常に見えるため）。
    // 狭い画面へ変わったときは、閉じた状態の inert を付ける
    narrow.addEventListener("change", () => setNavOpen(false));
    setNavOpen(false);

    refreshButton.addEventListener("click", () => refresh());
    refresh();
    startVisibleRefresh(refresh, 10_000);
    // 応答が返らないまま読み込みが止まると（サーバーが固まったときなど）、refresh からは
    // renderHeader が呼ばれない。それでも古くなったことを出せるよう、別に見直す。
    // 隠れている間は見直さない。読まないので必ず古くなり、戻った瞬間に、読み直しが返るまで
    // 「更新できていません」が一瞬出るため
    window.setInterval(() => {
        if (document.visibilityState === "visible") renderHeader();
    }, 5_000);
})();
