// @ts-check
// 絞り込み・3 段とページ送り・取得状況の案内・表示の更新は、利用者の /my/videos と同じもの（common.js）を使う。
// 「戻る」「進む」と自動更新はページを読み込み直すまで続けてよいので、ここで付ける。
const videoPage = bindOnlineVideoSections();
window.addEventListener("popstate", () => { videoPage.restore(); videoPage.loadAll(true); });
startVisibleRefresh(() => videoPage.loadAll(false, false));
videoPage.start();

// 管理者の動画一覧。一般利用者は 1 枚のページの動画・配信（/my/videos）へ移す（#178）。
// サーバーで転送しないのは、同じ URL を管理者も使うため。絞り込みは /my/videos も同じ名前で URL から読むので引き継ぐ。
(async () => {
    try {
        const viewer = await apiGet("/api/videos/viewer");
        if (!viewer.admin) {
            location.replace("/my/videos" + location.search);
            return;
        }
        renderNavigationForViewer(true);
    } catch {
        // 判定できなかったときは移さず（管理者を利用者の画面へ送らないため）、権限の少ない利用者用のメニューを出す。
        // 一覧本体のエラー表示を優先する。
        renderNavigationForViewer(false);
    }
})();
