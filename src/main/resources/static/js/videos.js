// @ts-check
// 絞り込み・3 段とページ送り・取得状況の案内・表示の更新は、利用者の /my/videos と同じもの（common.js）を使う。
// 「戻る」「進む」と自動更新はページを読み込み直すまで続けてよいので、ここで付ける。
const videoPage = bindOnlineVideoSections();
window.addEventListener("popstate", () => { videoPage.restore(); videoPage.loadAll(true); });
startVisibleRefresh(() => videoPage.loadAll(false, false));
videoPage.start();

// 共有画面なので、サーバーが返す権限で共通メニューを選ぶ。
(async () => {
    try {
        const viewer = await apiGet("/api/videos/viewer");
        renderNavigationForViewer(viewer.admin);
    } catch {
        // 判定できなかったときは、権限の少ない利用者用のメニューを出す。一覧本体のエラー表示を優先する。
        renderNavigationForViewer(false);
    }
})();
