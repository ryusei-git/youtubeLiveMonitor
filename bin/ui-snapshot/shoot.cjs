/*
 * 利用者の画面を PC と iPhone の大きさで撮る。bin/ui-snapshot.sh shoot から呼ばれる（直接は動かさない）。
 * 確認用インスタンスの URL・利用者・token は環境変数（UI_SNAPSHOT_*）で受け取る。
 *
 * 【この形にした理由】
 *   - PC は Chromium、iPhone は WebKit で撮る。iPhone の Safari は WebKit なので、文字の折り返し・フォーム部品の形が
 *     Chromium で狭めただけの画面とは違う。iPhone は isMobile・hasTouch・deviceScaleFactor 3 にして、
 *     viewport の meta・タッチ向けのメディアクエリ（hover: none など）・高解像度の描き方を実機に寄せる
 *   - 同じコードを 2 回撮って差が 0 になるよう、撮るたびに変わるものを止める
 *       時計: ブラウザの Date を FIXED_NOW に止める（「〜前」「あと〜」「最終表示更新」）。seed.sql の日時はこの時刻に合わせてある
 *       タイムゾーン・言語: 端末の設定に左右されないよう Asia/Tokyo・ja-JP に決める
 *       アニメーション: screenshot の animations: "disabled" と prefers-reduced-motion、入力欄のカーソルは caret: "hide"
 *       外部への通信: 確認用インスタンス以外への要求は止める（チャンネルのアイコン・外部の画像が、届く・届かないで変わらないように）
 *       角の描き方: Chromium の部分的な描き直しを止める（理由は CHROMIUM_ARGS）
 *   - 画面は 1 枚の全体（fullPage）で撮る。iPhone では下の方の崩れも見たいし、PC の比較でも見えていない部分の差を拾える
 *   - 画面ごとに読み込みを待ってから撮る（通信が落ち着く → 「読み込み中...」が消える → フォント → 2 フレーム）
 * 管理画面を足すときは、SCREENS に管理者でログインする種類を足し、login() を管理者用のログイン画面に分ければよい。
 */
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { chromium, webkit, devices } = require("playwright");

const BASE = required("UI_SNAPSHOT_BASE");
const USER = required("UI_SNAPSHOT_USER");
const PASSWORD = required("UI_SNAPSHOT_PASSWORD");
const REGISTER_TOKEN = required("UI_SNAPSHOT_REGISTER_TOKEN");
const RESET_TOKEN = required("UI_SNAPSHOT_RESET_TOKEN");
const OUT = process.argv[2];

/**
 * iPhone の大きさを撮るブラウザ。既定は WebKit。Windows で Smart App Control が有効だと、署名の無い Playwright の WebKit は
 * DLL の読み込みを止められて起動できない（実際に発生した。2026-10-04、イベントログ CodeIntegrity の 3077）。その端末では
 * UI_SNAPSHOT_IPHONE_ENGINE=chromium で、Chromium に大きさ・タッチ・解像度だけを合わせて撮る。WebKit とは文字の折り返しや
 * フォーム部品の形が違うので、出力先のディレクトリ名に -chromium を付けて、WebKit で撮った画像と取り違えないようにする。
 */
const IPHONE_ENGINE = process.env.UI_SNAPSHOT_IPHONE_ENGINE || "webkit";
if (IPHONE_ENGINE !== "webkit" && IPHONE_ENGINE !== "chromium") {
    console.error(`エラー: UI_SNAPSHOT_IPHONE_ENGINE は webkit か chromium です（${IPHONE_ENGINE}）`);
    process.exit(1);
}
const IPHONE_SUFFIX = IPHONE_ENGINE === "webkit" ? "" : `-${IPHONE_ENGINE}`;
/** iPhone の Safari の User-Agent。画面は UA で分けていないが、実機と同じ値にしておく。 */
const IPHONE_USER_AGENT = devices["iPhone 15"].userAgent;

/** 画面の「今」。seed.sql の日時（2026-09）より後で、配信予定（10/1 20:00・10/2 21:00）より前にしてある。 */
const FIXED_NOW = new Date("2026-10-01T12:00:00+09:00");

/**
 * 撮る大きさ。名前がそのまま出力先のディレクトリ名になる。pc: true の画像だけを diff.cjs が差の判定に使う。
 * iPhone の幅と高さは、それぞれ SE（第 2・3 世代）・15・15 Pro Max の CSS ピクセル。
 */
const SIZES = [
    { name: "pc-1280x800", pc: true, browser: "chromium", viewport: { width: 1280, height: 800 } },
    { name: "pc-1920x1080", pc: true, browser: "chromium", viewport: { width: 1920, height: 1080 } },
    { name: `iphone-375x667${IPHONE_SUFFIX}`, pc: false, browser: IPHONE_ENGINE, viewport: { width: 375, height: 667 }, mobile: true },
    { name: `iphone-393x852${IPHONE_SUFFIX}`, pc: false, browser: IPHONE_ENGINE, viewport: { width: 393, height: 852 }, mobile: true },
    { name: `iphone-430x932${IPHONE_SUFFIX}`, pc: false, browser: IPHONE_ENGINE, viewport: { width: 430, height: 932 }, mobile: true },
];

/** 撮る画面。auth は利用者でログインしてから開くか。録画 9001 は seed.sql の決め打ちの ID。 */
const SCREENS = [
    { name: "my-top", path: "/my", auth: true },
    { name: "my-archive-card", path: "/my/archive", auth: true },
    { name: "my-archive-list", path: "/my/archive?view=list", auth: true },
    { name: "my-watch", path: "/my/watch/9001", auth: true },
    { name: "my-channels", path: "/my/channels", auth: true },
    { name: "my-settings-notifications", path: "/my/settings/notifications", auth: true },
    { name: "my-settings-account", path: "/my/settings/account", auth: true },
    { name: "my-search", path: "/my/search", auth: true },
    { name: "my-discover", path: "/my/discover", auth: true },
    { name: "my-videos", path: "/my/videos", auth: true },
    { name: "my-download", path: "/my/download", auth: true },
    { name: "user-login", path: "/userLogin.html", auth: false },
    { name: "register", path: `/register.html?token=${encodeURIComponent(REGISTER_TOKEN)}`, auth: false },
    { name: "password-reset", path: `/password-reset.html?token=${encodeURIComponent(RESET_TOKEN)}`, auth: false },
];

/** 「読み込み中...」が消えるまで待つ上限。消えなくても撮る（消えない画面そのものが見たいこともある）が、名前を出す。 */
const SETTLE_TIMEOUT_MS = 10_000;

function required(name) {
    const value = process.env[name];
    if (!value) {
        console.error(`エラー: 環境変数 ${name} がありません（bin/ui-snapshot.sh shoot から動かしてください）`);
        process.exit(1);
    }
    return value;
}

/** 撮影に失敗した画面。ログイン・読み込みに失敗したら、その画面名を添えて投げる。 */
class ShotError extends Error {}

async function newContext(browser, size) {
    const context = await browser.newContext({
        viewport: size.viewport,
        deviceScaleFactor: size.mobile ? 3 : 1,
        isMobile: Boolean(size.mobile),
        hasTouch: Boolean(size.mobile),
        locale: "ja-JP",
        timezoneId: "Asia/Tokyo",
        reducedMotion: "reduce",
        colorScheme: "light",
        ...(size.mobile ? { userAgent: IPHONE_USER_AGENT } : {}),
    });
    // 確認用インスタンス以外への通信は止める（外部の画像・アイコン）
    await context.route((url) => !url.href.startsWith(BASE), (route) => route.abort());
    return context;
}

async function newPage(context) {
    const page = await context.newPage();
    // Date だけを止める（setTimeout・setInterval は今までどおり動くので、画面の読み込みは止まらない）
    await page.clock.setFixedTime(FIXED_NOW);
    return page;
}

async function login(context, size) {
    const page = await newPage(context);
    try {
        await page.goto(`${BASE}/userLogin.html`);
        await page.fill("#username", USER);
        await page.fill("#password", PASSWORD);
        await Promise.all([
            page.waitForURL((url) => url.pathname === "/my" || url.pathname.startsWith("/my/"), { timeout: 15_000 }),
            page.click("form button[type=submit]"),
        ]);
    } catch (e) {
        throw new ShotError(`${size.name}: 利用者 ${USER} でログインできませんでした（${e.message.split("\n")[0]}）`);
    } finally {
        await page.close();
    }
}

async function settle(page, label) {
    await page.waitForLoadState("networkidle");
    try {
        await page.waitForFunction(() => !document.body.innerText.includes("読み込み中"), null,
            { timeout: SETTLE_TIMEOUT_MS });
    } catch {
        console.warn(`  注意: ${label} は ${SETTLE_TIMEOUT_MS / 1000} 秒待っても「読み込み中」が残っています（そのまま撮ります）`);
    }
    await page.waitForLoadState("networkidle");
    await page.evaluate(async () => {
        await document.fonts.ready;
        await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    });
}

async function shoot(context, size, screen) {
    const label = `${size.name}/${screen.name}`;
    const page = await newPage(context);
    try {
        const response = await page.goto(`${BASE}${screen.path}`);
        if (!response || !response.ok()) {
            throw new ShotError(`${label}: 画面を読み込めませんでした（${response ? response.status() : "応答なし"}）`);
        }
        const pathname = new URL(page.url()).pathname;
        if (screen.auth && /Login\.html$/.test(pathname)) {
            throw new ShotError(`${label}: ログイン画面へ戻されました（ログインが切れています）`);
        }
        await settle(page, label);
        const file = path.join(OUT, size.name, `${screen.name}.png`);
        // 動画は塗りつぶす。録画のファイルが無いので、ブラウザ標準の操作部品が読み込み中の輪を回し続け、
        // 撮るたびに輪の向きが変わる（CSS のアニメーションではないので animations: "disabled" では止まらない。実際に発生した）。
        // 塗るのは要素の箱だけなので、動画の位置・大きさが変われば差として出る
        await page.screenshot({
            path: file, fullPage: true, animations: "disabled", caret: "hide",
            mask: [page.locator("video")], maskColor: "#202020",
        });
        console.log(`  ${label}`);
    } catch (e) {
        if (e instanceof ShotError) throw e;
        throw new ShotError(`${label}: ${e.message.split("\n")[0]}`);
    } finally {
        await page.close();
    }
}

/**
 * Chromium の起動引数。--disable-partial-raster で、画面の一部が書き換わったときも、その部分を含むタイル全体を描き直させる。
 *
 * 【なぜ要るか】（#802。実際に発生した）同じビルド・同じ確認用インスタンスでも、pc-1920x1080/my-archive-list.png の
 * 検索欄・ボタン・枠の角の画素が、撮るたびに 2〜3 通りのどれかになっていた（色の値が 1 だけ違う。差は 6・42・48 ピクセル）。
 * 要素の位置（getBoundingClientRect）は毎回小数点以下まで同じで、--disable-gpu でも揺れたので、レイアウトや GPU ではなく
 * 描画（ラスタライズ）の揺れ。Chromium は、データが届いて画面の一部が変わると、すでに描いたタイルのうち変わった範囲だけを
 * 描き直す（partial raster）。角の丸い枠をその範囲で切り取って描くと、境目の画素のぼかし（アンチエイリアス）が
 * 全体を描いたときと 1 だけ違う値になる。どの範囲がいつ描き直されるかは、通信と描画の間合いで毎回変わるので、
 * 結果が揺れる。一覧の画面は、検索欄の選択肢・件数・表が別々の通信で後から埋まるので、書き換えの回数が多く揺れやすい。
 * 部分的な描き直しを止めると、毎回タイル全体を同じ範囲で描くので、同じ画面なら同じ画素になる（同じ画面を 20 回撮って差 0）。
 * 実際のブラウザの描き方とは違うが、差は角の 1 段階の色だけで、変更の前後を比べる目的には影響しない。
 * 版（PLAYWRIGHT_VERSION）と同じく、この引数を変えたら前後の両方を撮り直す（引数の無い版で撮った画像とは角の画素が違う）。
 */
const CHROMIUM_ARGS = ["--disable-partial-raster"];

async function launch(engines, size) {
    try {
        return await engines[size.browser].launch(size.browser === "chromium" ? { args: CHROMIUM_ARGS } : {});
    } catch (e) {
        const hint = size.browser === "webkit"
            ? "（Windows で Smart App Control が有効なら WebKit は動きません。UI_SNAPSHOT_IPHONE_ENGINE=chromium で撮れます）"
            : "";
        throw new ShotError(`${size.name}: ${size.browser} を起動できませんでした${hint}: ${e.message.split("\n")[0]}`);
    }
}

async function main() {
    if (!OUT) {
        console.error("使い方: node shoot.cjs <出力先>");
        process.exit(1);
    }
    const engines = { chromium, webkit };
    /** @type {Map<string, import("playwright").Browser>} */
    const browsers = new Map();
    // 前に撮った画像・差分が残っていると、今回撮れなかった画面を前回の画像のまま比べてしまうので消してから撮る
    fs.rmSync(path.join(OUT, "diff"), { recursive: true, force: true });
    try {
        for (const size of SIZES) {
            fs.rmSync(path.join(OUT, size.name), { recursive: true, force: true });
            fs.mkdirSync(path.join(OUT, size.name), { recursive: true });
            if (!browsers.has(size.browser)) browsers.set(size.browser, await launch(engines, size));
            const browser = browsers.get(size.browser);
            console.log(`${size.name}（${size.browser}）`);
            // ログインしない画面（ログイン・登録・再設定）は、ログインしたままだと別の画面へ移されうるので別の context で撮る
            const guest = await newContext(browser, size);
            const member = await newContext(browser, size);
            try {
                await login(member, size);
                for (const screen of SCREENS) await shoot(screen.auth ? member : guest, size, screen);
            } finally {
                await guest.close();
                await member.close();
            }
        }
    } finally {
        for (const browser of browsers.values()) await browser.close();
    }
    console.log(`撮り終えました: ${OUT}`);
}

main().catch((e) => {
    console.error(`エラー: ${e instanceof ShotError ? e.message : e.stack || e}`);
    process.exit(1);
});
