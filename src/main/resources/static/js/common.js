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
 * @property {string} videoId 配信の動画ID
 * @property {string} videoTitle 録画開始時点の配信タイトル
 * @property {string} filePath 録画ディレクトリからの相対パス
 * @property {number|null} fileSizeBytes ファイルサイズ。録画中・失敗時は null
 * @property {number|null} durationSeconds 再生時間。未取得なら null
 * @property {string|null} thumbnailPath サムネイルの相対パス。未生成なら null
 * @property {"RECORDING"|"COMPLETED"|"PARTIAL"|"FAILED"} status 録画の状態
 * @property {string} startedAt 録画を開始した時刻（ISO形式）
 * @property {string|null} completedAt 完了・失敗した時刻。録画中は null
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
 * チャンネル名を YouTube のチャンネルページへのリンクにする。
 *
 * 未登録チャンネルの録画（URL指定でダウンロードしたもの）ではチャンネルIDが無いため、
 * その場合はリンクにせず名前だけを出す。
 *
 * @param {string} channelName 表示するチャンネル名
 * @param {string|null} youtubeChannelId リンク先のチャンネルID。無ければ null
 * @returns {string} セルへ差し込む HTML
 */
function channelLink(channelName, youtubeChannelId) {
    if (!youtubeChannelId) return escapeHtml(channelName);
    return `<a href="https://www.youtube.com/channel/${escapeHtml(youtubeChannelId)}"`
        + ` target="_blank" rel="noopener noreferrer">${escapeHtml(channelName)}</a>`;
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
 * @returns {HTMLElement} カード要素
 */
function buildVideoCard(recording, onDelete, linkToPlayer = true, onPlay = null) {
    const card = document.createElement("div");
    card.className = "videoCard";

    const href = `/player.html?id=${recording.id}`;
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
          <div class="muted">${channelLink(recording.channelName, recording.youtubeChannelId)}</div>
          <div class="muted">${datetimeCell(recording.startedAt)} ・ ${status}`
        + ` ・ ${formatFileSize(recording.fileSizeBytes)}</div>
          <div class="cardActions">${playButton}${deletable ? '<button class="deleteBtn">削除</button>' : ""}</div>
        </div>
    `;

    const playBtn = /** @type {HTMLButtonElement|null} */ (card.querySelector(".playBtn"));
    if (playBtn && onPlay) {
        playBtn.addEventListener("click", () => onPlay(recording, playBtn));
    }

    const deleteBtn = card.querySelector(".deleteBtn");
    if (deleteBtn && onDelete) {
        deleteBtn.addEventListener("click", async () => {
            if (!confirm("この録画を削除しますか？（録画ファイルも一緒に削除されます）")) return;
            try {
                await apiDelete(`/api/recordings/${recording.id}`);
                clearError();
                showToast(`「${recording.videoTitle}」を削除しました`, "danger");
                onDelete();
            } catch (e) {
                showError(errorMessage(e));
            }
        });
    }
    return card;
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
 * 操作の結果を画面の隅に短く出す。
 *
 * <p>保存・削除が効いたかどうかを、一覧の再読み込みを待たずに伝えるため。
 * <b>エラーはここでは扱わない</b>——エラーはエラー帯（{@link showError}）が担当し、
 * 消えてしまっては困る情報を自動で消さないようにしている。
 *
 * @param {string} message 表示する文言
 * @param {"info"|"danger"} kind 種別。danger は削除など取り消しにくい操作の結果に使う
 */
function showToast(message, kind = "info") {
    let stack = document.querySelector(".toastStack");
    if (!stack) {
        stack = document.createElement("div");
        stack.className = "toastStack";
        // 読み上げ環境にも伝える。操作の結果は「今起きたこと」なので polite で十分
        stack.setAttribute("aria-live", "polite");
        document.body.appendChild(stack);
    }

    const item = document.createElement("div");
    item.className = kind === "danger" ? "toast is-danger" : "toast";
    item.textContent = message;
    stack.appendChild(item);

    window.setTimeout(() => item.remove(), 3200);
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
    // 利用者向けの画面は自分が持っているデータから setLiveIndicator を呼ぶ
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
 * <p>数を持っている画面から直接呼べるようにしてある。ユーザー画面は
 * {@code /api/dashboard}（管理者専用）を見られないため、自分の購読一覧から数えて渡す。
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


/** 削除用スクリプトがHTML上のマーカーを消した後は、共通メニューにも追加しない。 */
const playgroundAvailable = Boolean(document.querySelector('.globalnav a[href="/playground.html"], .globalnav template[data-playground]'));

/** @type {Array<[string, string]>} 管理者画面の共通メニュー順。 */
const adminNavigation = [
    ["/videos.html", "動画・配信"], ["/index.html", "ダッシュボード"],
    ["/channels.html", "チャンネル"], ["/notifications.html", "通知履歴"],
    ["/recordings.html", "録画"], ["/users.html", "利用者管理"],
    ["/invitations.html", "招待"], ["/logs.html", "ログ"],
    ["/audit.html", "監査ログ"],
    ["/tables.html", "DB管理"], ["/playground.html", "APIお試し"]
];
/** @type {Array<[string, string]>} 一般利用者には管理リンクを載せない。 */
const userNavigation = [
    ["/videos.html", "動画・配信"], ["/my-channels.html", "マイチャンネル"],
    ["/my-recordings.html", "録画"]
];

/** @type {Record<string, [string, string]>} */
const studioPages = {
    "videos.html": ["", "M4 5h16v14H4z M10 9l5 3-5 3z"],
    "index.html": ["配信の状況と録画の動きを、ここから確認できます。", "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z"],
    "channels.html": ["お気に入りの配信者を登録して、通知・録画の条件を管理。", "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M20 8v6 M17 11h6"],
    "recordings.html": ["見たい配信を見つけて、好きなときに再生。", "M4 5h16v14H4z M10 9l5 3-5 3z"],
    "notifications.html": ["配信開始の通知と、送信結果を確認できます。", "M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21h4"],
    "logs.html": ["チャンネルやログレベルを絞って、動作状況を確認。", "M5 3h14v18H5z M8 7h8 M8 11h8 M8 15h5"],
    "audit.html": ["認証手続きと状態変更操作の証跡を、期間や操作者で絞り込んで確認。", "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z M9 12l2 2 4-4"],
    "tables.html": ["データの内容を確認・編集する管理者向けの画面です。", "M3 4h18v16H3z M3 9h18 M9 9v11"],
    "playground.html": ["APIのリクエストと応答を確認する診断ツール。", "M8 5l-6 7 6 7 M16 5l6 7-6 7 M14 3l-4 18"],
    "users.html": ["", "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M20 8v6 M17 11h6"],
    "invitations.html": ["招待リンクを発行して、サービスを共有できます。", "M3 5h18v14H3z M3 5l9 7 9-7"],
    "my-channels.html": ["フォローしている配信者と、自分の録画設定。", "M4 4h16v16H4z M8 9h8 M8 14h5"],
    "my-recordings.html": ["フォロー中のチャンネルの録画を、まとめて楽しむ。", "M4 5h16v14H4z M10 9l5 3-5 3z"],
    "player.html": ["保存した配信を再生。関連する録画もここから。", "M4 5h16v14H4z M10 9l5 3-5 3z"],
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
    const links = (admin ? adminNavigation : userNavigation)
        .filter(([href]) => href !== "/playground.html" || playgroundAvailable)
        .map(([href, label]) => {
        const link = document.createElement("a");
        link.href = href;
        link.textContent = label;
        if (href === page) { link.className = "active"; link.setAttribute("aria-current", "page"); }
        return link;
    });
    nav.replaceChildren(...links, ...(logout ? [logout] : []));
    decorateStudioNavigation();
}

/** 追加し直したリンクにも、同じアイコンとスマホの現在地表示を適用する。 */
function decorateStudioNavigation() {
    document.querySelectorAll(".globalnav a").forEach((link) => {
        const path = (link.getAttribute("href") || "").split("/").pop() || "";
        const icon = studioPages[path]?.[1] || "M9 4H4v16h5 M13 8l4 4-4 4 M8 12h13";
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
    if (current === "videos.html") renderNavigationForViewer(false);
    else renderNavigationForViewer(!["my-channels.html", "my-recordings.html"].includes(current || ""));
    const main = document.querySelector("main");
    if (main) {
        main.id = "mainContent";
        main.setAttribute("tabindex", "-1");
        const skip = document.createElement("a");
        skip.className = "skipLink";
        skip.href = "#mainContent";
        skip.textContent = "本文へ移動";
        document.body.prepend(skip);
        const heading = main.querySelector("h1");
        const description = studioPages[location.pathname.split("/").pop() || "index.html"]?.[0];
        if (heading && description) {
            const note = document.createElement("p");
            note.className = "pageDescription";
            note.textContent = description;
            (heading.closest(".pageHead") || heading).after(note);
        }
    }
    // プレースホルダーが消えた後も、支援技術から入力の目的を確認できるようにする。
    document.querySelectorAll("input[placeholder], select").forEach((control) => {
        if (control.hasAttribute("aria-label") || control.closest("label")) return;
        const id = control.id;
        if (id && document.querySelector(`label[for="${id}"]`)) return;
        const label = control.getAttribute("placeholder") || control.querySelector("option")?.textContent;
        if (label) control.setAttribute("aria-label", label);
    });
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
        <p class="muted">${escapeHtml(video.channelName)} · ${escapeHtml(video.platform)}</p>
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
    const state = video.state === "LIVE" ? "配信中" : video.state === "UNKNOWN" ? "配信状態を確認中" : video.playable ? "動画・アーカイブ" : "配信終了・アーカイブ未取得";
    card.innerHTML = `<button type="button" class="thumbLink onlinePlayButton" aria-label="${escapeHtml(video.title)}を再生" ${video.playable ? "" : "disabled"}>
        <img class="thumb" ${video.thumbnailRetryExhausted ? "hidden" : `src="${escapeHtml(video.thumbnailUrl)}"`} alt="" loading="lazy">
        <span class="thumbPlaceholder" ${video.thumbnailRetryExhausted ? "" : "hidden"}>${video.thumbnailRetryExhausted ? "サムネイル取得失敗" : "サムネイル取得待ち"}</span>
        <span class="onlinePlayMark" aria-hidden="true">▶</span>
        </button><div class="cardBody"><h3 class="cardTitle"><button type="button" class="onlineTitle" ${video.playable ? "" : "disabled"}>${escapeHtml(video.title)}</button></h3>
        <div class="muted">${escapeHtml(video.channelName)} · ${escapeHtml(video.platform)}</div>
        <div class="muted">${escapeHtml(state)}</div><div class="muted">${escapeHtml(formatInstant(video.publishedAt))}</div></div>`;
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
 */
function renderLiveVideoCards(target, page) {
    target.replaceChildren(...page.content.map(buildOnlineVideoCard));
    if (!page.content.length) target.innerHTML = emptyState("配信中の動画はありません", "起動直後・判定失敗時は、次の正常な確認を待って表示します。");
}

/** 非表示中の定期通信を省き、戻ってきたときだけ最新の保存済み状態を読む。
 * @param {() => void} refresh
 */
function startVisibleRefresh(refresh) {
    window.setInterval(() => { if (document.visibilityState === "visible") refresh(); }, 60_000);
    document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "visible") refresh();
    });
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
