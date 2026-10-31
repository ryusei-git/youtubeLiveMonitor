'use strict';
// common.js の、DOM を触らない小さな関数のテスト（embeddedVideoUrl は online-video.test.cjs、
// editTitleFilterCell は channel-filter.test.cjs にある）。
const {describe, test, beforeEach, afterEach, mock} = require('node:test');
const assert = require('node:assert/strict');
const {loadStatic} = require('./static-script.cjs');

const {escapeHtml, formatTimeAgo, recordingDownloadName} = loadStatic(['escapeHtml', 'formatTimeAgo', 'recordingDownloadName']);

describe('escapeHtml', () => {
    test('正常系：HTML で意味を持つ 5 文字を文字参照にする', () => {
        assert.equal(escapeHtml(`<a href="x">'&'</a>`), '&lt;a href=&quot;x&quot;&gt;&#39;&amp;&#39;&lt;/a&gt;');
    });
    test('正常系：null と undefined は空文字にし、数値は文字列にする', () => {
        assert.equal(escapeHtml(null), '');
        assert.equal(escapeHtml(undefined), '');
        assert.equal(escapeHtml(0), '0');
    });
});

describe('formatTimeAgo', () => {
    beforeEach(() => mock.timers.enable({apis: ['Date'], now: Date.parse('2026-09-27T12:00:00.000Z')}));
    afterEach(() => mock.timers.reset());

    test('正常系：経過をいちばん大きな単位の整数で出す', () => {
        assert.equal(formatTimeAgo('2026-09-27T11:55:00.000Z'), '5 分前');
        assert.equal(formatTimeAgo('2026-09-27T09:00:00.000Z'), '3 時間前');
        assert.equal(formatTimeAgo('2026-09-25T12:00:00.000Z'), '2 日前');
        assert.equal(formatTimeAgo('2026-09-13T12:00:00.000Z'), '2 週間前');
        assert.equal(formatTimeAgo('2026-08-01T12:00:00.000Z'), '1 か月前');
        assert.equal(formatTimeAgo('2024-09-27T12:00:00.000Z'), '2 年前');
    });
    test('正常系：1 分未満と未来の日時は「たった今」にする', () => {
        assert.equal(formatTimeAgo('2026-09-27T11:59:30.000Z'), 'たった今');
        assert.equal(formatTimeAgo('2026-09-27T13:00:00.000Z'), 'たった今');
    });
    test('異常系：無い・読めない日時は空文字にする', () => {
        assert.equal(formatTimeAgo(null), '');
        assert.equal(formatTimeAgo('not a date'), '');
    });
});

describe('recordingDownloadName', () => {
    test('正常系：ファイル名に使えない文字と制御文字を _ にし、前後の空白を除いて .mp4 を付ける', () => {
        const recording = {videoTitle: ' a/b\\c:d*e?f"g<h>i|j\tk\nl ', videoId: 'abcdefghijk'};
        assert.equal(recordingDownloadName(recording), 'a_b_c_d_e_f_g_h_i_j_k_l.mp4');
    });
    test('正常系：タイトルが無い・空白だけのときは動画 ID を名前にする', () => {
        assert.equal(recordingDownloadName({videoTitle: null, videoId: 'abcdefghijk'}), 'abcdefghijk.mp4');
        assert.equal(recordingDownloadName({videoTitle: '   ', videoId: 'abcdefghijk'}), 'abcdefghijk.mp4');
    });
    test('正常系：長いタイトルは 100 文字で切り、絵文字を半分に割らない', () => {
        // 絵文字は UTF-16 では 2 単位。単位で切ると、壊れた文字（片方だけのサロゲート）が名前に残る
        const name = recordingDownloadName({videoTitle: '😀'.repeat(150), videoId: 'abcdefghijk'});
        assert.equal(name, `${'😀'.repeat(100)}.mp4`);
        assert.equal(recordingDownloadName({videoTitle: 'あ'.repeat(120), videoId: 'abcdefghijk'}), `${'あ'.repeat(100)}.mp4`);
    });
});
