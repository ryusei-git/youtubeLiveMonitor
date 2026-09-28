'use strict';
/*
 * 画面の JS（src/main/resources/static/js/*.js）から、名前を指定したトップレベルの関数と定数だけを取り出して、node で動かす。
 *
 * ファイルを丸ごと読み込まないのは、common.js と my-app.js が読み込んだ時点で画面を組み立てるため
 * （common.js の initStudioShell()、my-app.js の末尾の myDockInit()・myRender() など）。DOM の無い node では、そこで失敗する。
 * 画面の JS はモジュールではなく export も無い（ビルド手順を増やさない方針。common.js の冒頭）ので、名前で取り出す。
 *
 * ファイルではなく名前で探すのは、jsconfig.json がすべての js を 1 つのスコープで型検査するため。トップレベルの名前は
 * ファイルをまたいで一意になる（重なると型検査のエラーになる）。関数を static/js の中の別のファイルへ移しても、テストを直さずに済む
 * （探すのは static/js の直下の .js だけ。下のディレクトリへ移すなら readStaticScripts を直す）。
 *
 * 取り出す範囲は、画面の JS の書き方（トップレベルは字下げ無し、中身は 4 文字の字下げ）に合わせて決める。
 * - 関数：行頭の「function 名前(」か「async function 名前(」から、「}」だけの行まで
 * - 定数：行頭の「const 名前 =」から、行末が「;」の最初の行まで。配列・文字列・値だけのオブジェクトの定数に使う
 *   （メソッドを持つオブジェクトは、中の行が「;」で終わるので取り出せない）
 *
 * 動かすのは node:vm の別のコンテキストではなく、new Function でテストと同じ世界にする。別のコンテキストで作った
 * 配列・オブジェクトはプロトタイプが違うので assert.deepStrictEqual が一致しないと判定し、
 * テストの mock.timers で差し替えた Date も届かないため。
 */
const fs = require('node:fs');
const path = require('node:path');

/** 画面の JS の置き場所。テストをどのディレクトリから実行しても読めるよう、このファイルの位置から決める。 */
const STATIC_JS_DIR = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'js');

/**
 * @returns {{file: string, text: string}[]} 画面の JS。改行は LF にそろえる（Windows の作業ツリーは CRLF のため）
 */
function readStaticScripts() {
    return fs.readdirSync(STATIC_JS_DIR).filter((file) => file.endsWith('.js')).sort()
        .map((file) => ({file, text: fs.readFileSync(path.join(STATIC_JS_DIR, file), 'utf8').replace(/\r\n/g, '\n')}));
}

/**
 * @param {{file: string, text: string}[]} scripts 画面の JS
 * @param {string} name 取り出す関数・定数の名前
 * @returns {string} 取り出したソース
 */
function extract(scripts, name) {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) throw new Error(`名前として使えない文字があります: ${name}`);
    const head = new RegExp(`^(?:(?:async )?function ${name}\\(|const ${name} =)`, 'm');
    const found = scripts.flatMap(({file, text}) => {
        const match = head.exec(text);
        return match ? [{file, text, index: match.index, isFunction: !match[0].startsWith('const')}] : [];
    });
    if (found.length !== 1) {
        throw new Error(`${name} が ${found.length} か所にあります（1 か所だけのはず）: ${found.map((f) => f.file).join(', ') || 'なし'}`);
    }
    const {file, text, index, isFunction} = found[0];
    const rest = text.slice(index);
    const end = rest.search(isFunction ? /^\}$/m : /;$/m);
    if (end < 0) throw new Error(`${file} の ${name} の終わりが見つかりません`);
    return rest.slice(0, end + 1);
}

/**
 * 名前を指定した関数・定数を取り出して動かし、名前をキーにして返す。
 * 取り出した関数が使うほかの関数・定数も names に入れる。入れていない名前は globals から探し、
 * どちらにも無ければ、呼んだときに ReferenceError になる。
 *
 * @param {string[]} names 取り出す関数・定数の名前
 * @param {Record<string, unknown>} [globals] 取り出した関数から見える名前（DOM の関数などの差し替え）
 * @returns {Record<string, any>} 名前 → 関数・定数
 */
function loadStatic(names, globals = {}) {
    const scripts = readStaticScripts();
    const source = names.map((name) => extract(scripts, name)).join('\n\n');
    const factory = new Function(...Object.keys(globals), `${source}\nreturn { ${names.join(', ')} };`);
    return factory(...Object.values(globals));
}

module.exports = {loadStatic};
