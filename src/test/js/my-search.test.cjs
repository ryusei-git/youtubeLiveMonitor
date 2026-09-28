'use strict';
// 利用者の検索（my-search.js）の、DOM を触らない関数のテスト。
// 検索の条件は URL を正本にし、サーバーは同じクエリの結果を使い回す。クエリの組み立てがずれると、
// 使い回しに当たらず検索の回数（1 人 1 日 10 回）を使ったり、条件が API に届かなかったりする。

// 「期間を指定」の日付は端末の時刻帯の 0 時で区切る。期待値を固定するため、日付を扱う前に日本時間にそろえる
process.env.TZ = 'Asia/Tokyo';

const {describe, test, beforeEach, afterEach, mock} = require('node:test');
const assert = require('node:assert/strict');
const {loadStatic} = require('./static-script.cjs');

const {
    mySearchChannelId, mySearchApiParams, mySearchPublishedRange, mySearchKeptRange, mySearchTimeAgo, mySearchLinkify,
} = loadStatic([
    'MY_SEARCH_PASSTHROUGH', 'MY_SEARCH_OFFICIAL_FIELDS', 'MY_SEARCH_KEEP_RANGE_MS', 'escapeHtml',
    'mySearchChannelId', 'mySearchApiParams', 'mySearchPublishedRange', 'mySearchKeptRange', 'mySearchTimeAgo', 'mySearchLinkify',
]);

/** 本来のチャンネル ID の形（UC で始まる 24 文字）の値 */
const CHANNEL_ID = `UC${'a'.repeat(22)}`;

/**
 * @param {string} query 画面の URL の条件
 * @returns {Record<string, string>} mySearchApiParams の結果（比べやすいようにオブジェクトにする）
 */
function apiParams(query) {
    return Object.fromEntries(mySearchApiParams(new URLSearchParams(query)));
}

describe('mySearchChannelId', () => {
    test('正常系：チャンネルの URL と ID そのものから ID を取り出す', () => {
        assert.equal(mySearchChannelId(`https://www.youtube.com/channel/${CHANNEL_ID}/videos`), CHANNEL_ID);
        assert.equal(mySearchChannelId(CHANNEL_ID), CHANNEL_ID);
        assert.equal(mySearchChannelId(`UC-_${'b'.repeat(20)}`), `UC-_${'b'.repeat(20)}`);
    });
    test('異常系：ハンドル・短い値・空は null にする', () => {
        // ハンドル（@foo）は本来のチャンネル ID と別物で、search.list の channelId には使えない
        assert.equal(mySearchChannelId('https://www.youtube.com/@example'), null);
        assert.equal(mySearchChannelId('UCshort'), null);
        assert.equal(mySearchChannelId(''), null);
    });
});

describe('mySearchApiParams', () => {
    test('正常系：API に渡す条件だけをそのまま渡し、空の値と画面だけの条件は渡さない', () => {
        assert.deepEqual(apiParams('q=ASMR&order=date&duration=&posted=week&from=2026-09-01&to=2026-09-30'
            + '&publishedAfter=2026-09-20T12:00:00.000Z&excludeShorts=true'),
            {q: 'ASMR', order: 'date', publishedAfter: '2026-09-20T12:00:00.000Z', excludeShorts: 'true'});
    });
    test('正常系：チャンネルの URL を channelId にする', () => {
        assert.deepEqual(apiParams(`channel=${encodeURIComponent(`https://www.youtube.com/channel/${CHANNEL_ID}`)}`),
            {channelId: CHANNEL_ID});
    });
    test('異常系：ID を読み取れないチャンネルの入力は渡さない', () => {
        assert.deepEqual(apiParams('channel=%40example'), {});
    });
    test('正常系：長さは分から秒にし、端数は四捨五入する', () => {
        assert.deepEqual(apiParams('minMinutes=1.5&maxMinutes=60'), {minDurationSec: '90', maxDurationSec: '3600'});
        assert.deepEqual(apiParams('minMinutes=0.33'), {minDurationSec: '20'});
    });
    test('異常系：数として読めない長さは渡さない', () => {
        assert.deepEqual(apiParams('minMinutes=abc&maxMinutes='), {});
    });
    test('正常系：監視中のチャンネルの選択を onlyRegistered・excludeRegistered にする', () => {
        assert.deepEqual(apiParams('registered=only'), {onlyRegistered: 'true'});
        assert.deepEqual(apiParams('registered=exclude'), {excludeRegistered: 'true'});
        assert.deepEqual(apiParams('registered='), {});
    });
});

describe('mySearchPublishedRange', () => {
    beforeEach(() => mock.timers.enable({apis: ['Date'], now: Date.parse('2026-09-27T12:34:56.789Z')}));
    afterEach(() => mock.timers.reset());

    test('正常系：「24 時間以内」などは今から数えた起点を、秒を切り捨てて返す', () => {
        // 同じ分の中で検索し直したときに同じクエリになり、サーバーの使い回しに当たるように秒を切り捨てる
        assert.deepEqual(mySearchPublishedRange('day', '', ''), {publishedAfter: '2026-09-26T12:34:00.000Z'});
        assert.deepEqual(mySearchPublishedRange('week', '', ''), {publishedAfter: '2026-09-20T12:34:00.000Z'});
        assert.deepEqual(mySearchPublishedRange('month', '', ''), {publishedAfter: '2026-08-28T12:34:00.000Z'});
        assert.deepEqual(mySearchPublishedRange('year', '', ''), {publishedAfter: '2025-09-27T12:34:00.000Z'});
    });
    test('正常系：「期間を指定」は開始日の 0 時から、終了日の翌日の 0 時より前までにする', () => {
        // 終了日の 0 時で切ると、終了日に投稿された動画が入らない
        assert.deepEqual(mySearchPublishedRange('custom', '2026-09-01', '2026-09-30'),
            {publishedAfter: '2026-08-31T15:00:00.000Z', publishedBefore: '2026-09-30T15:00:00.000Z'});
        assert.deepEqual(mySearchPublishedRange('custom', '', '2026-12-31'), {publishedBefore: '2026-12-31T15:00:00.000Z'});
    });
    test('正常系：指定なし・日付の無い「期間を指定」は条件を付けない', () => {
        assert.deepEqual(mySearchPublishedRange('', '2026-09-01', '2026-09-30'), {});
        assert.deepEqual(mySearchPublishedRange('custom', '', ''), {});
    });
});

describe('mySearchKeptRange', () => {
    const FRESH = {publishedAfter: '2026-09-27T12:00:00.000Z'};

    test('正常系：公式の条件が同じで 6 時間より新しい起点は引き継ぐ（このサービスの条件だけ変えても回数を使わない）', () => {
        const previous = new URLSearchParams('q=ASMR&posted=day&publishedAfter=2026-09-27T10:00:00.000Z');
        const next = new URLSearchParams('q=ASMR&posted=day&minViews=1000');
        assert.deepEqual(mySearchKeptRange(previous, next, FRESH), {publishedAfter: '2026-09-27T10:00:00.000Z'});
    });
    test('正常系：公式の条件が変わったら、今から数え直した起点を使う', () => {
        const previous = new URLSearchParams('q=ASMR&posted=day&publishedAfter=2026-09-27T10:00:00.000Z');
        assert.deepEqual(mySearchKeptRange(previous, new URLSearchParams('q=雑談&posted=day'), FRESH), FRESH);
        assert.deepEqual(mySearchKeptRange(previous, new URLSearchParams('q=ASMR&posted=week'), FRESH), FRESH);
    });
    test('正常系：6 時間以上前の起点は引き継がない', () => {
        const previous = new URLSearchParams('q=ASMR&posted=day&publishedAfter=2026-09-27T06:00:00.000Z');
        assert.deepEqual(mySearchKeptRange(previous, new URLSearchParams('q=ASMR&posted=day'), FRESH), FRESH);
    });
    test('正常系：「期間を指定」と、前の検索に起点が無いときは引き継がない', () => {
        const custom = new URLSearchParams('q=ASMR&posted=custom&publishedAfter=2026-09-27T11:00:00.000Z');
        assert.deepEqual(mySearchKeptRange(custom, new URLSearchParams('q=ASMR&posted=custom'), FRESH), FRESH);
        assert.deepEqual(mySearchKeptRange(new URLSearchParams('q=ASMR&posted=day'), new URLSearchParams('q=ASMR&posted=day'), FRESH), FRESH);
    });
    test('異常系：未来の起点と読めない起点は引き継がない', () => {
        const next = new URLSearchParams('q=ASMR&posted=day');
        const future = new URLSearchParams('q=ASMR&posted=day&publishedAfter=2026-09-27T13:00:00.000Z');
        const broken = new URLSearchParams('q=ASMR&posted=day&publishedAfter=abc');
        assert.deepEqual(mySearchKeptRange(future, next, FRESH), FRESH);
        assert.deepEqual(mySearchKeptRange(broken, next, FRESH), FRESH);
    });
});

describe('mySearchTimeAgo', () => {
    beforeEach(() => mock.timers.enable({apis: ['Date'], now: Date.parse('2026-09-27T12:00:00.000Z')}));
    afterEach(() => mock.timers.reset());

    test('正常系：経過をいちばん大きな単位の整数で出す', () => {
        assert.equal(mySearchTimeAgo('2026-09-27T11:55:00.000Z'), '5 分前');
        assert.equal(mySearchTimeAgo('2026-09-27T09:00:00.000Z'), '3 時間前');
        assert.equal(mySearchTimeAgo('2026-09-25T12:00:00.000Z'), '2 日前');
        assert.equal(mySearchTimeAgo('2026-09-13T12:00:00.000Z'), '2 週間前');
        assert.equal(mySearchTimeAgo('2026-08-01T12:00:00.000Z'), '1 か月前');
        assert.equal(mySearchTimeAgo('2024-09-27T12:00:00.000Z'), '2 年前');
    });
    test('正常系：1 分未満と未来の日時は「たった今」にする', () => {
        assert.equal(mySearchTimeAgo('2026-09-27T11:59:30.000Z'), 'たった今');
        assert.equal(mySearchTimeAgo('2026-09-27T13:00:00.000Z'), 'たった今');
    });
    test('異常系：無い・読めない日時は空文字にする', () => {
        assert.equal(mySearchTimeAgo(null), '');
        assert.equal(mySearchTimeAgo('not a date'), '');
    });
});

describe('mySearchLinkify', () => {
    /**
     * @param {string} url リンクにする URL（エスケープ済み）
     * @returns {string} mySearchLinkify が作るリンク
     */
    const link = (url) => `<a href="${url}" target="_blank" rel="noopener noreferrer">${url}</a>`;

    test('正常系：URL の直後の全角の記号・句点はリンクに含めない', () => {
        assert.equal(mySearchLinkify('（https://x.com/foo）'), `（${link('https://x.com/foo')}）`);
        assert.equal(mySearchLinkify('https://example.com。'), `${link('https://example.com')}。`);
        assert.equal(mySearchLinkify('【https://example.com/a?b=1&c=2】'), `【${link('https://example.com/a?b=1&amp;c=2')}】`);
    });
    test('正常系：末尾の ) は、URL の中に対応する ( があるときだけ残す', () => {
        assert.equal(mySearchLinkify('(https://x.com/foo).'), `(${link('https://x.com/foo')}).`);
        assert.equal(mySearchLinkify('https://ja.wikipedia.org/wiki/Foo_(bar)'), link('https://ja.wikipedia.org/wiki/Foo_(bar)'));
    });
    test('異常系：説明に書かれた HTML は効かせず、" や < の手前で URL を終える', () => {
        assert.equal(mySearchLinkify('<b>https://x.com/"x</b>'), `&lt;b&gt;${link('https://x.com/')}&quot;x&lt;/b&gt;`);
    });
    test('異常系：説明が無ければ空文字にする', () => {
        assert.equal(mySearchLinkify(null), '');
    });
});
