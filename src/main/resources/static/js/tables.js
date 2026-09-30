// @ts-check
// 画面固有の状態をグローバルへ漏らさないため、全体を即時実行関数で包む。
// （各画面のスクリプトは <script> で読み込まれ、既定では同じスコープを共有するため）
(() => {
    /** @type {string} */
    let currentTable = "";
    const pageSize = 20;
    const pager = bindPager({ load: loadTableData });

    async function loadTableList() {
        try {
            const tables = await apiGet("/api/admin/tables");
            const select = selectEl("tableSelect");
            select.innerHTML = "";
            for (const t of tables) {
                const opt = document.createElement("option");
                opt.value = t.tableName;
                opt.textContent = t.label;
                opt.title = `物理名: ${t.tableName}`;
                select.appendChild(opt);
            }
            if (tables.length > 0) {
                currentTable = tables[0].tableName;
                loadTableData();
            }
        } catch (e) {
            showError(errorMessage(e));
        }
    }

    async function loadTableData() {
        try {
            const data = await apiGet(`/api/admin/tables/${currentTable}?page=${pager.page()}&size=${pageSize}`);
            clearError();

            const thead = query("#dataTable thead");
            thead.innerHTML = "<tr>" + /** @type {string[]} */ (data.columns).map((c) =>
                `<th title="物理名: ${escapeHtml(c)}">${escapeHtml(data.columnLabels[c] || c)}</th>`).join("") + "</tr>";

            const tbody = query("#dataTable tbody");
            tbody.innerHTML = "";
            for (const row of data.rows) {
                const tr = document.createElement("tr");
                for (const col of data.columns) {
                    const td = document.createElement("td");
                    // バイナリの列のセルは中身ではなく「（バイナリ n バイト）」の文字なので編集させない
                    // （保存すると入力した文字がそのまま BLOB に書き込まれて壊れる。サーバーも断る）
                    const binary = data.binaryColumns.includes(col);
                    const editable = col !== data.primaryKeyColumn && !binary && inputEl("editMode").checked;
                    showValue(td, row[col], editable);
                    if (binary) td.title = "バイナリの列は編集できません";
                    if (editable) {
                        td.tabIndex = 0;
                        td.addEventListener("click", () => editCell(td, row, col, data.primaryKeyColumn));
                        td.addEventListener("keydown", (ev) => { if (ev.key === "Enter") editCell(td, row, col, data.primaryKeyColumn); });
                    }
                    tr.appendChild(td);
                }
                tbody.appendChild(tr);
            }
            pager.update(Math.ceil(data.totalElements / pageSize));
        } catch (e) {
            showError(errorMessage(e));
        }
    }

    /** 1 セルに表示する文字数の上限。これを超える値は末尾を省略して表示する。 */
    const CELL_DISPLAY_LIMIT = 50;

    /** 編集できるセルに出す案内。編集欄はクリック（キーボードでは Enter）で開く。 */
    const EDIT_HINT = "クリックまたは Enter で編集";

    /**
     * セルへ値を表示する。任意の値が入るテーブルなので、長い値は折り返さず末尾を省略し、
     * 全文はマウスオーバー（title 属性）と、編集モードでクリックしたときの編集欄で確認できるようにする。
     * 折り返してしまうと行の高さが不揃いになり、表全体が読みにくくなるため。
     *
     * 編集の案内は、実際に編集欄が開くセルにだけ出す。以前はどのセルにも「ダブルクリックで編集」と出していて、
     * 編集モードがオフのときや主キーの列のように押しても何も起きないセルでも編集できると読め、
     * 実際の操作（1 回のクリック）とも違っていた。
     *
     * @param {HTMLTableCellElement} td 対象のセル
     * @param {*} value 表示したい値
     * @param {boolean} editable このセルで編集欄が開くか（編集モードがオンで、主キーでもバイナリの列でもない）
     */
    function showValue(td, value, editable) {
        const full = value === null || value === undefined ? "" : String(value);
        const tooLong = full.length > CELL_DISPLAY_LIMIT;
        td.textContent = tooLong ? `${full.slice(0, CELL_DISPLAY_LIMIT)}…` : full;
        if (tooLong) {
            td.title = editable ? `${full}\n（${EDIT_HINT}）` : full;
        } else {
            td.title = editable ? EDIT_HINT : "";
        }
    }

    /**
     * セルを編集状態にする。
     *
     * @param {HTMLTableCellElement} td 対象のセル
     * @param {Record<string, any>} row 行のデータ
     * @param {string} col 編集するカラム名
     * @param {string} pkColumn 主キーのカラム名
     */
    function editCell(td, row, col, pkColumn) {
        if (td.querySelector("input")) return;
        const oldValue = row[col] === null || row[col] === undefined ? "" : row[col];
        const input = document.createElement("input");
        input.value = oldValue;

        let done = false;
        const commit = async () => {
            if (done) return;
            done = true;
            const newValue = input.value;
            if (newValue === String(oldValue)) {
                showValue(td, oldValue, true);
                return;
            }
            try {
                await apiPut(`/api/admin/tables/${currentTable}/${row[pkColumn]}`, { [col]: newValue });
                clearError();
                row[col] = newValue;
                showValue(td, newValue, true);
            } catch (e) {
                showError(errorMessage(e));
                showValue(td, oldValue, true);
            }
        };
        const cancel = () => {
            done = true;
            showValue(td, oldValue, true);
        };

        const save = document.createElement("button");
        save.textContent = "保存";
        const cancelButton = document.createElement("button");
        cancelButton.textContent = "取消";
        save.addEventListener("click", (ev) => {
            ev.stopPropagation();
            if (confirm(`${col}: ${String(oldValue)} → ${input.value} に変更しますか？`)) {
                save.disabled = cancelButton.disabled = input.disabled = true;
                commit();
            }
        });
        cancelButton.addEventListener("click", (ev) => { ev.stopPropagation(); cancel(); });
        save.className = "filterSave";
        // 入力欄とボタンを 1 行に収める入れ物（チャンネル一覧のフィルターの編集と同じ .filterEdit）。
        // td へ直接並べると、td input の width:100% で入力欄がセルの幅いっぱいになり、ボタンがセルの外へ
        // 押し出されて隣の列に重なる（スマホでは表の枠の外に出て、横に送らないと保存・取消を押せない）
        const editor = document.createElement("div");
        editor.className = "filterEdit";
        editor.append(input, save, cancelButton);
        td.replaceChildren(editor);
        input.focus();
        // 右寄りの列を開いたときも保存・取消が見えるよう、表の枠（.table-scroll）を横に送る
        editor.scrollIntoView({ block: "nearest", inline: "nearest" });
        input.addEventListener("keydown", (ev) => {
            if (ev.key === "Enter") { ev.stopPropagation(); save.click(); }
            if (ev.key === "Escape") cancel();
        });
    }

    selectEl("tableSelect").addEventListener("change", (ev) => {
        currentTable = /** @type {HTMLSelectElement} */ (ev.target).value;
        pager.reset();
        loadTableData();
    });

    inputEl("editMode").addEventListener("change", loadTableData);
    loadTableList();
})();
