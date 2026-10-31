// ==UserScript==
// @name         YouTube 広告スキップ
// @namespace    youtube-live-monitor.ad-skip
// @version      1.1.0
// @description  スキップボタンが出た広告を飛ばす。YouTube と、YouTube Live Monitor の埋め込みプレーヤーの中で動く
// @match        https://www.youtube.com/*
// @match        https://m.youtube.com/*
// @match        https://www.youtube-nocookie.com/embed/*
// @match        https://*.ts.net/*
// @run-at       document-start
// @grant        none
// ==/UserScript==

// 再生画面は YouTube を別オリジンの iframe で開くので、アプリの JS からは中に触れない。ユーザースクリプトは iframe の中でも動くので、
// 埋め込みの広告にも効く（@noframes を付けないこと）。
// @run-at を document-start にするのは、視聴画面を直接開くとアプリが読み込みの途中で案内を描くため。document-idle だと印が間に合わず、
// 導入済みのブラウザにも案内が出る。YouTube 側は下のタイマーで探し続けるので、早く動いても変わらない。
(() => {
    'use strict';

    // サービスの画面では、導入済みと分かる印だけを付ける（導入の案内を出さないため）
    if (location.hostname.endsWith('.ts.net')) {
        document.documentElement.dataset.adSkip = '1';
        return;
    }

    const SKIP_BUTTON = '.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern';

    setInterval(() => {
        // 広告の再生中にだけ動く。ボタンが残っているだけのときに飛ばすと、本編が最後まで飛ぶ
        if (!document.querySelector('.html5-video-player.ad-showing')) return;
        const button = document.querySelector(SKIP_BUTTON);
        if (!button || button.offsetParent === null) return;
        const video = document.querySelector('.html5-video-player video');
        // ライブの duration は Infinity で、currentTime に入れると例外になる
        if (video && Number.isFinite(video.duration) && video.duration > 0) {
            video.currentTime = video.duration - 0.1;
        }
        setTimeout(() => {
            if (button.offsetParent !== null) button.click();
        }, 100);
    }, 500);
})();
