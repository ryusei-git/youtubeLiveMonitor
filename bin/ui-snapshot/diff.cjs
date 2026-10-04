/*
 * bin/ui-snapshot.sh shoot で撮った 2 つの出力先を比べる。bin/ui-snapshot.sh diff から呼ばれる。
 *
 * 【この形にした理由】
 *   - 判定に使うのは PC の画像（pc- で始まるディレクトリ）だけ。iPhone 対応の変更は iPhone の画面を変えるのが目的なので、
 *     iPhone の差は参考として数を出すだけにして、終了コードには入れない（親 Issue #785「PC の画面は 1 ピクセルも変えない」）
 *   - 色の差に許容値を設けず、RGBA が 1 つでも違う画素を数える。「ほぼ同じ」を許すと、1px の線のずれや
 *     色の小さな変化を見逃す。同じコードを 2 回撮ると 0 になるよう、揺れの元は撮る側（shoot.cjs）で止めている
 *   - 差のある画像は <後>/diff/ に差分の画像を書く（違う画素を赤、同じ画素を薄くした元の画像）。大きさが違うときは
 *     重なる範囲だけを比べ、はみ出した部分はすべて差として数える
 *   - 片方にしか無い PC の画像も差として扱う（撮れなかった画面を「差 0」と取り違えないため）
 */
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { PNG } = require("pngjs");

const [before, after] = process.argv.slice(2);

/** @param {string} dir */
function listImages(dir) {
    const images = new Map();
    for (const size of fs.readdirSync(dir, { withFileTypes: true })) {
        if (!size.isDirectory() || size.name === "diff") continue;
        for (const file of fs.readdirSync(path.join(dir, size.name))) {
            if (file.endsWith(".png")) images.set(`${size.name}/${file}`, path.join(dir, size.name, file));
        }
    }
    return images;
}

/**
 * @param {string} a
 * @param {string} b
 * @returns {{ count: number, diff: PNG }}
 */
function compare(a, b) {
    const left = PNG.sync.read(fs.readFileSync(a));
    const right = PNG.sync.read(fs.readFileSync(b));
    const width = Math.max(left.width, right.width);
    const height = Math.max(left.height, right.height);
    const diff = new PNG({ width, height });
    let count = 0;
    for (let y = 0; y < height; y++) {
        for (let x = 0; x < width; x++) {
            const o = (y * width + x) * 4;
            const inLeft = x < left.width && y < left.height;
            const inRight = x < right.width && y < right.height;
            const l = inLeft ? (y * left.width + x) * 4 : -1;
            const r = inRight ? (y * right.width + x) * 4 : -1;
            const same = inLeft && inRight
                && left.data[l] === right.data[r] && left.data[l + 1] === right.data[r + 1]
                && left.data[l + 2] === right.data[r + 2] && left.data[l + 3] === right.data[r + 3];
            if (same) {
                // 同じ画素は、どこが違うか目で追えるよう元の画像を薄く残す
                const gray = Math.round((left.data[l] + left.data[l + 1] + left.data[l + 2]) / 3);
                const faded = 255 - Math.round((255 - gray) * 0.25);
                diff.data[o] = diff.data[o + 1] = diff.data[o + 2] = faded;
            } else {
                count++;
                diff.data[o] = 255;
                diff.data[o + 1] = 0;
                diff.data[o + 2] = 0;
            }
            diff.data[o + 3] = 255;
        }
    }
    return { count, diff };
}

function main() {
    if (!before || !after) {
        console.error("使い方: node diff.cjs <前> <後>");
        process.exit(1);
    }
    const left = listImages(before);
    const right = listImages(after);
    const names = [...new Set([...left.keys(), ...right.keys()])].sort();
    let pcChecked = 0;
    let pcDiffering = 0;
    for (const name of names) {
        const pc = name.startsWith("pc-");
        const tag = pc ? "" : "（参考）";
        if (!left.has(name) || !right.has(name)) {
            console.log(`${pc ? "差あり" : "参考"}  ${name}: ${left.has(name) ? "後" : "前"}にありません${tag}`);
            if (pc) { pcChecked++; pcDiffering++; }
            continue;
        }
        const { count, diff } = compare(left.get(name), right.get(name));
        if (pc) pcChecked++;
        if (count === 0) {
            console.log(`${pc ? "同じ  " : "参考  "}${name}: 差 0${tag}`);
            continue;
        }
        const out = path.join(after, "diff", name);
        fs.mkdirSync(path.dirname(out), { recursive: true });
        fs.writeFileSync(out, PNG.sync.write(diff));
        console.log(`${pc ? "差あり" : "参考  "}${name}: ${count} ピクセル（差分: ${out}）${tag}`);
        if (pc) pcDiffering++;
    }
    if (pcChecked === 0) {
        console.error("エラー: 比べられる PC の画像がありません（pc- で始まるディレクトリ）");
        process.exit(1);
    }
    console.log(pcDiffering === 0
        ? `PC の画像 ${pcChecked} 枚はすべて同じです`
        : `PC の画像 ${pcChecked} 枚のうち ${pcDiffering} 枚に差があります`);
    process.exit(pcDiffering === 0 ? 0 : 1);
}

main();
