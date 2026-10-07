'use strict';
// system.js の、DOM を触らない小さな関数のテスト。
const {describe, test} = require('node:test');
const assert = require('node:assert/strict');
const {loadStatic} = require('./static-script.cjs');

const {systemGroupRows, systemNiceMax, systemTimeTicks} =
    loadStatic(['systemGroupRows', 'systemNiceMax', 'systemTimeTicks']);

/**
 * @param {number} pid プロセス ID
 * @param {number} parentPid 親のプロセス ID
 * @param {string} name プロセス名
 */
function proc(pid, parentPid, name) {
    return {pid, parentPid, name};
}

describe('systemGroupRows', () => {
    test('正常系：親と同じ名前の子は親の行にたたむ', () => {
        const rows = systemGroupRows([
            proc(10, 1, 'chrome'), proc(11, 10, 'chrome'), proc(12, 10, 'chrome'),
        ]);
        assert.deepEqual(rows, [{head: 10, members: [10, 11, 12]}]);
    });
    test('正常系：名前の違う子は別の行の頭になる', () => {
        const rows = systemGroupRows([proc(10, 1, 'sh'), proc(11, 10, 'sleep')]);
        assert.deepEqual(rows, [{head: 10, members: [10]}, {head: 11, members: [11]}]);
    });
    test('正常系：同じ名前が続く限り孫までたたむが、違う名前の子の下の同じ名前は別の行', () => {
        const rows = systemGroupRows([
            proc(10, 1, 'chrome'), proc(11, 10, 'chrome'), proc(12, 11, 'chrome'),
            proc(13, 10, 'cat'), proc(14, 13, 'chrome'),
        ]);
        assert.deepEqual(rows, [
            {head: 10, members: [10, 11, 12]},
            {head: 13, members: [13]},
            {head: 14, members: [14]},
        ]);
    });
    test('正常系：親が一覧に無いプロセスは頭になる', () => {
        // 親の 1（systemd）は実行ユーザーのプロセスではないので一覧に無い
        const rows = systemGroupRows([proc(10, 1, 'bash'), proc(20, 1, 'bash')]);
        assert.deepEqual(rows, [{head: 10, members: [10]}, {head: 20, members: [20]}]);
    });
    test('正常系：行の並びは渡した順', () => {
        // 子が親より先に来ても、行は頭の順で、頭の中では先頭が頭、残りは渡した順
        const rows = systemGroupRows([
            proc(30, 1, 'yes'), proc(22, 20, 'node'), proc(20, 1, 'node'), proc(21, 20, 'node'),
        ]);
        assert.deepEqual(rows, [{head: 30, members: [30]}, {head: 20, members: [20, 22, 21]}]);
    });
});

describe('systemNiceMax', () => {
    test('正常系：値以上で最も小さい 1・2・5 × 10 の累乗を返す', () => {
        assert.equal(systemNiceMax(2.7), 5);
        assert.equal(systemNiceMax(0.03), 0.05);
        assert.equal(systemNiceMax(1), 1);
        assert.equal(systemNiceMax(10.1), 20);
    });
    test('正常系：0 以下は 1 を返す', () => {
        assert.equal(systemNiceMax(0), 1);
        assert.equal(systemNiceMax(-3), 1);
    });
});

describe('systemTimeTicks', () => {
    // 端末の時計で目盛りを作るので、期待値も端末の時計の new Date(年, 月, 日, 時, 分) で作る
    const start = new Date(2026, 9, 8, 20, 58).getTime();
    const end = new Date(2026, 9, 8, 22, 40).getTime();
    const at = (hours, minutes) => new Date(2026, 9, 8, hours, minutes).getTime();

    test('正常系：30 分なら 21:00・21:30・22:00・22:30 の 4 つ', () => {
        assert.deepEqual(systemTimeTicks(start, end, 30),
            [at(21, 0), at(21, 30), at(22, 0), at(22, 30)]);
    });
    test('正常系：60 分なら 21:00・22:00', () => {
        assert.deepEqual(systemTimeTicks(start, end, 60), [at(21, 0), at(22, 0)]);
    });
    test('正常系：範囲に切りのよい時刻が無ければ空', () => {
        assert.deepEqual(systemTimeTicks(at(21, 1), at(21, 29), 30), []);
    });
});
