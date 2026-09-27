// @ts-check
/*
 * 全画面で共有する処理。各画面のスクリプトより先に読み込まれ、グローバルに関数を公開する。
 *
 * 先頭の // @ts-check と jsconfig.json により、ビルドを挟まずにエディタ上で型検査が効く。
 * 型は JSDoc で書く（TypeScript を導入するとビルド手順が増え、
 * 「static/ を編集すればそのまま反映される」という現在の運用が崩れるため）。
 */

/* ============================================================
   サーバのレスポンス型
   Java 側の dto パッケージと対になる定義。片方を変えたらもう片方も直すこと。
   ============================================================ */

/**
 * 録画履歴1件（Java の {@code RecordingResponse} に対応）。
 *
 * @typedef {object} Recording
 * @property {number} id 録画履歴の主キー
 * @property {number|null} channelId 録画対象チャンネルの主キー。監視登録していないチャンネルの動画を
 *   ダウンロードした場合は null
 * @property {string|null} youtubeChannelId チャンネルID。未登録なら null
 * @property {string} channelName チャンネルの表示名。未登録なら "(未登録チャンネル)"
 * @property {string|null} channelUrl チャンネルページの URL。未登録、またはログイン名未取得の Twitch なら null
 * @property {string} videoId 配信の動画ID
 * @property {string} videoTitle 録画開始時点の配信タイトル
 * @property {string|null} genre タイトルの最初の【】の中身。無ければ null
 * @property {string} filePath 録画ディレクトリからの相対パス
 * @property {number|null} fileSizeBytes ファイルサイズ。録画中・失敗時は null
 * @property {number|null} durationSeconds 再生時間。未取得なら null
 * @property {number} playCount 再生回数（全員の合計）
 * @property {string|null} thumbnailPath サムネイルの相対パス。未生成なら null
 * @property {"RECORDING"|"COMPLETED"|"PARTIAL"|"FAILED"} status 録画の状態
 * @property {string} startedAt 録画を開始した時刻（ISO形式）
 * @property {string|null} completedAt 完了・失敗した時刻。録画中は null
 * @property {boolean} watched ログイン中の利用者が視聴済みにしたか
 * @property {boolean} favorite ログイン中の利用者がお気に入りにしたか
 */

/* ============================================================
   DOM の取得
   ============================================================ */

/**
 * ID で要素を取得する。存在しなければ例外を投げる。
 *
 * {@link document.getElementById} は見つからないと null を返すため、呼び出し側が
 * 毎回 null 検査を書く羽目になる。画面の要素は HTML に必ず存在する前提なので、
 * 「無ければ HTML と JS の食い違い＝設計ミス」として即座に落とす方が、
 * 後から静かに壊れて原因を探すより発見が早い。
 *
 * @param {string} id 要素のID
 * @returns {HTMLElement} 見つかった要素
 */
function el(id) {
    const found = document.getElementById(id);
    if (!found) {
        throw new Error(`要素が見つかりません: #${id}`);
    }
    return found;
}

/**
 * ID で入力欄を取得する。`value` や `checked` を読み書きする箇所で使う。
 *
 * @param {string} id 要素のID
 * @returns {HTMLInputElement} 見つかった入力欄
 */
function inputEl(id) {
    const found = el(id);
    if (!(found instanceof HTMLInputElement)) {
        throw new Error(`入力欄ではありません: #${id}`);
    }
    return found;
}

/**
 * ID で選択欄を取得する。
 *
 * @param {string} id 要素のID
 * @returns {HTMLSelectElement} 見つかった選択欄
 */
function selectEl(id) {
    const found = el(id);
    if (!(found instanceof HTMLSelectElement)) {
        throw new Error(`選択欄ではありません: #${id}`);
    }
    return found;
}

/**
 * セレクタで要素を取得する。存在しなければ例外を投げる。
 *
 * {@link el} と同じ考え方で、{@link ParentNode.querySelector} が返す null を
 * 呼び出し側で毎回検査しなくて済むようにするためのもの。
 *
 * @param {string} selector CSS セレクタ
 * @param {ParentNode} [root] 探索の起点。省略時は document
 * @returns {HTMLElement} 見つかった要素
 */
function query(selector, root) {
    const found = (root ?? document).querySelector(selector);
    if (!(found instanceof HTMLElement)) {
        throw new Error(`要素が見つかりません: ${selector}`);
    }
    return found;
}

/**
 * ID でボタンを取得する。`disabled` を切り替える箇所で使う。
 *
 * @param {string} id 要素のID
 * @returns {HTMLButtonElement} 見つかったボタン
 */
function buttonEl(id) {
    const found = el(id);
    if (!(found instanceof HTMLButtonElement)) {
        throw new Error(`ボタンではありません: #${id}`);
    }
    return found;
}

/**
 * ID でフォームを取得する。`reset()` を呼ぶ箇所で使う。
 *
 * @param {string} id 要素のID
 * @returns {HTMLFormElement} 見つかったフォーム
 */
function formEl(id) {
    const found = el(id);
    if (!(found instanceof HTMLFormElement)) {
        throw new Error(`フォームではありません: #${id}`);
    }
    return found;
}

/**
 * catch で受け取った値からメッセージを取り出す。
 *
 * ES2022 以降 catch の変数は unknown 相当で、そのまま `.message` を読めないため
 * 共通化している。Error 以外が投げられた場合も表示できる形にして返す。
 *
 * @param {unknown} e catch で受け取った値
 * @returns {string} 画面に出せるメッセージ
 */
function errorMessage(e) {
    return e instanceof Error ? e.message : String(e);
}

/* ============================================================
   API 呼び出し
   ============================================================ */

/**
 * 管理者と利用者でログイン画面を分けているため、戻す先も役割で変える。
 * @returns {string} ログイン画面のパス
 */
function loginPagePath() {
    return viewerIsAdmin ? "/adminLogin.html" : "/userLogin.html";
}

/**
 * セッション切れをJSON解析より先に扱い、同時に複数のAPIが失敗しても一度だけ遷移する。
 * @param {string} path APIパス
 * @param {RequestInit} [options] 通信設定
 * @returns {Promise<Response>} 応答
 */
async function authenticatedFetch(path, options) {
    const response = await fetch(path, options);
    if (response.status === 401) {
        if (!["/userLogin.html", "/adminLogin.html"].includes(location.pathname) && !loginRedirectPending) {
            loginRedirectPending = true;
            const target = location.pathname + location.search + location.hash;
            location.assign(loginPagePath() + "?expired=1&returnTo=" + encodeURIComponent(target));
        }
        throw new Error("ログインの有効期限が切れました。ログインし直してください");
    }
    return response;
}

let loginRedirectPending = false;
/** ログアウト後・セッション切れのときに戻すログイン画面を、見ている人の役割で決めるため。 */
let viewerIsAdmin = false;

/**
 * GET でJSONを取得する。
 *
 * @param {string} path 呼び出す API のパス
 * @returns {Promise<any>} 応答のJSON
 */
async function apiGet(path) {
    const res = await authenticatedFetch(path);
    if (!res.ok) throw new Error(await extractError(res));
    return res.json();
}

/**
 * POST でJSONを送る。
 *
 * @param {string} path 呼び出す API のパス
 * @param {unknown} body 送信する本文
 * @returns {Promise<any>} 応答のJSON。204 の場合は null
 */
async function apiPost(path, body) {
    const res = await authenticatedFetch(path, {
        method: "POST",
        headers: { "Content-Type": "application/json", ...csrfHeaders() },
        body: JSON.stringify(body)
    });
    if (!res.ok) throw new Error(await extractError(res));
    return res.status === 204 ? null : res.json();
}

/**
 * PUT でJSONを送る。応答本文は使わない。
 *
 * @param {string} path 呼び出す API のパス
 * @param {unknown} body 送信する本文
 * @returns {Promise<void>}
 */
async function apiPut(path, body) {
    const res = await authenticatedFetch(path, {
        method: "PUT",
        headers: { "Content-Type": "application/json", ...csrfHeaders() },
        body: JSON.stringify(body)
    });
    if (!res.ok) throw new Error(await extractError(res));
}

/**
 * DELETE を送る。
 *
 * @param {string} path 呼び出す API のパス
 * @returns {Promise<any>} 応答のJSON。204 の場合は null
 */
async function apiDelete(path) {
    const res = await authenticatedFetch(path, { method: "DELETE", headers: csrfHeaders() });
    if (!res.ok) throw new Error(await extractError(res));
    // 削除の多くは 204（本文なし）だが、削除結果の集計を返すものもある
    return res.status === 204 ? null : res.json();
}

/**
 * エラー応答から画面に出すメッセージを取り出す。
 *
 * @param {Response} res 失敗した応答
 * @returns {Promise<string>} エラーメッセージ
 */
async function extractError(res) {
    try {
        const data = await res.json();
        return data.error || res.statusText;
    } catch {
        return res.statusText;
    }
}

/**
 * HTML に差し込む値をエスケープする。
 *
 * @param {unknown} value 差し込みたい値
 * @returns {string} エスケープ済みの文字列
 */
function escapeHtml(value) {
    if (value === null || value === undefined) return "";
    /** @type {Record<string, string>} */
    const entities = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
    return String(value).replace(/[&<>"']/g, (c) => entities[c]);
}

/** collapsibleCell が 1 行に収める上限。これを超えるか改行を含む場合だけ折りたためる形にする。 */
const CELL_PREVIEW_LIMIT = 60;

/**
 * 表のセルの中身を組み立てる。長い内容や複数行の内容は details で折りたたむ。
 *
 * セルを折り返してしまうと行の高さが不揃いになり一覧全体が読みにくくなるため、
 * 既定では 1 行に収め、全文を読みたいときだけ開く形にしている。
 * 複数の画面（ログ・通知履歴・DB管理）で同じ判断が要るので共通処理として切り出した。
 *
 * @param {string} value セルに表示したい値
 * @returns {string} セルへ差し込む HTML
 */
function collapsibleCell(value) {
    if (value === null || value === undefined || value === "") return "-";

    const text = String(value);
    const firstLine = text.split("\n")[0];
    const needsFold = text.includes("\n") || text.length > CELL_PREVIEW_LIMIT;

    if (!needsFold) return escapeHtml(text);

    const preview = firstLine.length > CELL_PREVIEW_LIMIT
        ? `${firstLine.slice(0, CELL_PREVIEW_LIMIT)}…`
        : `${firstLine}…`;
    return `<details><summary>${escapeHtml(preview)}</summary><pre>${escapeHtml(text)}</pre></details>`;
}

/**
 * バイト数を読みやすい単位（B/KB/MB/GB）に整形する。
 * 録画のディスク使用量表示（ダッシュボード・録画一覧）で共通して使う。
 *
 * @param {number|null} bytes バイト数。null/undefined なら "-" を返す
 * @returns {string} 整形済みの文字列
 */
function formatFileSize(bytes) {
    if (bytes === null || bytes === undefined) return "-";
    const units = ["B", "KB", "MB", "GB"];
    let value = bytes;
    let unitIndex = 0;
    while (value >= 1024 && unitIndex < units.length - 1) {
        value /= 1024;
        unitIndex++;
    }
    return `${value.toFixed(1)} ${units[unitIndex]}`;
}

/**
 * 秒数を「h:mm:ss」（1時間未満なら「m:ss」）に整形する。
 * 録画一覧のサムネイルと再生画面で共通して使う。
 *
 * @param {number|null} seconds 秒数。null/undefined なら "-" を返す
 * @returns {string} 整形済みの文字列
 */
function formatDuration(seconds) {
    if (seconds === null || seconds === undefined) return "-";
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const rest = Math.floor(seconds % 60);
    /** @param {number} n */
    const pad = (n) => String(n).padStart(2, "0");
    return hours > 0 ? `${hours}:${pad(minutes)}:${pad(rest)}` : `${minutes}:${pad(rest)}`;
}

/**
 * URL のクエリ文字列から値を取り出す。
 * 再生画面（player.html?id=◯）のように、画面遷移で対象を受け渡す箇所で使う。
 *
 * @param {string} name パラメータ名
 * @returns {string|null} 値。無ければ null
 */
function queryParam(name) {
    return new URLSearchParams(window.location.search).get(name);
}

/**
 * ISO形式の日時文字列（例: "2026-09-13T20:05:27.691679"）から、
 * 秒未満の精度を落とした「YYYY-MM-DD HH:mm:ss」を作る。
 * 一覧では秒未満の精度は不要な一方、必要な人のために元の値は残しておきたいので、
 * 表示はこちらを使い、元の値は datetimeCell() でクリックすると見られるようにする。
 *
 * @param {string} iso ISO形式の日時文字列
 * @returns {string} 秒までに整形した文字列
 */
function formatDateTimeSimple(iso) {
    return iso.replace("T", " ").slice(0, 19);
}

/**
 * 一覧のセルに日時を表示するための HTML を組み立てる。
 * 既定では秒までの簡潔な表示にし、クリックすると秒未満の精度を含む元の値に切り替わる
 * （bindDatetimeCells() と対で使う）。
 *
 * @param {string|null} iso ISO形式の日時文字列。null/undefined なら "-" を返す
 * @returns {string} セルへ差し込む HTML
 */
function datetimeCell(iso) {
    if (!iso) return "-";
    const simple = formatDateTimeSimple(iso);
    return `<span class="datetimeCell revealable" title="クリックで秒未満の精度を表示"
                  data-simple="${escapeHtml(simple)}" data-full="${escapeHtml(iso)}">${escapeHtml(simple)}</span>`;
}

/**
 * datetimeCell() で作ったセルにクリックでの表示切り替えを組み込む。
 * 一覧を読み直すたびに呼び直しても、束縛済みの要素には再度付けない。
 *
 * @param {ParentNode} root クリック対象を探す起点（通常は tbody）
 */
function bindDatetimeCells(root) {
    const cells = /** @type {NodeListOf<HTMLElement>} */ (root.querySelectorAll(".datetimeCell"));
    for (const cell of cells) {
        if (cell.dataset.bound) continue;
        cell.dataset.bound = "1";
        let showingFull = false;
        cell.addEventListener("click", () => {
            showingFull = !showingFull;
            cell.textContent = showingFull ? (cell.dataset.full ?? "") : (cell.dataset.simple ?? "");
        });
    }
}

/**
 * チャンネル名のセルをクリックするとチャンネルIDの表示を切り替える。
 * 常時表示すると1列使ってしまうため、必要なとき（CLIコマンドに渡す時など）だけ出す
 * （チャンネル管理・ダッシュボードの「現在配信中」一覧で共通して使う）。
 *
 * @param {HTMLTableCellElement} td チャンネル名を表示しているセル
 * @param {string} youtubeChannelId 表示するチャンネルID
 */
function toggleChannelIdReveal(td, youtubeChannelId) {
    const existing = td.querySelector(".channelIdReveal");
    if (existing) {
        existing.remove();
        return;
    }
    const span = document.createElement("span");
    span.className = "channelIdReveal muted";
    span.textContent = ` (${youtubeChannelId})`;
    td.appendChild(span);
}

/**
 * 見出し（`thead th[data-sort]`）を押すと、その列で表を並べ替えられるようにする。
 * 全件を画面に持っている表なら API を変えずに済むため、画面の中だけで並べ替える。
 * 状態を表の `data-sort-column` / `data-sort-direction` に持たせるのは、一覧を読み直して
 * tbody を作り直しても表の要素そのものは残るため（読み直した後に {@link applyTableSort} を
 * 呼べば同じ並びに戻る）。見出しの中身を button で包むのは、Tab と Enter でも押せるようにするため。
 * 表ごとに初期化で 1 回だけ呼ぶ（呼び直すと button が二重になる）。
 *
 * @param {HTMLTableElement} table 並べ替えを付ける表
 */
function makeTableSortable(table) {
    for (const th of /** @type {NodeListOf<HTMLTableCellElement>} */ (table.querySelectorAll("thead th[data-sort]"))) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "sortButton";
        button.append(...th.childNodes);
        const mark = document.createElement("span");
        mark.className = "sortMark";
        // 向きは aria-sort で伝わるので、記号は読み上げさせない
        mark.setAttribute("aria-hidden", "true");
        button.appendChild(mark);
        th.appendChild(button);
        button.addEventListener("click", () => {
            const column = String(th.cellIndex);
            const ascending = table.dataset.sortColumn === column && table.dataset.sortDirection === "ascending";
            table.dataset.sortColumn = column;
            table.dataset.sortDirection = ascending ? "descending" : "ascending";
            applyTableSort(table);
        });
    }
}

/**
 * {@link makeTableSortable} で選ばれた列と向きで tbody の行を並べ直し、見出しの表示を合わせる。
 * 行は作り直さず移動するだけなので、行に付けたイベントはそのまま残る。
 * 空の値を向きに関わらず末尾に置くのは、降順にしたとたん未設定の行が先頭に並んで
 * 見たい行が押し出されるのを避けるため。
 *
 * @param {HTMLTableElement} table 並べ替える表
 */
function applyTableSort(table) {
    const column = table.dataset.sortColumn;
    if (column === undefined) return;
    const index = Number(column);
    const descending = table.dataset.sortDirection === "descending";
    const headers = [.../** @type {NodeListOf<HTMLTableCellElement>} */ (table.querySelectorAll("thead th[data-sort]"))];
    for (const th of headers) {
        const selected = th.cellIndex === index;
        if (selected) {
            th.setAttribute("aria-sort", descending ? "descending" : "ascending");
        } else {
            th.removeAttribute("aria-sort");
        }
        const mark = th.querySelector(".sortMark");
        if (mark) mark.textContent = selected ? (descending ? " ▼" : " ▲") : "";
    }

    const numeric = headers.find((th) => th.cellIndex === index)?.dataset.sort === "number";
    /** @param {HTMLTableRowElement} row */
    const valueOf = (row) => {
        const cell = row.cells[index];
        return cell ? (cell.dataset.sortValue ?? (cell.textContent ?? "").trim()) : "";
    };
    const tbody = table.tBodies[0];
    const rows = [...tbody.rows].sort((a, b) => {
        const va = valueOf(a);
        const vb = valueOf(b);
        if (va === "" || vb === "") return Number(va === "") - Number(vb === "");
        const diff = numeric ? Number(va) - Number(vb) : va.localeCompare(vb, "ja", { numeric: true });
        return descending ? -diff : diff;
    });
    tbody.append(...rows);
}

/**
 * 動画IDを YouTube の視聴ページへのリンクにする。
 * IDをコピーしてURLを手で組み立てる手間をなくすため、一覧のどこでも同じ形で使えるようにしている。
 *
 * @param {string|null} videoId 動画ID。未設定なら "-" を返す
 * @returns {string} セルへ差し込む HTML
 */
function videoLink(videoId) {
    if (!videoId) return "-";
    const safe = escapeHtml(videoId);
    // 別タブで開く。rel は別タブ側から開き元を操作されないようにするための定番の指定
    return `<a href="https://www.youtube.com/watch?v=${safe}" target="_blank" rel="noopener noreferrer">${safe}</a>`;
}

/**
 * チャンネル名をチャンネルページへのリンクにする。
 *
 * URL は配信元ごとに形が違う（Twitch はログイン名から作る）ため、サーバーが組み立てた
 * channelUrl をそのまま使う。未登録チャンネルの録画（URL指定でダウンロードしたもの）や
 * ログイン名未取得の Twitch では null になり、その場合はリンクにせず名前だけを出す。
 *
 * @param {string} channelName 表示するチャンネル名
 * @param {string|null} channelUrl リンク先のチャンネルページ URL。無ければ null
 * @returns {string} セルへ差し込む HTML
 */
function channelLink(channelName, channelUrl) {
    return externalLink(channelName, channelUrl);
}

/** 録画の状態を画面表示用の日本語にする。録画一覧と再生画面で同じ語を使う。 */
const RECORDING_STATUS_LABEL = {
    RECORDING: "録画中",
    COMPLETED: "完了",
    PARTIAL: "途中まで",
    FAILED: "失敗",
};

/**
 * 録画の状態を表示用の HTML にする。
 *
 * <p>録画中だけランプを明滅させる。一覧を開いたまま終わるのを待つことがあるため、
 * 「まだ動いている」ことが目の端で分かるようにしている。
 *
 * @param {"RECORDING"|"COMPLETED"|"PARTIAL"|"FAILED"} status 録画の状態
 * @returns {string} 差し込む HTML
 */
function recordingStatusLabel(status) {
    if (status === "RECORDING") {
        return statusLamp("recording", RECORDING_STATUS_LABEL.RECORDING, "まだ録画を続けています");
    }
    if (status === "FAILED") {
        return `<span class="error">${RECORDING_STATUS_LABEL.FAILED}</span>`;
    }
    if (status === "PARTIAL") {
        // 再生はできるので警告色にはしないが、完全でないことは分かるようにする
        return `<span title="配信の途中で録画が終わったため、そこまでの内容だけが入っています">`
            + `${RECORDING_STATUS_LABEL.PARTIAL}</span>`;
    }
    return RECORDING_STATUS_LABEL[status] || escapeHtml(status);
}

/** 再生できる状態。途中で切れた録画も、そこまでは再生できる。 */
const PLAYABLE_RECORDING_STATUSES = ["COMPLETED", "PARTIAL"];

/**
 * 録画を端末に保存するときのファイル名（配信タイトル＋.mp4）。
 *
 * <p>サーバー上の名前（動画ID.mp4）のままだと、端末の「ファイル」アプリに並んだときに何の録画か分からないため、
 * 配信タイトルを使う。ファイル名に使えない文字（Windows で使えない \ / : * ? " < > | と制御文字）は _ に置き換える。
 * ブラウザに任せると、置き換え方がブラウザごとに違うため。長いタイトルは、端末によってはファイル名の長さの上限を
 * 超えるため 100 文字で切る。
 *
 * @param {Recording} recording 録画
 * @returns {string} ファイル名
 */
function recordingDownloadName(recording) {
    const title = (recording.videoTitle ?? "").replace(/[\\/:*?"<>|\p{Cc}]/gu, "_").trim();
    // 文字は符号位置で数える。UTF-16 の単位で切ると、絵文字が半分に割れて壊れた文字が残るため
    return `${Array.from(title).slice(0, 100).join("") || recording.videoId}.mp4`;
}

/**
 * サムネイル枠の中身を組み立てる。
 * 完了していてサムネイルがある録画だけ画像を出し、それ以外は理由が分かる代替表示にする
 * （画像の生成は巡回のたびに後追いで行われるため、完了直後は未生成のことがある）。
 *
 * @param {Recording} recording 録画履歴 1 件
 * @returns {string} サムネイル枠へ差し込む HTML
 */
function thumbnailContent(recording) {
    if (recording.thumbnailPath) {
        return `<img class="thumb" src="/recordings/${encodeURI(recording.thumbnailPath)}"`
            + ` alt="${escapeHtml(recording.videoTitle)}" loading="lazy">`;
    }
    const reason = recording.status === "RECORDING" ? "録画中"
        : recording.status === "FAILED" ? "録画失敗"
        : "サムネイル生成待ち";
    return `<span class="thumbPlaceholder">${reason}</span>`;
}

/**
 * 録画 1 件分のカードを組み立てる。録画一覧と再生画面の「同じチャンネルの録画」で共通して使う。
 *
 * @param {Recording} recording 録画履歴 1 件
 * @param {(() => void)|null} onDelete 削除後に呼ぶ処理。省略すると削除ボタン自体を出さない
 * @param {boolean} linkToPlayer サムネイルと題名を再生画面（{@code /player.html}）への
 *        リンクにするか。{@code false} にすると素の要素になり、カード側で
 *        クリックを拾ってその場で再生できる（利用者向けの画面はこちら。
 *        {@code /player.html} は管理者専用のため、リンクのままだと 403 になる）
 * @param {((recording: Recording, button: HTMLButtonElement) => void)|null} onPlay
 *        再生ボタンを押したときの処理。{@code linkToPlayer} が {@code true} の
 *        画面では渡さない（リンクで飛べるためボタンが要らない）。
 *
 *        <p>カード全体のクリックだけで再生させると、マウスでしか操作できず、
 *        スクリーンリーダーにも「押せば何が起きるか」が伝わらない。
 *        ボタンにすることで Tab 移動・Enter/Space・読み上げのすべてが
 *        ブラウザの標準機能で揃う（実際に指摘を受けた）。
 * @param {((recording: Recording, kind: RecordingMarkKind, button: HTMLButtonElement) => void)|null} onToggleMark
 *        視聴済み・お気に入りのボタンを押したときの処理。渡したときだけボタンを出す
 *        （印を扱わない画面（管理画面の再生画面の関連一覧など）の見た目は変えないため）。
 * @param {(recording: Recording) => string} [playerHref] サムネイルと題名のリンク先。省略すると管理者の再生画面。
 *        利用者の 1 枚のページ（my.html）は {@code /my/watch/<ID>} を渡す。{@code /player.html} へ移ると
 *        ページが読み込み直され、ミニプレーヤーで再生中の動画が止まるため
 * @returns {HTMLElement} カード要素
 */
function buildVideoCard(recording, onDelete, linkToPlayer = true, onPlay = null, onToggleMark = null,
                        playerHref = (r) => `/player.html?id=${r.id}`) {
    const card = document.createElement("div");
    card.className = "videoCard";

    const href = escapeHtml(playerHref(recording));
    const duration = recording.durationSeconds
        ? `<span class="duration">${formatDuration(recording.durationSeconds)}</span>`
        : "";

    // 再生画面へ飛ばさない場合はリンクにしない。リンクのままだと、カード自身の
    // クリック処理（その場で再生）より先にブラウザの画面遷移が起きてしまう。
    // 利用者向けの画面では /player.html が管理者専用なので 403 になる
    const thumbnail = linkToPlayer
        ? `<a class="thumbLink" href="${href}">${thumbnailContent(recording)}${duration}</a>`
        : `<span class="thumbLink">${thumbnailContent(recording)}${duration}</span>`;
    const title = linkToPlayer
        ? `<a href="${href}">${escapeHtml(recording.videoTitle)}</a>`
        : escapeHtml(recording.videoTitle);
    const status = recordingStatusLabel(recording.status);
    // 録画中は中断させたくないので削除ボタンを出さない（API 側も 409 で弾く）
    const deletable = onDelete !== null && recording.status !== "RECORDING";
    const playable = PLAYABLE_RECORDING_STATUSES.includes(recording.status);
    // 再生できない録画でもボタン自体は出す。消してしまうと「なぜ操作できないか」が
    // スクリーンリーダーにも見た目にも伝わらない。disabled にして title で理由を添える
    const playButton = onPlay
        ? `<button type="button" class="playBtn" aria-label="${escapeHtml(recording.videoTitle)}を再生する"`
          + ` ${playable ? "" : "disabled"}`
          + ` title="${playable ? "" : "この録画は再生できるファイルが残っていません"}">再生</button>`
        : "";

    card.innerHTML = `
        ${thumbnail}
        <div class="cardBody">
          <div class="cardTitle">${title}</div>
          <div class="muted">${channelLink(recording.channelName, recording.channelUrl)}</div>
          <div class="muted">${datetimeCell(recording.startedAt)} ・ ${status}`
        + ` ・ ${formatFileSize(recording.fileSizeBytes)} ・ <span class="playCount">再生 ${Number(recording.playCount)} 回</span></div>
          <div class="cardActions">${playButton}${deletable ? '<button class="deleteBtn">削除</button>' : ""}</div>
        </div>
    `;

    const playBtn = /** @type {HTMLButtonElement|null} */ (card.querySelector(".playBtn"));
    if (playBtn && onPlay) {
        playBtn.addEventListener("click", () => onPlay(recording, playBtn));
    }

    if (onToggleMark) {
        const actions = query(".cardActions", card);
        actions.prepend(...RECORDING_MARK_KINDS.map((kind) => recordingMarkButton(recording, kind, onToggleMark)));
    }

    const deleteBtn = card.querySelector(".deleteBtn");
    if (deleteBtn && onDelete) {
        deleteBtn.addEventListener("click", () => deleteRecording(recording, onDelete));
    }
    return card;
}

/**
 * 確認してから録画を削除する。アーカイブ一覧のカードと表の両方から呼ぶため、
 * 確認文言・通知の出し方を 1 か所にまとめている。
 *
 * @param {Recording} recording 削除する録画
 * @param {() => void} onDelete 削除後に呼ぶ処理
 */
async function deleteRecording(recording, onDelete) {
    if (!confirm("この録画を削除しますか？（録画ファイルも一緒に削除されます）")) return;
    try {
        await apiDelete(`/api/recordings/${recording.id}`);
        clearError();
        showToast(`「${recording.videoTitle}」を削除しました`, "danger");
        onDelete();
    } catch (e) {
        showError(errorMessage(e));
    }
}

/** @typedef {"watched"|"favorite"} RecordingMarkKind 録画に付ける印の種類。API のパスと本文のキーを兼ねる */

/** @type {RecordingMarkKind[]} */
const RECORDING_MARK_KINDS = ["watched", "favorite"];

/**
 * 印ごとの表示。読み上げ名（name）は状態によらず固定にし、状態は aria-pressed で伝える。
 * 見た目の文字（「視聴済み」/「未視聴」）まで読み上げ名にすると、押すたびに別のボタンに聞こえるため。
 */
const RECORDING_MARK_LABEL = {
    watched: { name: "視聴済み", on: "視聴済み", off: "未視聴" },
    favorite: { name: "お気に入り", on: "★", off: "☆" },
};

/**
 * 印の切り替えボタンの表示を状態に合わせる。押した直後と失敗して戻すときの両方で使う。
 *
 * @param {HTMLButtonElement} button 対象のボタン
 * @param {RecordingMarkKind} kind 印の種類
 * @param {boolean} on 印が付いているか
 */
function renderRecordingMarkButton(button, kind, on) {
    button.setAttribute("aria-pressed", String(on));
    button.textContent = on ? RECORDING_MARK_LABEL[kind].on : RECORDING_MARK_LABEL[kind].off;
}

/**
 * 印の切り替えボタンを作る。カードと表で同じ見た目・同じ操作にするため共通化している。
 *
 * @param {Recording} recording 対象の録画
 * @param {RecordingMarkKind} kind 印の種類
 * @param {(recording: Recording, kind: RecordingMarkKind, button: HTMLButtonElement) => void} onToggle 押したときの処理
 * @returns {HTMLButtonElement} ボタン
 */
function recordingMarkButton(recording, kind, onToggle) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `markBtn markBtn-${kind}`;
    button.setAttribute("aria-label", RECORDING_MARK_LABEL[kind].name);
    renderRecordingMarkButton(button, kind, Boolean(recording[kind]));
    button.addEventListener("click", () => onToggle(recording, kind, button));
    return button;
}

/**
 * {@link bindRecordingSearch} の戻り値。
 *
 * @typedef {object} RecordingSearch
 * @property {() => void} restore URL の条件・ページ・表示を入力欄へ戻す。読み込み時と「戻る」「進む」で呼ぶ
 *           （チャンネル・ジャンルの選択肢が揃ってから呼ぶ。先に戻すと、選択肢に無い値として捨てられる）
 * @property {(page: number) => void} goToPage 指定のページ（0 始まり）へ移って読み直す
 * @property {() => URLSearchParams} apiParams 検索 API のクエリ（ページ・空でない条件）
 * @property {(data: {content: Recording[], totalPages: number}) => boolean} show 読み込んだ結果を今の表示
 *           （カード／リスト）で描き、ページ送りを合わせる。ページが結果の範囲を超えていたら（URL の page が古い・
 *           最後のページの録画を消した）、空のページを出さずに最後のページへ直して false を返す（呼び出し側で読み直す）
 */

/**
 * アーカイブの検索一式（条件のフォームと URL の同期・API のクエリ・ページ送り・カード／リストの切り替え）を結びつける。
 *
 * <p>管理画面（recordings.js）と利用者のアーカイブで同じ検索を持つため、片方だけ直して動きがずれないよう 1 か所に
 * まとめた。利用者のアーカイブは 1 枚のページ（my.html）の中に部品を JS で組み立てるため、要素は ID で決め打ちせず
 * 引数の入れ物の中から探す。条件の入力欄は name を URL と API のキーにする（フォームに置いた欄がそのまま条件になり、
 * 管理画面だけの「状態」もフォームに置くだけで済む）。
 *
 * <p>「戻る」「進む」（popstate）はここでは拾わない。利用者のアーカイブではルーター（my-app.js）が拾って画面ごと
 * 描き直すため、ここでも拾うと二重に読み直す。拾う画面が {@link RecordingSearch} の restore を呼ぶ。
 *
 * @param {object} parts 部品と、画面ごとに違う処理
 * @param {HTMLFormElement} parts.form 検索条件のフォーム。条件の入力欄に name、条件のクリアに type="reset" のボタンを置く
 * @param {HTMLElement} parts.viewToggle カード（.cardViewBtn）とリスト（.listViewBtn）の切り替えボタンの入れ物
 * @param {HTMLElement} parts.grid カードを並べる入れ物
 * @param {HTMLElement} parts.list 表の入れ物。中の tbody に行を入れる
 * @param {HTMLElement} parts.pager ページ送り。前へ（.prevBtn）・番号を並べる入れ物（.pageNumbers）・次へ（.nextBtn）
 * @param {() => void} parts.load 条件・ページを変えたときに一覧を読み直す処理
 * @param {(recording: Recording) => HTMLElement} parts.buildCard カード 1 枚を作る（削除や印の API が画面ごとに違う）
 * @param {(recording: Recording) => HTMLTableRowElement} parts.buildRow 表の 1 行を作る
 * @param {string} parts.empty 0 件のときにカード枠へ出す HTML
 * @param {(url: URL, replace: boolean) => void} [parts.writeUrl] URL を書く処理。省くと履歴に積む（replace のときは
 *        今の履歴を置き換える）。利用者のアーカイブはルーターと食い違わないよう差し替えられる
 * @returns {RecordingSearch} 読み込みの前後で呼ぶ操作
 */
function bindRecordingSearch({ form, viewToggle, grid, list, pager, load, buildCard, buildRow, empty,
        writeUrl = (url, replace) => history[replace ? "replaceState" : "pushState"](null, "", url) }) {
    let page = 0;
    let totalPages = 1;
    /** 今の表示。既定のカードのときは URL に載せない。 */
    /** @type {"card"|"list"} */
    let view = "card";
    /** 今表示している録画。表示を切り替えたとき、一覧を読み直さずに描き直すため。 */
    /** @type {Recording[]} */
    let shown = [];

    const fields = /** @type {NodeListOf<HTMLInputElement|HTMLSelectElement>} */ (form.querySelectorAll("[name]"));
    const cardButton = query(".cardViewBtn", viewToggle);
    const listButton = query(".listViewBtn", viewToggle);
    const prevButton = /** @type {HTMLButtonElement} */ (query(".prevBtn", pager));
    const nextButton = /** @type {HTMLButtonElement} */ (query(".nextBtn", pager));
    const pageNumbers = query(".pageNumbers", pager);
    /**
     * キーワード以外の条件の折りたたみ（スマホ幅だけ畳む）。アーカイブを開く目的は録画を選ぶことなので、
     * 条件の項目で最初の 1 画面を埋めず、録画のカードを見せるため。
     */
    const more = /** @type {HTMLDetailsElement|null} */ (form.querySelector("details.filterMore"));
    const moreSummary = more?.querySelector("summary") ?? null;
    const moreCount = more?.querySelector(".filterCount") ?? null;
    if (more && moreSummary) {
        // PC 幅では summary を隠して常に開く（今までと同じ見た目）。URL に条件が付いているときも開き、
        // 何で絞り込んでいるかを見せる
        more.open = window.matchMedia("(min-width: 761px)").matches
            || countConditions(new URLSearchParams(location.search)) > 0;
        // summary は読み上げでも開閉の状態が伝わるよう、aria-expanded を開閉に合わせる
        const syncExpanded = () => moreSummary.setAttribute("aria-expanded", String(more.open));
        more.addEventListener("toggle", syncExpanded);
        syncExpanded();
    }

    /**
     * @param {HTMLInputElement|HTMLSelectElement} field 条件の入力欄
     * @returns {string} 前後の空白を除いた値。チェックボックスは付いていれば "true"、外れていれば空
     */
    function valueOf(field) {
        if (field instanceof HTMLSelectElement) return field.value;
        if (field.type === "checkbox") return field.checked ? "true" : "";
        return field.value.trim();
    }

    /**
     * 今の条件とページを URL に書き出す。
     * 再生画面へ移って「戻る」を押したとき、同じ条件・同じページに戻れるようにするため。
     * 既定値（空・選択欄の先頭の選択肢）は載せない。載せると条件を付けていないのに URL が長くなり、
     * チャンネル一覧からの `?channelId=` のような短いリンクと見分けにくくなるため。
     * ページは画面の表示に合わせて 1 始まりで載せる（API の 0 始まりのままだと、URL を見た人が 1 ずれて読む）。
     *
     * @param {boolean} replace 読み込み直後の整えやページ超過の補正は履歴を増やさない
     */
    function syncUrl(replace = false) {
        const url = new URL(location.href);
        for (const field of fields) url.searchParams.delete(field.name);
        url.searchParams.delete("page");
        url.searchParams.delete("view");
        for (const field of fields) {
            const value = valueOf(field);
            const isDefault = !value || (field instanceof HTMLSelectElement && value === field.options[0].value);
            if (!isDefault) url.searchParams.set(field.name, value);
        }
        if (page > 0) url.searchParams.set("page", String(page + 1));
        if (view === "list") url.searchParams.set("view", "list");
        if (url.href !== location.href) writeUrl(url, replace);
        // 畳んだ「絞り込み・並び順」に、効いている条件の数を添える。畳んだままだと、絞り込みが残っていて
        // 録画が少なく見えているのか、そもそも少ないのかが分からないため。入力途中の値ではなく、
        // URL に書き出した（＝今の一覧に効いている）条件を数える
        if (moreCount) {
            const count = countConditions(url.searchParams);
            moreCount.textContent = count > 0 ? `${count}件の条件を指定中` : "";
        }
    }

    /**
     * @param {URLSearchParams} params URL のクエリ
     * @returns {number} キーワード以外で指定されている条件の数（キーワードは畳んでも見えているので数えない）
     */
    function countConditions(params) {
        return Array.from(fields).filter(f => f.name !== "keyword" && params.get(f.name)).length;
    }

    /**
     * URL から条件とページを入力欄へ戻す。
     * select は選択肢に無い値（削除済みチャンネル・無くなったジャンルなど）を入れると空になるため、
     * 無い値は既定に戻す。日付欄も yyyy-MM-dd 以外は空になるので、そのまま入れてよい。
     */
    function restore() {
        const params = new URLSearchParams(location.search);
        for (const field of fields) {
            const value = (params.get(field.name) || "").trim();
            if (field instanceof HTMLSelectElement) {
                field.value = Array.from(field.options).some(o => o.value === value) ? value : field.options[0].value;
            } else if (field.type === "checkbox") {
                field.checked = value === "true";
            } else {
                field.value = field.name === "keyword" ? value.slice(0, 200) : value;
            }
        }
        const requested = Number.parseInt(params.get("page") || "", 10);
        page = Number.isFinite(requested) && requested > 1 ? requested - 1 : 0;
        view = params.get("view") === "list" ? "list" : "card";
        syncUrl(true);
    }

    /** @param {number} to 移動先（0 始まり） */
    function goToPage(to) {
        page = to;
        syncUrl();
        load();
    }

    function apiParams() {
        const params = new URLSearchParams({ page: String(page) });
        for (const field of fields) {
            const value = valueOf(field);
            if (value) params.set(field.name, value);
        }
        return params;
    }

    /**
     * ページ番号のボタンを並べる。数千件（100ページ超）でも行が溢れないよう、
     * 先頭・末尾・今のページの前後 2 つだけを出し、間は「…」で詰める。
     */
    function renderPageNumbers() {
        pageNumbers.replaceChildren();
        const pages = [];
        for (let p = 0; p < totalPages; p++) {
            // 「…」が 1 ページ分だけを隠すことになる場合（例: 1 2 3 … 5）は、そのページを出す
            const onlyHiddenPage = Math.abs(p - page) === 3 && (p === 1 || p === totalPages - 2);
            if (p === 0 || p === totalPages - 1 || Math.abs(p - page) <= 2 || onlyHiddenPage) pages.push(p);
        }
        let previous = -1;
        for (const p of pages) {
            if (p - previous > 1) {
                const gap = document.createElement("span");
                gap.className = "muted";
                gap.textContent = "…";
                pageNumbers.appendChild(gap);
            }
            const btn = document.createElement("button");
            btn.type = "button";
            btn.textContent = String(p + 1);
            if (p === page) {
                btn.setAttribute("aria-current", "page");
                btn.disabled = true;
            } else {
                btn.addEventListener("click", () => goToPage(p));
            }
            pageNumbers.appendChild(btn);
            previous = p;
        }
    }

    /** 今の表示（カード / リスト）で shown を描く。 */
    function renderResults() {
        const asList = view === "list";
        cardButton.setAttribute("aria-pressed", String(!asList));
        listButton.setAttribute("aria-pressed", String(asList));

        const tbody = query("tbody", list);
        grid.innerHTML = "";
        tbody.innerHTML = "";
        if (shown.length === 0) {
            // 空のときは表示によらずカード枠に案内を出す（見出しだけの空の表より理由が伝わる）
            grid.hidden = false;
            list.hidden = true;
            grid.innerHTML = empty;
            return;
        }
        grid.hidden = asList;
        list.hidden = !asList;
        for (const r of shown) {
            if (asList) tbody.appendChild(buildRow(r));
            else grid.appendChild(buildCard(r));
        }
        bindDatetimeCells(asList ? tbody : grid);
    }

    /** @param {"card"|"list"} to 切り替え先の表示 */
    function switchView(to) {
        if (view === to) return;
        view = to;
        syncUrl();
        renderResults();
    }

    /** @param {{content: Recording[], totalPages: number}} data 検索 API の応答 */
    function show(data) {
        if (page > 0 && page >= data.totalPages) {
            page = Math.max(0, data.totalPages - 1);
            syncUrl(true);
            return false;
        }
        totalPages = data.totalPages;
        shown = data.content;
        renderResults();
        prevButton.disabled = page <= 0;
        nextButton.disabled = page + 1 >= totalPages;
        // 押せないページ送りを残さない（1 ページに収まるとき、0 件を含む）。
        pager.hidden = totalPages <= 1;
        renderPageNumbers();
        return true;
    }

    form.addEventListener("submit", (ev) => {
        ev.preventDefault();
        goToPage(0);
    });
    form.addEventListener("reset", (ev) => {
        // ブラウザ標準のリセットは、このイベントの後で値を戻す。戻した値で URL を書いて読み直すため、ここで戻す
        ev.preventDefault();
        for (const field of fields) {
            if (field instanceof HTMLSelectElement) field.value = field.options[0].value;
            else if (field.type === "checkbox") field.checked = false;
            else field.value = "";
        }
        goToPage(0);
    });
    cardButton.addEventListener("click", () => switchView("card"));
    listButton.addEventListener("click", () => switchView("list"));
    prevButton.addEventListener("click", () => {
        if (page > 0) goToPage(page - 1);
    });
    nextButton.addEventListener("click", () => {
        if (page + 1 < totalPages) goToPage(page + 1);
    });

    return { restore, goToPage, apiParams, show };
}

/* ============================================================
   再生
   ============================================================ */

/**
 * ロック画面・通知・キーボードのメディアキーに、再生中の録画の情報と操作を出す。
 *
 * 画面を消したり別のアプリへ切り替えたりしたとき、ブラウザの外から一時停止・再開・早送りが
 * できないと、録画を聞き続ける使い方が成り立たないため。
 * Media Session API に対応していないブラウザでは何もしない（出なくても再生そのものは変わらない）。
 *
 * <p>小窓にする操作（enterpictureinpicture）も出すのは、再生中に別のタブへ移ったとき自動で小窓にするため。
 * Chrome などはこの操作を登録したページでしか自動の小窓を使わず、登録しないと、別のタブを見ながら
 * 再生を続けるには先に「小窓で再生」を押しておく必要がある。
 *
 * <p>同じ動画要素に何度呼んでもよく、2 回目以降は題名などの表示だけを差し替える。利用者の 1 枚のページ
 * （my.html）は 1 つの動画要素で録画を切り替え、そのたびに呼ぶ。毎回イベントを付けると、
 * 切り替えた回数だけ同じ処理が重なって走る。
 *
 * @param {HTMLVideoElement} video 操作の対象
 * @param {{title: string, artist: string, artworkUrl: string|null}} metadata
 *        題名（配信タイトル）・アーティスト（チャンネル名）・画像の URL（サムネイルが無ければ null）
 */
function bindMediaSession(video, metadata) {
    if (!("mediaSession" in navigator)) return;
    const session = navigator.mediaSession;
    session.metadata = new MediaMetadata({
        title: metadata.title,
        artist: metadata.artist,
        artwork: metadata.artworkUrl ? [{ src: metadata.artworkUrl }] : [],
    });
    if (video.dataset.mediaSessionBound) return;
    video.dataset.mediaSessionBound = "1";

    /** @param {number} seconds 進める秒数（負なら戻す）。先頭より前・末尾より後へは行かない */
    const seekBy = (seconds) => {
        video.currentTime = Math.min(Math.max(0, video.currentTime + seconds), video.duration);
    };
    /** @type {Array<[MediaSessionAction, MediaSessionActionHandler]>} */
    const handlers = [
        // 直後の一時停止で中断された等の失敗は、再生されないこと自体で分かるため何も出さない
        ["play", () => { video.play().catch(() => {}); }],
        ["pause", () => video.pause()],
        ["seekbackward", (details) => seekBy(-(details.seekOffset ?? 10))],
        ["seekforward", (details) => seekBy(details.seekOffset ?? 10)],
        ["seekto", (details) => {
            if (details.seekTime !== undefined) video.currentTime = details.seekTime;
        }],
        // lib.dom の MediaSessionAction にまだ無い操作なので型を補う。
        // 止めている動画まで小窓にすると、見るのをやめて別のタブへ移っただけで小窓が開いてしまうため再生中に限る。
        // 利用者が操作していない場面なので、断られても画面には出さず、調べられるよう残すだけにする
        [/** @type {MediaSessionAction} */ ("enterpictureinpicture"), () => {
            if (video.paused) return;
            video.requestPictureInPicture().catch((e) => console.warn(e));
        }],
    ];
    for (const [action, handler] of handlers) {
        // 未対応の操作を渡すと例外を投げるブラウザがある。出せる操作だけ出せればよいので 1 つずつ無視する
        try {
            session.setActionHandler(action, handler);
        } catch {
            // 未対応の操作
        }
    }

    // ロック画面のシークバーを実際の再生位置に合わせる。長さが分かる前（読み込み前）は渡せない
    const updatePosition = () => {
        if (!Number.isFinite(video.duration)) return;
        try {
            session.setPositionState({
                duration: video.duration,
                playbackRate: video.playbackRate,
                position: video.currentTime,
            });
        } catch {
            // setPositionState の無いブラウザ。位置が出ないだけで操作はできる
        }
    };
    for (const type of ["loadedmetadata", "seeked", "ratechange", "play", "pause"]) {
        video.addEventListener(type, updatePosition);
    }
}

/**
 * Safari の独自の小窓 API。lib.dom に型が無いため、使う分だけ補う。
 * Safari 以外には無いので、webkitSupportsPresentationMode の有無を確かめてから使う。
 *
 * @typedef {object} WebkitPresentationVideo
 * @property {((mode: string) => boolean)|undefined} webkitSupportsPresentationMode 指定の表示方法に対応しているか
 * @property {(mode: string) => void} webkitSetPresentationMode 表示方法を切り替える
 * @property {string} webkitPresentationMode 今の表示方法（"inline"・"picture-in-picture"・"fullscreen"）
 * @property {(() => void)|undefined} webkitEnterFullscreen 全画面の再生画面を開く（iPhone の Safari は、ここにある小窓のボタンからなら小窓にできる）
 */

/**
 * 小窓（Picture-in-Picture）の切り替えボタンを動画に結びつける。
 *
 * ほかのアプリやタブを見ながら再生を続けるため。ブラウザ標準の小窓は Chrome ではメニューの奥にあって
 * 気づきにくいので、ボタンとして画面に出す。標準の API が無い Safari は独自の API で切り替え、
 * どちらも無いブラウザではボタンを隠す（押しても何も起きないボタンを出さないため）。
 *
 * <p>iPhone の Safari は、ページ内（playsinline）で再生している動画を小窓にできず、標準の API は
 * 「The Video element does not support the Picture-in-Picture mode.」で断る（再生前・再生中とも実際に発生した）。
 * 一方、Safari の全画面の再生画面には小窓のボタンがあり、そこからなら小窓にできる。そこで Safari が
 * この動画を小窓にできないと答えたとき・断ったときは、全画面の再生画面を開いてそのボタンを案内する。
 * 英語の断り文句をエラーとして見せても、次に何をすればよいか分からないため。
 *
 * @param {HTMLButtonElement} button 切り替えボタン（最初の文言は「小窓で再生」にしておく）
 * @param {HTMLVideoElement} video 小窓にする動画
 */
function bindPictureInPictureButton(button, video) {
    /** @param {boolean} active 小窓の表示中か */
    const render = (active) => { button.textContent = active ? "小窓を閉じる" : "小窓で再生"; };
    const safari = /** @type {HTMLVideoElement & WebkitPresentationVideo} */ (video);
    /**
     * 案内をトーストに加えてボタンの横にも残す。iPhone の全画面の再生画面はページ全体を覆うため、
     * トーストは全画面の裏で消えてしまい読めない。全画面から戻ったときに読めるようにする。
     * @param {string} message 案内の文言
     */
    const guide = (message) => {
        showToast(message);
        let hint = button.nextElementSibling;
        if (!(hint instanceof HTMLElement) || !hint.classList.contains("pipHint")) {
            hint = document.createElement("span");
            hint.className = "pipHint muted";
            button.after(hint);
        }
        hint.textContent = message;
    };
    const openFullscreenInstead = () => {
        if (typeof safari.webkitEnterFullscreen === "function") {
            try {
                safari.webkitEnterFullscreen();
                guide("全画面の再生画面にある小窓のボタンで小窓にできます");
                return;
            } catch (e) {
                // 全画面も断られた。投げ直すと何も起きないように見えるので、下の案内に落とす
                console.warn(e);
            }
        }
        guide("このブラウザではこの動画を小窓にできません");
    };
    /** @type {() => unknown} */
    let toggle;
    if (document.pictureInPictureEnabled && !video.disablePictureInPicture) {
        video.addEventListener("enterpictureinpicture", () => render(true));
        video.addEventListener("leavepictureinpicture", () => render(false));
        toggle = () => {
            if (document.pictureInPictureElement === video) return document.exitPictureInPicture();
            // 答えを返すのは Safari だけ（ほかは undefined）。読み込みで答えが変わりうるので押した時点で聞く
            if (safari.webkitSupportsPresentationMode?.("picture-in-picture") === false) return openFullscreenInstead();
            return video.requestPictureInPicture();
        };
    } else if (typeof safari.webkitSupportsPresentationMode === "function"
            && safari.webkitSupportsPresentationMode("picture-in-picture")) {
        const inPictureInPicture = () => safari.webkitPresentationMode === "picture-in-picture";
        video.addEventListener("webkitpresentationmodechanged", () => render(inPictureInPicture()));
        toggle = () => safari.webkitSetPresentationMode(inPictureInPicture() ? "inline" : "picture-in-picture");
    } else {
        button.hidden = true;
        return;
    }
    button.addEventListener("click", async () => {
        try {
            await toggle();
        } catch (e) {
            if (typeof safari.webkitSupportsPresentationMode !== "function") {
                // 読み込み前に押した等。ブラウザが理由を返すのでそのまま見せる
                showError(errorMessage(e));
                return;
            }
            // Safari は確認を通っても断ることがある。英語の理由は見せず、調べられるよう残す
            console.warn(e);
            openFullscreenInstead();
        }
    });
    button.hidden = false;
}

/* ============================================================
   グラフ
   チャート用ライブラリは入れず、素の SVG を組み立てる。
   必要なのはドーナツと横棒だけで依存を増やす理由が無く、色を CSS カスタムプロパティで
   指定できるため style.css の配色変更にそのまま追従するのが利点
   （ライブラリを使うと既定の配色を全部上書きすることになる）。
   ============================================================ */

/**
 * グラフの一区画。
 *
 * <b>各区画は互いに重なってはならない</b>（合計が全体と一致すること）。
 * 重なる分類を混ぜると、面積の比が意味を失う。
 *
 * @typedef {object} ChartSegment
 * @property {string} label 凡例に出す名前
 * @property {number} value 値
 * @property {string} color CSS の色指定。`var(--chart-1)` のような変数参照でよい
 * @property {string} [display] 凡例に出す表示値。省略すると value をそのまま出す
 */

/** ドーナツの半径。線の太さと合わせて viewBox 140x140 に収まるよう決めている。 */
const DONUT_RADIUS = 54;

/**
 * ドーナツグラフを組み立てる。
 *
 * <p>円弧は `stroke-dasharray`（線と間隔の長さ）と `stroke-dashoffset`（描き始めの位置）で
 * 表現している。円周の長さを値の比で分け合い、前の区画の長さぶんだけ開始位置をずらしていく。
 * `path` で扇形を計算するより短く、線幅を変えるだけで太さを調整できる。
 *
 * @param {ChartSegment[]} segments 表示する区画
 * @param {string} centerValue 中央に大きく出す値
 * @param {string} centerLabel 中央の値に添える短いラベル
 * @returns {string} 差し込む HTML
 */
function donutChart(segments, centerValue, centerLabel) {
    const circumference = 2 * Math.PI * DONUT_RADIUS;
    const total = segments.reduce((sum, s) => sum + s.value, 0);

    let arcs;
    if (total === 0) {
        // 0 件のときに何も描かないと「壊れている」ように見えるため、灰色の輪を出す
        arcs = `<circle cx="70" cy="70" r="${DONUT_RADIUS}" stroke="var(--chart-empty)"/>`;
    } else {
        let consumed = 0;
        arcs = segments.filter((s) => s.value > 0).map((s) => {
            const length = (s.value / total) * circumference;
            const arc = `<circle cx="70" cy="70" r="${DONUT_RADIUS}" stroke="${s.color}"`
                + ` stroke-dasharray="${length.toFixed(2)} ${(circumference - length).toFixed(2)}"`
                + ` stroke-dashoffset="${(-consumed).toFixed(2)}"><title>`
                + `${escapeHtml(s.label)}: ${escapeHtml(s.display ?? String(s.value))}</title></circle>`;
            consumed += length;
            return arc;
        }).join("");
    }

    const legend = segments.map((s) => {
        const share = total === 0 ? "0%" : `${Math.round((s.value / total) * 100)}%`;
        return `<div><em style="background:${s.color}"></em>`
            + `<s>${escapeHtml(s.label)}</s>`
            + `<b>${escapeHtml(s.display ?? String(s.value))}</b>`
            + `<u>${share}</u></div>`;
    }).join("");

    return `<div class="donut">
        <svg viewBox="0 0 140 140" role="img" aria-label="${escapeHtml(centerLabel)} ${escapeHtml(centerValue)}">
          <g transform="rotate(-90 70 70)" fill="none" stroke-width="21">${arcs}</g>
          <text x="70" y="67" text-anchor="middle" class="donutValue">${escapeHtml(centerValue)}</text>
          <text x="70" y="83" text-anchor="middle" class="donutLabel">${escapeHtml(centerLabel)}</text>
        </svg>
        <div class="donutLegend">${legend}</div>
      </div>`;
}

/**
 * 横棒グラフを組み立てる。
 *
 * <p>項目が増えても読めるのが円グラフとの違い。円グラフは 3〜5 項目までしか判別できないため、
 * 「全体に対する割合」はドーナツ、「順位と差」はこちら、と役割を分けて併置する。
 * 棒の長さは最大値を 100% とした相対比（合計に対する割合ではない）。
 *
 * @param {ChartSegment[]} items 表示する項目。呼び出し側で降順に並べておくこと
 * @returns {string} 差し込む HTML
 */
function barChart(items) {
    if (items.length === 0) {
        return '<p class="muted">表示できるデータがありません</p>';
    }
    const max = Math.max(...items.map((i) => i.value), 1);
    return items.map((i) => {
        const width = Math.max((i.value / max) * 100, 1);
        return `<div class="bar">
            <s title="${escapeHtml(i.label)}">${escapeHtml(i.label)}</s>
            <div class="barTrack"><div class="barFill" style="width:${width.toFixed(1)}%;background:${i.color}"></div></div>
            <b>${escapeHtml(i.display ?? String(i.value))}</b>
          </div>`;
    }).join("");
}

/**
 * 内訳グラフで使う色を、項目の並び順から決める。
 *
 * <p>濃紺の濃淡だけで構成する（色相を増やすと途端に安っぽく見えるため）。
 * 用意した段階数を超えたぶんは最も薄い色を繰り返す。
 *
 * @param {number} index 0 始まりの順位
 * @returns {string} CSS の色指定
 */
function rampColor(index) {
    const RAMP_STEPS = 5;
    return `var(--chart-${Math.min(index + 1, RAMP_STEPS)})`;
}

/**
 * 折れ線グラフの 1 本分。
 *
 * @typedef {object} LineSeries
 * @property {string} label 凡例に出す名前
 * @property {string} color CSS の色指定
 * @property {Array<number|null>} values 各時刻の値（times と同じ並び）。null は計測できなかった点
 */

/**
 * 時系列の折れ線グラフを組み立てる。
 *
 * <p>ドーナツ・横棒と同じく素の SVG で描く（グラフのために外部ライブラリを足さないため）。
 * viewBox の幅はスマホ幅に合わせて小さめにしている。広い画面では拡大されるが、
 * 狭い画面で文字が読めなくなるよりよい。線は `vector-effect` で拡大しても太さを変えない。
 *
 * <p>横位置は点の番号ではなく時刻から決める。記録が抜けた時間があっても時間の縮尺が狂わないため。
 * null は 0 として描かずに線を切る。計測できなかった時間を「0%」と読み違えさせないため。
 *
 * @param {Date[]} times 各点の時刻（古い順、2 点以上）
 * @param {LineSeries[]} series 描く線
 * @param {{format: (value: number) => string, max?: number, height?: number}} options
 *        format は軸と凡例の値の書式。max を省くと値の最大から縦軸の上限を決める
 * @returns {string} 差し込む HTML
 */
function lineChart(times, series, options) {
    const width = 360;
    const height = options.height ?? 150;
    const pad = { left: 46, right: 10, top: 8, bottom: 20 };
    const plotWidth = width - pad.left - pad.right;
    const plotHeight = height - pad.top - pad.bottom;
    const measured = /** @type {number[]} */ (series.flatMap((s) => s.values).filter((v) => v !== null));
    // 上端に張り付くと線が枠と重なって読めないため、自動のときは 1 割の余白を足す
    const max = options.max ?? Math.max(...measured, 1) * 1.1;
    const start = times[0].getTime();
    const span = Math.max(times[times.length - 1].getTime() - start, 1);
    /** @param {number} i */
    const x = (i) => pad.left + ((times[i].getTime() - start) / span) * plotWidth;
    /** @param {number} v */
    const y = (v) => pad.top + plotHeight - Math.min(v / max, 1) * plotHeight;

    const grid = [0, 0.5, 1].map((ratio) => {
        const lineY = y(max * ratio).toFixed(1);
        return `<line x1="${pad.left}" x2="${width - pad.right}" y1="${lineY}" y2="${lineY}" class="lineGrid"/>`
            + `<text x="${pad.left - 6}" y="${(Number(lineY) + 3).toFixed(1)}" text-anchor="end" class="lineAxis">`
            + `${escapeHtml(options.format(max * ratio))}</text>`;
    }).join("");

    /** @param {Date} date */
    const clock = (date) => `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
    const last = times.length - 1;
    const timeLabels = [[0, "start"], [Math.floor(last / 2), "middle"], [last, "end"]].map(([i, anchor]) =>
        `<text x="${x(Number(i)).toFixed(1)}" y="${height - 4}" text-anchor="${anchor}" class="lineAxis">${clock(times[Number(i)])}</text>`
    ).join("");

    const lines = series.map((s) => {
        let path = "";
        let drawing = false;
        s.values.forEach((v, i) => {
            if (v === null) {
                drawing = false;
                return;
            }
            path += `${drawing ? "L" : "M"}${x(i).toFixed(1)} ${y(v).toFixed(1)}`;
            drawing = true;
        });
        const latest = [...s.values].reverse().find((v) => v !== null);
        return `<path d="${path}" stroke="${s.color}" vector-effect="non-scaling-stroke"><title>`
            + `${escapeHtml(s.label)}: 最新 ${escapeHtml(latest === undefined || latest === null ? "-" : options.format(latest))}</title></path>`;
    }).join("");

    const legend = series.map((s) =>
        `<span><em style="background:${s.color}"></em>${escapeHtml(s.label)}</span>`).join("");

    return `<div class="lineChart">
        <svg viewBox="0 0 ${width} ${height}" role="img" aria-label="${escapeHtml(series.map((s) => s.label).join("・"))}の推移">
          ${grid}${timeLabels}
          <g fill="none" stroke-width="2" stroke-linejoin="round">${lines}</g>
        </svg>
        <div class="lineLegend">${legend}</div>
      </div>`;
}

/* ============================================================
   認証
   ============================================================ */

/**
 * 画面上部のナビゲーションに「ログアウト」を追加する。
 *
 * 各画面のHTMLを個別に編集せずに済むよう、この共通スクリプトの読み込み時に
 * ナビゲーション（.globalnav）があれば差し込む方式にしている。ログイン画面のように
 * ナビゲーションが無い画面では何もしない。
 */
function initLogoutControl() {
    const nav = document.querySelector(".globalnav .shell");
    if (!nav) return;

    const link = document.createElement("a");
    link.href = "#";
    link.textContent = "ログアウト";
    link.className = "navLogout";
    link.addEventListener("click", async (e) => {
        e.preventDefault();
        try {
            await fetch("/api/auth/logout", { method: "POST", headers: csrfHeaders() });
        } finally {
            // ログアウト自体が失敗しても（通信断など）、利用者をログイン画面へは戻す
            window.location.href = loginPagePath() + "?logout";
        }
    });
    nav.appendChild(link);
}

initLogoutControl();

/**
 * エラー帯にメッセージを表示する。
 *
 * <p>ここだけ {@link el} を使わず {@link document.getElementById} を直接呼ぶ。
 * error 要素を置いていない画面からも呼ばれうるため、見つからない場合は例外にせず何もしない。
 *
 * @param {string} message 表示するメッセージ
 */
function showError(message) {
    const box = document.getElementById("error");
    if (!box) return;
    box.textContent = message;
    box.style.display = "block";
}

/** エラー帯を隠す。 */
function clearError() {
    const box = document.getElementById("error");
    if (!box) return;
    box.style.display = "none";
}

/** ページ端で押しても何も起きない操作を表示しないための共通処理。
 * @param {number} page
 * @param {number} total
 */
function updatePagination(page, total) {
    buttonEl("prevBtn").disabled = page <= 0;
    buttonEl("nextBtn").disabled = page + 1 >= total;
}


/* ============================================================
   通知・録画の条件セル（複数の画面で同じ形を使う）
   ============================================================ */
/**
 * 条件セルの「表示状態」の HTML を組み立てる。
 *
 * <p>一覧の描画時と編集を終えた時の2か所で同じ形が要るため、ここにまとめている
 * （別々に書くと、片方だけ直して見た目がずれる）。
 *
 * @param {string} value 現在の条件。空文字なら未設定
 * @returns {string} セルへ差し込む HTML
 */
function titleFilterButton(value) {
    const unset = value ? "" : " is-unset";
    const label = value ? escapeHtml(value) : "条件なし（すべて対象）";
    return `<button type="button" class="filterValue${unset}"`
        + ` title="${value ? escapeHtml(value) : "条件を設定していません"}"`
        + ` aria-label="通知・録画の条件を編集">${label}</button>`;
}

/**
 * 条件セルをその場編集に切り替える。
 *
 * <p>保存先は画面ごとに違う（管理者はチャンネル単位の設定、利用者は自分の購読）ため、
 * <b>保存処理を引数で受け取る</b>。ここに画面ごとの分岐を書き始めると、
 * 画面が増えるたびにこの関数が膨らむ。
 *
 * @param {HTMLTableCellElement} td 対象のセル
 * @param {string} oldValue 編集前の値
 * @param {(value: string) => Promise<void>} save 値を保存する処理
 */
function editTitleFilterCell(td, oldValue, save) {
    if (td.dataset.editing === "true") return;
    oldValue = td.dataset.value ?? oldValue;
    td.dataset.editing = "true";
    const input = document.createElement("input");
    input.value = oldValue;
    input.setAttribute("aria-label", "通知・録画の条件");
    // 何を入れる欄なのかが空のときに分からないため、例を出しておく
    input.placeholder = "例：ASMR（空欄で条件なし）";
    const saveButton = document.createElement("button");
    saveButton.type = "button";
    saveButton.className = "filterSave";
    saveButton.textContent = "保存";
    const cancel = document.createElement("button");
    cancel.type = "button";
    cancel.textContent = "取消";
    // 入力欄とボタンを1行に収める入れ物。td へ直接並べると、
    // DB管理用の td input（width:100%）に引っ張られてボタンが押し出される
    const editor = document.createElement("div");
    editor.className = "filterEdit";
    editor.append(input, saveButton, cancel);
    td.replaceChildren(editor);
    input.focus();
    input.select();
    /** @param {string} value */
    const finish = (value) => {
        td.dataset.value = value;
        td.dataset.editing = "false";
        td.innerHTML = titleFilterButton(value);
        query("button", td).focus();
    };
    saveButton.addEventListener("click", async (ev) => {
        ev.stopPropagation();
        saveButton.disabled = cancel.disabled = input.disabled = true;
        const value = input.value.trim();
        try {
            if (value !== oldValue) {
                await save(value);
                showToast(value ? `条件を「${value}」に変更しました` : "条件を解除しました");
            }
            clearError();
            finish(value);
        } catch (e) {
            showError(errorMessage(e));
            finish(oldValue);
        }
    });
    cancel.addEventListener("click", (ev) => { ev.stopPropagation(); finish(oldValue); });
    input.addEventListener("keydown", (ev) => {
        if (ev.key === "Enter") { ev.preventDefault(); saveButton.click(); }
        if (ev.key === "Escape") { ev.preventDefault(); finish(oldValue); }
    });
}

/* ============================================================
   状態の可視化と操作の結果表示
   ============================================================ */

/**
 * 状態ランプ（色付きの丸＋文字）の HTML を組み立てる。
 *
 * <p>状態を文字だけで表すと、行数の多い一覧では読まないと分からない。
 * 色と形を先に目に入れることで、読む前に当たりが付くようにしている。
 * <b>色だけで区別させない</b>（配信中と録画中は同じ赤だが、録画中は二重丸にしてある）。
 * 色覚特性によっては赤の濃淡が同じに見えるため。
 *
 * @param {"live"|"recording"|"failed"|"unknown"|"idle"} state 状態
 * @param {string} label 表示する文字
 * @param {string|null} title 補足説明（ホバーで出す）。不要なら null
 * @returns {string} セルへ差し込む HTML
 */
function statusLamp(state, label, title = null) {
    const titleAttr = title ? ` title="${escapeHtml(title)}"` : "";
    return `<span class="statusLamp is-${state}"${titleAttr}>`
        + `<span class="lamp" aria-hidden="true"></span>`
        + `<span class="lampText">${escapeHtml(label)}</span></span>`;
}

/**
 * トーストを積む読み上げの領域（{@link showToast} の置き場所）を返す。無ければ作る。
 *
 * <p>読み上げの領域は、中身を入れる前から DOM にある必要がある（作ると同時に中身を入れると
 * 1 件目が読まれない。WCAG 4.1.3）。そのため {@link initStudioShell} がページの読み込み時に
 * 一度呼んで空の領域を置き、{@link showToast} はそれを使い回す。
 *
 * @returns {HTMLElement} `.toastStack` の要素
 */
function ensureToastStack() {
    /** @type {HTMLElement | null} */
    let stack = document.querySelector(".toastStack");
    if (!stack) {
        stack = document.createElement("div");
        stack.className = "toastStack";
        // 操作の結果は「今起きたこと」なので polite で十分
        stack.setAttribute("role", "status");
        stack.setAttribute("aria-live", "polite");
        document.body.appendChild(stack);
    }
    return stack;
}

/**
 * 操作の結果を画面の隅に短く出す。
 *
 * <p>保存・削除が効いたかどうかを、一覧の再読み込みを待たずに伝えるため。
 * <b>エラーはここでは扱わない</b>——エラーはエラー帯（{@link showError}）が担当し、
 * 消えてしまっては困る情報を自動で消さないようにしている。
 *
 * <p>読み終える前に消えないよう、文の長さに応じて最低 5 秒は出しておく。
 * マウスを乗せている間・フォーカスがある間は読んでいるので消さず、離れてから消す（WCAG 2.2.1）。
 *
 * @param {string} message 表示する文言
 * @param {"info"|"danger"} kind 種別。danger は削除など取り消しにくい操作の結果に使う
 */
function showToast(message, kind = "info") {
    const stack = ensureToastStack();

    const item = document.createElement("div");
    item.className = kind === "danger" ? "toast is-danger" : "toast";
    item.textContent = message;
    stack.appendChild(item);

    let hovered = false;
    let focused = false;
    let expired = false;
    const removeIfIdle = () => { if (expired && !hovered && !focused) item.remove(); };
    item.addEventListener("mouseenter", () => { hovered = true; });
    item.addEventListener("mouseleave", () => { hovered = false; removeIfIdle(); });
    item.addEventListener("focusin", () => { focused = true; });
    item.addEventListener("focusout", () => { focused = false; removeIfIdle(); });
    window.setTimeout(() => { expired = true; removeIfIdle(); }, Math.max(5000, message.length * 120));
}

/**
 * 「0 件」を伝える表示の HTML を組み立てる。
 *
 * <p>空欄のまま何も出さないと、0 件なのか読み込みに失敗したのか区別が付かない。
 *
 * @param {string} title 主文（例：「まだ録画がありません」）
 * @param {string|null} detail 次に何をすればよいかの補足。不要なら null
 * @returns {string} 差し込む HTML
 */
function emptyState(title, detail = null) {
    const body = detail ? `${escapeHtml(detail)}` : "";
    return `<div class="emptyState"><strong>${escapeHtml(title)}</strong>${body}</div>`;
}

/**
 * 読み込み中であることを示す。
 *
 * <p>中身を消さずに薄くする。消してしまうと画面が一瞬空になり、
 * 「0 件になった」と誤解させるため。
 *
 * @param {Element|null} target 対象の領域
 * @param {boolean} busy 読み込み中なら true
 */
function setBusy(target, busy) {
    if (!target) return;
    target.classList.toggle("isBusy", busy);
}

/**
 * ヘッダに「今いくつ配信中か」を出す。
 *
 * <p>配信中かどうかはこの画面を見に来る最大の理由なので、ダッシュボードを開かなくても
 * どの画面からでも目に入るようにしている。<b>0 件のときは出さない</b>——
 * 常に表示していると 0 という数字が背景に溶けて、増えたことに気付けなくなるため。
 *
 * <p>ログイン画面のようにナビの無い画面では何もしない（未認証で API を叩かないため）。
 */
async function initLiveIndicator() {
    // 管理者のナビにだけダッシュボードへのリンクがある。これが無い画面（ログイン・
    // 利用者向け）から /api/dashboard を叩いても 403 が返るだけなので、行かない
    // （握り潰していたが、開くたびに無駄な要求とコンソールエラーが出ていた）。
    const bar = document.querySelector(".masthead .shell");
    if (!bar || !document.querySelector('.globalnav a[href="/index.html"]')) return;

    try {
        const data = await apiGet("/api/dashboard");
        setLiveIndicator(Number(data?.liveNowCount ?? 0));
    } catch {
        // 取得できなくても画面の本体は使えるので、ここでは何も出さない
        // （エラー帯を出すと、本来の目的と関係ない失敗で画面が騒がしくなる）。
        // 一般利用者は /api/dashboard を見られないため、ここは通常経路としても通る
    }
}

/**
 * ヘッダの配信中カウンタを設定する。
 *
 * <p>数を持っている画面（ダッシュボード）から直接呼べるようにしてある。
 *
 * @param {number} liveNow 配信中の数。0 なら表示しない
 */
function setLiveIndicator(liveNow) {
    const bar = document.querySelector(".masthead .shell");
    if (!bar) return;

    const existing = bar.querySelector(".liveCount");
    if (existing) existing.remove();
    if (!liveNow) return;

    const link = document.createElement("a");
    link.className = "liveCount";
    // 一覧そのものが配信中を示すので、リンク先は今の画面でよい
    link.href = "/videos.html?liveOnly=true";
    link.title = "配信中のチャンネルがあります";
    link.innerHTML = `<span class="lamp" aria-hidden="true"></span>`
        + `<span>配信中 ${liveNow}</span>`;
    bar.appendChild(link);
}

initLiveIndicator();

/**
 * 保存されたURLでも危険なスキームはリンクにしない。
 * @param {string} label
 * @param {string|null} url
 * @returns {string}
 */
function externalLink(label, url) {
    if (!url || !/^https?:\/\//i.test(url)) return escapeHtml(label);
    return `<a href="${escapeHtml(url)}" target="_blank" rel="noopener noreferrer">${escapeHtml(label)}</a>`;
}


/**
 * リンクをクリップボードへ写す。
 *
 * <p>クリップボード API は安全な文脈（HTTPS か localhost）でしか使えず、
 * Tailscale 経由の http では失敗する。そのため<b>失敗しても入力欄の選択だけは残し</b>、
 * 手動でコピーできるようにしている。
 *
 * @param {HTMLInputElement} field リンクを表示している入力欄
 */
async function copyLink(field) {
    field.select();
    try {
        await navigator.clipboard.writeText(field.value);
        showToast("リンクをコピーしました");
    } catch {
        showToast("コピーできなかったので選択しました。手動でコピーしてください");
    }
}

/**
 * リンクの表示欄（読み取り専用の入力欄＋コピーボタン）を作る。
 *
 * <p>ただの文字列として置くと、長い URL が表のセルを押し広げてしまう。
 * 入力欄にすると幅を決められ、クリックで全選択もできる。
 *
 * @param {string} url 表示するリンク
 * @param {string} label 入力欄の読み上げ名（招待リンク・再設定用のリンクなど）
 * @returns {HTMLElement} 差し込む要素
 */
function linkField(url, label) {
    const wrap = document.createElement("div");
    wrap.className = "filterEdit";

    const field = document.createElement("input");
    field.type = "text";
    field.readOnly = true;
    field.value = url;
    field.setAttribute("aria-label", label);
    field.addEventListener("click", () => field.select());

    const copy = document.createElement("button");
    copy.type = "button";
    copy.className = "filterSave";
    copy.textContent = "コピー";
    copy.addEventListener("click", () => copyLink(field));

    wrap.append(field, copy);
    return wrap;
}

/** @type {Array<[string, string]>} 管理者画面の「ワークスペース」のメニュー。よく使う順に並べる。 */
const adminNavigation = [
    ["/index.html", "ダッシュボード"], ["/videos.html", "動画一覧"],
    ["/channels.html", "登録済みチャンネル一覧"], ["/recordings.html", "アーカイブ一覧"],
    ["/notifications.html", "通知履歴"], ["/tables.html", "DB管理"]
];
/** @type {Array<[string, Array<[string, string]>]>} ワークスペースの下に見出し付きで並べる管理者メニュー。役割ごとに探しやすくするため。 */
const adminSections = [
    ["アカウント管理", [["/users.html", "利用者管理"], ["/invitations.html", "招待"]]],
    ["ログ", [["/logs.html", "ログ"], ["/audit.html", "監査ログ"]]]
];
/**
 * 一般利用者には管理リンクを載せない。使うのは動画一覧（videos.js）で閲覧者を判定できなかったときだけで、
 * 利用者の画面（/my 配下）のメニューは my.html に書いてある。
 * @type {Array<[string, string]>}
 */
const userNavigation = [
    ["/my/videos", "動画・配信"], ["/my/channels", "マイチャンネル"],
    ["/my/archive", "アーカイブ"]
];

/** @type {Record<string, string>} 画面ごとのメニューアイコン（SVG の path）。 */
const studioPages = {
    "videos.html": "M4 5h16v14H4z M10 9l5 3-5 3z",
    "index.html": "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
    "channels.html": "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M20 8v6 M17 11h6",
    "recordings.html": "M4 5h16v14H4z M10 9l5 3-5 3z",
    "notifications.html": "M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21h4",
    "logs.html": "M5 3h14v18H5z M8 7h8 M8 11h8 M8 15h5",
    "audit.html": "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z M9 12l2 2 4-4",
    "tables.html": "M3 4h18v16H3z M3 9h18 M9 9v11",
    "users.html": "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M20 8v6 M17 11h6",
    "invitations.html": "M3 5h18v14H3z M3 5l9 7 9-7",
    "player.html": "M4 5h16v14H4z M10 9l5 3-5 3z",
    // 利用者の 1 枚のページ（my.html）のトップ（/my）・動画・配信（/my/videos）・アーカイブ（/my/archive）・
    // マイチャンネル（/my/channels）・通知の設定（/my/settings/notifications。管理者の通知履歴と同じベル）
    "my": "M3 11l9-8 9 8 M5 9v12h14V9 M10 21v-6h4v6",
    "videos": "M4 5h16v14H4z M10 9l5 3-5 3z",
    "archive": "M4 5h16v14H4z M10 9l5 3-5 3z",
    "channels": "M4 4h16v16H4z M8 9h8 M8 14h5",
    "notifications": "M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21h4",
    // 動画ダウンロード（/my/download）。下向きの矢印と受け皿
    "download": "M12 4v11 M7 10l5 5 5-5 M5 20h14",
    // 検索（/my/search）。虫めがね
    "search": "M4 11a7 7 0 1 0 14 0a7 7 0 1 0-14 0 M16 16l5 5",
    // 新人発掘（/my/discover）。きらめき（新しく見つかったもの）
    "discover": "M12 3l2 6 6 2-6 2-2 6-2-6-6-2 6-2z M19 3v4 M17 5h4",
};

/**
 * 動画画面は閲覧者判定後に呼び直すため、ログアウト操作を付け直さず同じ要素を残す。
 * @param {boolean} admin 管理者ならtrue
 */
function renderNavigationForViewer(admin) {
    viewerIsAdmin = admin;
    const nav = document.querySelector(".globalnav .shell");
    if (!nav) return;
    const logout = nav.querySelector(".navLogout");
    const page = location.pathname === "/player.html" ? "/recordings.html" : location.pathname;
    /** @param {Array<[string, string]>} entries */
    const toLinks = (entries) => entries.map(([href, label]) => {
        const link = document.createElement("a");
        link.href = href;
        link.textContent = label;
        if (href === page) { link.classList.add("active"); link.setAttribute("aria-current", "page"); }
        return link;
    });
    /** @type {HTMLElement[]} */
    const items = toLinks(admin ? adminNavigation : userNavigation);
    if (admin) {
        // 管理者のメニューの見出し。CSS の疑似要素にすると利用者の /my のメニューにも出てしまうため、
        // 管理者のときだけ要素で入れる（要素なら読み上げやコピーでも本文として扱われる）
        const workspaceLabel = document.createElement("div");
        workspaceLabel.className = "navWorkspaceLabel";
        workspaceLabel.textContent = "ワークスペース";
        items.unshift(workspaceLabel);
        for (const [title, entries] of adminSections) {
            const groupLabel = document.createElement("div");
            groupLabel.className = "navGroupLabel";
            groupLabel.textContent = title;
            items.push(groupLabel, ...toLinks(entries));
        }
    }
    nav.replaceChildren(...items, ...(logout ? [logout] : []));
    decorateStudioNavigation();
    document.querySelector(".globalnav")?.classList.add("navReady");
}

/** 追加し直したリンクにも、同じアイコンとスマホの現在地表示を適用する。 */
function decorateStudioNavigation() {
    document.querySelectorAll(".globalnav a").forEach((link) => {
        const path = (link.getAttribute("href") || "").split("/").pop() || "";
        const icon = studioPages[path] || "M9 4H4v16h5 M13 8l4 4-4 4 M8 12h13";
        if (!link.querySelector(".navIcon")) link.insertAdjacentHTML("afterbegin", `<svg class="navIcon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" stroke-linecap="round" aria-hidden="true"><path d="${icon}"/></svg>`);
        if (link.classList.contains("active")) link.setAttribute("aria-current", "page");
    });
    const navigation = document.querySelector(".globalnav");
    const activeLink = navigation?.querySelector("a.active");
    if (navigation && activeLink instanceof HTMLElement && matchMedia("(max-width: 760px)").matches) {
        // 現在地を左端へ寄せると、手前のタブが必ず画面外へ押し出される。
        // スマホではナビが横スクロールできること自体が分かりにくいため、
        // 押すたびに選べる範囲が狭まっていくように見えてしまう（実際に指摘を受けた）。
        // scrollIntoView は「見えていなければ最小限だけ動かす」ので、
        // 既に見えている場合は何もしない
        activeLink.scrollIntoView({ inline: "nearest", block: "nearest" });
    }
}

/** 共通の補助要素を一度だけ置く。 */
function initStudioShell() {
    const current = location.pathname.split("/").pop();
    if (/^\/my(\/|$)/.test(location.pathname)) {
        // 利用者の 1 枚のページ（/my 配下、my.html）は HTML に書いたメニューをそのまま使う。
        // 下の分岐は管理者のメニューを描くため、通すと /my/archive なども管理者のメニューに描き直されてしまう
        decorateStudioNavigation();
        document.querySelector(".globalnav")?.classList.add("navReady");
    } else if (current !== "videos.html") {
        // 動画一覧は videos.js が閲覧者を判定してから 1 回だけ描く。
        // 仮に利用者用を描くと、管理者には一瞬別のメニューが見えてから組み替わるため。
        // ほかは管理者の画面か、メニューの無いログイン画面だけ（利用者の旧画面は #179 で消した）
        renderNavigationForViewer(true);
    }
    const main = document.querySelector("main");
    if (main) {
        // my.html は main#view を中身の描き替え先にしているため、付いている ID は変えない
        if (!main.id) main.id = "mainContent";
        main.setAttribute("tabindex", "-1");
        const skip = document.createElement("a");
        skip.className = "skipLink";
        skip.href = `#${main.id}`;
        skip.textContent = "本文へ移動";
        document.body.prepend(skip);
    }
    // プレースホルダーが消えた後も、支援技術から入力の目的を確認できるようにする。
    document.querySelectorAll("input[placeholder], select").forEach((control) => {
        if (control.hasAttribute("aria-label") || control.closest("label")) return;
        const id = control.id;
        if (id && document.querySelector(`label[for="${id}"]`)) return;
        const label = control.getAttribute("placeholder") || control.querySelector("option")?.textContent;
        if (label) control.setAttribute("aria-label", label);
    });
    initHints();
    // トーストの読み上げ領域は、最初のトーストより前に置いておく（理由は ensureToastStack）
    ensureToastStack();
}

/**
 * 管理画面の補足の ⓘ（`<span class="hint" title="...">`）を、キーボード・タップ・読み上げでも読めるようにする。
 *
 * <p>ⓘ の中身は title 属性にしか無く、マウスを乗せない限り読めない（WCAG 1.4.13）。
 * HTML の 27 行を書き換えずに済むよう、ここでフォーカスと読み上げ名を付け、
 * フォーカス中（スマホではタップ）は style.css の `.hint:focus::after` で本文を吹き出しに出す。
 * title は消さない（マウスで乗せたときの吹き出しに使う）。
 *
 * <p>吹き出しは position: fixed で出し、位置はここで CSS 変数に渡す。absolute だと、
 * 表の見出しの ⓘ は横スクロールの枠（`.table-scroll`）に切り取られるため。
 * Esc で閉じられるようにし、閉じた後はフォーカスが外れるまで出さない（WCAG 1.4.13 の「消せる」）。
 */
function initHints() {
    /** @param {Element | null} hint */
    const place = (hint) => {
        if (!(hint instanceof HTMLElement) || !hint.matches(".hint[title]")) return;
        const rect = hint.getBoundingClientRect();
        hint.style.setProperty("--hint-left", `${rect.left}px`);
        hint.style.setProperty("--hint-top", `${rect.bottom + 6}px`);
    };
    document.querySelectorAll(".hint[title]").forEach((hint) => {
        if (!(hint instanceof HTMLElement)) return;
        hint.tabIndex = 0;
        hint.setAttribute("role", "note");
        // 見出しの中の ⓘ は見出しの読み上げ名に「補足: …」が入るが、補足を読み上げ環境に届けるのが目的なので許す
        hint.setAttribute("aria-label", `補足: ${hint.title}`);
        hint.addEventListener("focus", () => place(hint));
        hint.addEventListener("blur", () => hint.classList.remove("hintDismissed"));
        hint.addEventListener("keydown", (event) => {
            if (event.key === "Escape") hint.classList.add("hintDismissed");
        });
    });
    // 吹き出しは画面に固定なので、スクロールしたら ⓘ の位置に付け直す
    document.addEventListener("scroll", () => place(document.activeElement), { capture: true, passive: true });
}

initStudioShell();

/** サーバーが配布したトークンを返し、既存のCSRF保護に対応する。
 * @returns {Record<string, string>}
 */
function csrfHeaders() {
    const cookie = document.cookie.split("; ").find((value) => value.startsWith("XSRF-TOKEN="));
    return cookie ? { "X-XSRF-TOKEN": decodeURIComponent(cookie.slice("XSRF-TOKEN=".length)) } : {};
}

/** 許可したサービスのURLだけを公式プレーヤーに変換する。
 * @param {string} watchUrl
 * @param {string} [hostname]
 * @returns {string|null}
 */
function embeddedVideoUrl(watchUrl, hostname = location.hostname) {
    try {
        const url = new URL(watchUrl);
        if (url.protocol !== "https:") return null;
        const youtube = ["www.youtube.com", "youtube.com", "youtu.be"].includes(url.hostname);
        if (youtube) {
            const id = url.hostname === "youtu.be" ? url.pathname.slice(1)
                : url.pathname === "/watch" ? url.searchParams.get("v") : null;
            return id && /^[A-Za-z0-9_-]{11}$/.test(id)
                ? `https://www.youtube-nocookie.com/embed/${id}?autoplay=1&playsinline=1&rel=0` : null;
        }
        if (!["www.twitch.tv", "twitch.tv"].includes(url.hostname)) return null;
        const vod = url.pathname.match(/^\/videos\/(\d+)\/?$/);
        const channel = url.pathname.match(/^\/([A-Za-z0-9_]+)\/?$/);
        if (!vod && !channel) return null;
        const params = new URLSearchParams({parent: hostname, autoplay: "true"});
        if (vod) params.set("video", "v" + vod[1]);
        else if (channel) params.set("channel", channel[1]);
        return "https://player.twitch.tv/?" + params;
    } catch { return null; }
}

/** 一覧を離れず一つの動画だけ再生し、閉じた時に音声も確実に止める。
 * @param {any} video
 */
function openOnlineVideo(video) {
    const source = embeddedVideoUrl(video.watchUrl);
    if (!source || !video.playable) { showError("この配信のアーカイブはまだ取得できていません。"); return; }
    document.querySelector("#onlinePlayerDialog")?.remove();
    const dialog = document.createElement("dialog");
    dialog.id = "onlinePlayerDialog";
    dialog.className = "onlinePlayerDialog";
    dialog.setAttribute("aria-labelledby", "onlinePlayerTitle");
    dialog.innerHTML = `<div class="onlinePlayerHead"><h2 id="onlinePlayerTitle">${escapeHtml(video.title)}</h2><button type="button" class="closeOnlinePlayer">閉じる</button></div>
        <div class="onlinePlayerFrame"></div>
        <p class="muted">${escapeHtml(video.channelName)} · ${escapeHtml(video.platformLabel)}</p>
        <p class="muted">投稿者が埋め込みを許可していない動画、非公開・削除済みの動画は再生できません。 <a href="${escapeHtml(video.watchUrl)}" target="_blank" rel="noopener noreferrer">配信元で開く ↗</a></p>`;
    const frame = document.createElement("iframe");
    frame.src = source;
    frame.title = video.title;
    frame.allow = "autoplay; encrypted-media; fullscreen; picture-in-picture";
    frame.allowFullscreen = true;
    // サイト全体のsame-origin方針は維持し、埋め込みだけ必要なRefererを送る。
    frame.referrerPolicy = "strict-origin-when-cross-origin";
    query(".onlinePlayerFrame", dialog).append(frame);
    query(".closeOnlinePlayer", dialog).addEventListener("click", () => dialog.close());
    dialog.addEventListener("close", () => dialog.remove());
    dialog.addEventListener("click", (event) => { if (event.target === dialog) dialog.close(); });
    document.body.append(dialog);
    dialog.showModal();
    query(".closeOnlinePlayer", dialog).focus();
}

/** タイトルや外部応答をHTMLとして解釈させず、共通の再生操作を提供する。
 * @param {any} video
 * @returns {HTMLElement}
 */
function buildOnlineVideoCard(video) {
    const card = document.createElement("article");
    card.className = "videoCard onlineVideoCard";
    const upcoming = video.contentKind === "UPCOMING";
    const state = upcoming ? "配信予定" : video.state === "LIVE" ? "配信中" : video.state === "UNKNOWN" ? "配信状態を確認中" : video.playable ? "動画・アーカイブ" : "配信終了・アーカイブ未取得";
    // 待機所の公開日時は枠を作った時刻で、視聴者が知りたいのは開始予定のほうなので差し替える。
    const start = upcoming && video.scheduledStartTime ? new Date(video.scheduledStartTime) : null;
    const when = !upcoming ? formatInstant(video.publishedAt) : !start || Number.isNaN(start.getTime()) ? "開始時刻不明"
        : `${start.getMonth() + 1}/${start.getDate()}(${"日月火水木金土"[start.getDay()]}) ${String(start.getHours()).padStart(2, "0")}:${String(start.getMinutes()).padStart(2, "0")} 開始予定`;
    card.innerHTML = `<button type="button" class="thumbLink onlinePlayButton" aria-label="${escapeHtml(video.title)}を再生" ${video.playable ? "" : "disabled"}>
        <img class="thumb" ${video.thumbnailRetryExhausted ? "hidden" : `src="${escapeHtml(video.thumbnailUrl)}"`} alt="" loading="lazy">
        <span class="thumbPlaceholder" ${video.thumbnailRetryExhausted ? "" : "hidden"}>${video.thumbnailRetryExhausted ? "サムネイル取得失敗" : "サムネイル取得待ち"}</span>
        <span class="onlinePlayMark" aria-hidden="true">▶</span>
        </button><div class="cardBody"><h3 class="cardTitle"><button type="button" class="onlineTitle" ${video.playable ? "" : "disabled"}>${escapeHtml(video.title)}</button></h3>
        <div class="muted">${escapeHtml(video.channelName)} · ${escapeHtml(video.platformLabel)}</div>
        <div class="muted">${escapeHtml(state)}</div><div class="muted">${escapeHtml(when)}</div></div>`;
    const thumbnail = query("img", card);
    if (!video.thumbnailRetryExhausted) {
        thumbnail.addEventListener("error", () => { thumbnail.hidden = true; query(".thumbPlaceholder", card).hidden = false; });
    }
    card.querySelectorAll("button").forEach(button => button.addEventListener("click", () => openOnlineVideo(video)));
    return card;
}

/** 配信中のカードも投稿動画と同じ再生経路へまとめる。
 * @param {HTMLElement} target
 * @param {any} page
 * @param {string} [empty] 0 件のときに出す HTML。省くと管理画面のダッシュボードの文言
 */
function renderLiveVideoCards(target, page,
        empty = emptyState("配信中の動画はありません", "起動直後・判定失敗時は、次の正常な確認を待って表示します。")) {
    target.replaceChildren(...page.content.map(buildOnlineVideoCard));
    if (!page.content.length) target.innerHTML = empty;
}

/**
 * 開始予定を日付・曜日・時間の列に分ける。列ごとに並べ替えや目視での比較をしやすくするため。
 * @param {string|null} iso 開始予定時刻
 * @returns {{date: string, weekday: string, time: string}} 表示用の文字列。不明なら全て "-"
 */
function splitScheduledStart(iso) {
    if (!iso) return { date: "-", weekday: "-", time: "-" };
    const start = new Date(iso);
    return {
        date: `${start.getMonth() + 1}/${start.getDate()}`,
        weekday: "日月火水木金土"[start.getDay()],
        time: `${String(start.getHours()).padStart(2, "0")}:${String(start.getMinutes()).padStart(2, "0")}`,
    };
}

/**
 * チャンネル名の左に並べる丸いアイコンの HTML（.channelWithIcon の中に置く）。配信予定の表と利用者のマイチャンネルで
 * 同じ出し方にするため、ここに置いている。隣にチャンネル名があるため alt は空にし、読み上げで名前が 2 回読まれないようにする。
 *
 * @param {string|null|undefined} url アイコンの URL。まだ読み取れていなければ null
 * @returns {string} 差し込む HTML。URL が無ければ空文字
 */
function channelIcon(url) {
    return url
        ? `<img class="channelIcon" src="${escapeHtml(url)}" alt="" width="24" height="24" loading="lazy" referrerpolicy="no-referrer">`
        : "";
}

/**
 * 配信予定を開始時刻の近さで読み取れる一覧にする。
 *
 * <p>配信中の一覧と分けることで、待機所を配信開始と誤解せず、利用者が次の予定を把握できる。
 * 管理画面のダッシュボードと利用者のトップ（my-app.js）で同じ表を出すため、ここに置いている。
 *
 * @param {Array<{channelName: string, title: string|null, scheduledStartTime: string|null, watchUrl: string, genre?: string|null, channelIconUrl?: string|null, channelUrl: string|null}>} streams 開始予定の早い順で返された配信予定
 * @param {HTMLElement} [box] 描く先。省くとダッシュボードの欄
 * @param {string} [empty] 0 件のときに出す HTML。省くとダッシュボードの文言
 */
function renderUpcomingStreams(streams, box = el("upcomingStreams"),
        empty = emptyState("配信予定はありません", "監視中のチャンネルが YouTube で待機所を作ると、ここに開始予定の早い順で並びます。")) {
    if (!streams || streams.length === 0) {
        box.innerHTML = empty;
        return;
    }
    const rows = streams.map(s => {
        const start = splitScheduledStart(s.scheduledStartTime);
        return `
        <tr>
            <td>${escapeHtml(start.date)}</td>
            <td>${escapeHtml(start.weekday)}</td>
            <td>${escapeHtml(start.time)}</td>
            <td><span class="channelWithIcon">${channelIcon(s.channelIconUrl)}${externalLink(s.channelName, s.channelUrl)}</span></td>
            <td>${escapeHtml(s.genre || "未設定")}</td>
            <td>${externalLink(s.title ?? "（タイトル不明）", s.watchUrl)}</td>
        </tr>`;
    }).join("");
    box.innerHTML = `
        <div class="table-scroll">
            <table id="upcomingTable">
                <thead><tr><th>日付</th><th>曜日</th><th>時間</th><th>チャンネル名</th><th>ジャンル</th><th>タイトル</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
        </div>`;
}

/** 非表示中の定期通信を省き、戻ってきたときだけ最新の保存済み状態を読む。
 * @param {() => void} refresh
 * @returns {() => void} 止める関数。1 枚のページ（my.html）の画面はページを読み込み直さずに移るため、
 *   画面を離れるときに呼ばないと、離れた画面の読み直しが続く
 */
function startVisibleRefresh(refresh) {
    const refreshIfVisible = () => { if (document.visibilityState === "visible") refresh(); };
    const timer = window.setInterval(refreshIfVisible, 60_000);
    document.addEventListener("visibilitychange", refreshIfVisible);
    return () => {
        window.clearInterval(timer);
        document.removeEventListener("visibilitychange", refreshIfVisible);
    };
}

/** 失敗時も最後に表示できた時刻を残し、画面の再試行ボタンを案内する。
 * @param {HTMLElement} target
 * @param {Date|null} lastUpdatedAt
 * @param {boolean} failed
 */
function renderRefreshStatus(target, lastUpdatedAt, failed) {
    const lastUpdated = lastUpdatedAt ? `最終表示更新: ${formatInstant(lastUpdatedAt.toISOString())}` : "表示を取得できていません";
    target.textContent = failed ? `${lastUpdated}。更新に失敗しました。表示を更新して再試行してください。` : lastUpdated;
}

/** UTCの公開・取得時刻を、利用者のブラウザのタイムゾーンで表示する。
 * @param {string|null} value
 * @returns {string}
 */
function formatInstant(value) {
    if (!value) return "-";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? "-" : date.toLocaleString("ja-JP", {
        year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false,
    });
}

/* ============================================================
   動画・配信（管理者の動画一覧と利用者の /my/videos）
   ============================================================ */

/**
 * 段ごとにページ送りを独立させるため、ページ・総ページ数・リクエスト番号を段ごとに持つ。
 * @typedef {{name: string, page: number, totalPages: number, request: number, empty: string}} VideoSection
 */

/**
 * {@link bindOnlineVideoSections} の戻り値。
 *
 * @typedef {object} OnlineVideoSections
 * @property {() => Promise<void>} start 開いたときに呼ぶ。チャンネルの選択肢を読んでから URL の条件を戻し、3 段を読む
 *           （先に戻すと、URL のチャンネルが選択肢に無い値として捨てられる）
 * @property {() => void} restore URL の条件を入力欄へ戻す。「戻る」「進む」を自分で拾う画面が呼ぶ
 * @property {(resetPage: boolean, showAlert?: boolean) => Promise<void>} loadAll 3 段を読み直す。resetPage は各段を
 *           先頭に戻すか（絞り込みを変えたとき。自動更新では今のページを保つ）、showAlert は失敗をエラー帯に出すか
 */

/**
 * 動画・配信の画面の、絞り込み・3 段（配信中・配信予定／配信済み／投稿済み）と段ごとのページ送り・取得状況の案内・
 * 表示の更新ボタンを結びつける。
 *
 * <p>管理者の動画一覧（videos.html）と利用者の /my/videos（my-app.js）で同じものを出すため、片方だけ直して
 * 動きがずれないよう 1 か所にまとめた。要素は videos.html と同じ ID で探す（利用者の画面も同じ ID で描く）。
 *
 * <p>「戻る」「進む」（popstate）と 1 分ごとの自動更新はここでは付けない。利用者の画面ではルーターが「戻る」「進む」を
 * 拾って画面ごと描き直し、自動更新は画面を離れるときに止める必要があるため、どちらも画面ごとに付ける。
 *
 * <p>利用者の画面は、読み込みを待つ間に別の画面へ移れる。待った後は、描いた要素がまだページにあるときだけ
 * 結果を反映し、エラー帯と URL にも触らない。どちらも移った先の画面のもので、URL を整えると、移った先の条件
 * （アーカイブの keyword・channelId・page）を消してしまう。
 *
 * @returns {OnlineVideoSections} 開いたとき・「戻る」「進む」・自動更新で呼ぶ操作
 */
function bindOnlineVideoSections() {
    /** @type {VideoSection[]} */
    const sections = [
        {name: "now", page: 0, totalPages: 0, request: 0, empty: "配信中・配信予定の動画はありません"},
        {name: "streams", page: 0, totalPages: 0, request: 0, empty: "配信済みの動画はありません"},
        {name: "uploads", page: 0, totalPages: 0, request: 0, empty: "投稿済みの動画はありません"},
    ];
    /** @type {Date|null} */
    let lastUpdatedAt = null;
    const form = formEl("videoFilterForm");
    const keyword = inputEl("videoKeyword");
    const channel = selectEl("videoChannel");

    // 段ごとのページは URL に残さない。3 段分を持たせると戻る操作の単位が分かりにくくなるため、絞り込み条件だけを残す。
    function syncUrl(replace = false) {
        const url = new URL(location.href);
        for (const key of ["keyword", "channelId", "liveOnly", "page"]) url.searchParams.delete(key);
        if (keyword.value.trim()) url.searchParams.set("keyword", keyword.value.trim());
        if (channel.value) url.searchParams.set("channelId", channel.value);
        if (url.href !== location.href) history[replace ? "replaceState" : "pushState"](null, "", url);
    }

    function restore() {
        const params = new URLSearchParams(location.search);
        keyword.value = (params.get("keyword") || "").trim().slice(0, 200);
        const channelId = params.get("channelId") || "";
        channel.value = Array.from(channel.options).some(option => option.value === channelId) ? channelId : "";
        syncUrl(true);
    }

    /** 自動更新では通知帯の読み上げを繰り返さず、利用者が操作した失敗だけ明示する。
     * @param {VideoSection} section
     * @param {boolean} showAlert
     * @returns {Promise<boolean|undefined>} 新しい読み込みに追い越された・画面を離れた後に届いたときは undefined
     */
    async function loadSection(section, showAlert = true) {
        const request = ++section.request;
        const grid = el(section.name + "Grid");
        setBusy(grid, true);
        const params = new URLSearchParams({section: section.name, page: String(section.page), size: "12",
            keyword: keyword.value.trim()});
        if (channel.value) params.set("channelId", channel.value);
        try {
            const data = await apiGet("/api/videos?" + params);
            if (request !== section.request || !grid.isConnected) return;
            if (section.page > 0 && section.page >= data.totalPages) {
                section.page = Math.max(0, data.totalPages - 1);
                return loadSection(section, showAlert);
            }
            section.totalPages = data.totalPages;
            grid.replaceChildren(...data.content.map(buildOnlineVideoCard));
            if (!data.content.length) grid.innerHTML = emptyState(section.empty, "新着動画の取得後に表示されます。絞り込み条件も確認してください。");
            el(section.name + "Summary").textContent = `${data.totalElements}件`;
            el(section.name + "Page").textContent = data.totalPages ? `${data.number + 1} / ${data.totalPages}` : "0 / 0";
            buttonEl(section.name + "Prev").disabled = data.first || data.empty;
            buttonEl(section.name + "Next").disabled = data.last || data.empty;
            return true;
        } catch (error) {
            if (!grid.isConnected) return;
            if (request === section.request && showAlert) showError(errorMessage(error));
            return false;
        } finally { if (request === section.request) setBusy(grid, false); }
    }

    /** 3 段をまとめて読み直し、表示更新の時刻は全段が揃って成功したときだけ進める。
     * @param {boolean} resetPage
     * @param {boolean} showAlert
     */
    async function loadAll(resetPage, showAlert = true) {
        if (resetPage) for (const section of sections) section.page = 0;
        if (showAlert) clearError();
        const results = await Promise.all(sections.map(section => loadSection(section, showAlert)));
        // 新しい読み直しに追い越された段は undefined を返す。その回の結果で表示時刻を決めない。
        if (results.includes(undefined)) return;
        const failed = results.includes(false);
        if (!failed) lastUpdatedAt = new Date();
        renderRefreshStatus(el("videoRefreshStatus"), lastUpdatedAt, failed);
    }

    async function loadChannels() {
        const notice = el("collectionNotice");
        try {
            const channels = await apiGet("/api/videos/channels");
            const previous = channel.value;
            channel.replaceChildren(new Option("すべて", ""));
            for (const item of channels) channel.add(new Option(item.name, String(item.id)));
            channel.value = previous;
            const failed = channels.filter(/** @param {any} c */ c => c.error);
            const waiting = channels.filter(/** @param {any} c */ c => !c.checkedAt);
            notice.textContent = !channels.length ? "チャンネルを登録・購読すると新着動画を取得します。" : failed.length
                ? `新着動画を取得できないチャンネル：${failed.map(/** @param {any} c */ c => c.name).join("、")}。自動再試行します。`
                : waiting.length ? "新着動画の初回取得を待っています。配信中の動画は監視で確認できたものから表示します。"
                : `前回の新着動画取得：${formatInstant(channels.map(/** @param {any} c */ c => c.checkedAt).sort()[0])}。約10分間隔で確認します。`;
        } catch (error) { notice.textContent = "動画の取得状況を確認できません。表示を更新して再試行してください。"; }
    }

    form.addEventListener("submit", event => {
        event.preventDefault();
        syncUrl();
        loadAll(true);
    });
    for (const section of sections) {
        buttonEl(section.name + "Prev").addEventListener("click", () => {
            if (section.page > 0) { section.page--; clearError(); loadSection(section); }
        });
        buttonEl(section.name + "Next").addEventListener("click", () => {
            if (section.page + 1 < section.totalPages) { section.page++; clearError(); loadSection(section); }
        });
    }
    buttonEl("refreshVideosBtn").addEventListener("click", async () => {
        await loadChannels();
        if (!form.isConnected) return;
        syncUrl(true);
        await loadAll(true);
    });

    return {
        async start() {
            await loadChannels();
            if (!form.isConnected) return;
            restore();
            await loadAll(true);
        },
        restore,
        loadAll,
    };
}
