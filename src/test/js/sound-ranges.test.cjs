'use strict';
// 再生画面の耳キスの欄（my-app.js）が、印・候補の前後のどこを流すかを決める関数のテスト。
// 「印の所だけ再生」と前後の移動は、この区間を使って再生位置を動かすので、ずれると聞きたい音の手前や後ろへ飛ぶ。
const {describe, test} = require('node:test');
const assert = require('node:assert/strict');
const {loadStatic} = require('./static-script.cjs');

const {mySoundMarkRange, mySoundCandidateRange, myMergeSoundRanges} =
    loadStatic(['mySoundMarkRange', 'mySoundCandidateRange', 'myMergeSoundRanges']);

describe('mySoundMarkRange', () => {
    test('正常系：人の印は 3 秒前から 4 秒後までにする', () => {
        assert.deepEqual(mySoundMarkRange({positionMs: 10_000}), {start: 7, end: 14});
    });
    test('正常系：録画の先頭に近い印は 0 秒から始める', () => {
        assert.deepEqual(mySoundMarkRange({positionMs: 1_500}), {start: 0, end: 5.5});
    });
});

describe('mySoundCandidateRange', () => {
    test('正常系：自動の候補は 2 秒前から 2 秒後までにする', () => {
        assert.deepEqual(mySoundCandidateRange({positionMs: 10_000}), {start: 8, end: 12});
    });
    test('正常系：録画の先頭に近い候補は 0 秒から始める', () => {
        assert.deepEqual(mySoundCandidateRange({positionMs: 1_000}), {start: 0, end: 3});
    });
});

describe('myMergeSoundRanges', () => {
    test('正常系：並べ替えてから、間が 2 秒以下の区間をまとめ、2 秒を超える区間は分ける', () => {
        const ranges = [{start: 20, end: 24}, {start: 0, end: 4}, {start: 6, end: 10}, {start: 12.5, end: 15}];
        assert.deepEqual(myMergeSoundRanges(ranges), [{start: 0, end: 10}, {start: 12.5, end: 15}, {start: 20, end: 24}]);
    });
    test('正常系：重なる区間はまとめる', () => {
        assert.deepEqual(myMergeSoundRanges([{start: 0, end: 5}, {start: 3, end: 8}]), [{start: 0, end: 8}]);
    });
    test('正常系：後から始まる短い区間が前の区間の中に収まるときは、前の区間の終わりを残す', () => {
        // 同じ位置の人の印（7〜14 秒）と候補（8〜12 秒）。候補の終わりで区間を縮めると、印の後ろの 2 秒を流さなくなる
        const ranges = [mySoundMarkRange({positionMs: 10_000}), mySoundCandidateRange({positionMs: 10_000})];
        assert.deepEqual(myMergeSoundRanges(ranges), [{start: 7, end: 14}]);
    });
    test('正常系：渡した配列の並びと区間を書き換えない', () => {
        // 今の呼び出し側は毎回区間を作り直すので困らないが、渡した区間の end を伸ばすと、同じ区間を使い回す呼び出し方にしたときに区間が広がっていく
        const ranges = [{start: 5, end: 9}, {start: 1, end: 4}];
        assert.deepEqual(myMergeSoundRanges(ranges), [{start: 1, end: 9}]);
        assert.deepEqual(ranges, [{start: 5, end: 9}, {start: 1, end: 4}]);
    });
    test('正常系：区間が無ければ空の配列を返す', () => {
        assert.deepEqual(myMergeSoundRanges([]), []);
    });
});
