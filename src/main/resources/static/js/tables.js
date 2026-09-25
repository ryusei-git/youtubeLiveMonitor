// @ts-check
// 画面固有の状態をグローバルへ漏らさないため、全体を即時実行関数で包む。
// （各画面のスクリプトは <script> で読み込まれ、既定では同じスコープを共有するため）
(() => {
    /** @type {string} */
    let currentTable = "";
    let currentPage = 0;
    const pageSize = 20;
    let totalPages = 1;

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
            const data = await apiGet(`/api/admin/tables/${currentTable}?page=${currentPage}&size=${pageSize}`);
            clearError();
            totalPages = Math.max(1, Math.ceil(data.totalElements / pageSize));

            const thead = query("#dataTable thead");
            thead.innerHTML = "<tr>" + /** @type {string[]} */ (data.columns).map((c) =>
                `<th title="物理名: ${escapeHtml(c)}">${escapeHtml(data.columnLabels[c] || c)}</th>`).join("") + "</tr>";

            const tbody = query("#dataTable tbody");
            tbody.innerHTML = "";
            for (const row of data.rows) {
                const tr = document.createElement("tr");
                for (const col of data.columns) {
                    const td = document.createElement("td");
                    showValue(td, row[col]);
                    // バイナリの列のセルは中身ではなく「（バイナリ n バイト）」の文字なので編集させない
                    // （保存すると入力した文字がそのまま BLOB に書き込まれて壊れる。サーバーも断る）
                    const binary = data.binaryColumns.includes(col);
                    if (binary) td.title = "バイナリの列は編集できません";
                    if (col !== data.primaryKeyColumn && !binary && inputEl("editMode").checked) {
                        td.tabIndex = 0;
                        td.addEventListener("click", () => editCell(td, row, col, data.primaryKeyColumn));
                        td.addEventListener("keydown", (ev) => { if (ev.key === "Enter") editCell(td, row, col, data.primaryKeyColumn); });
                    }
                    tr.appendChild(td);
                }
                tbody.appendChild(tr);
            }
            el("pageInfo").textContent = `${currentPage + 1} / ${totalPages}`;
        } catch (e) {
            showError(errorMessage(e));
        }
    }

    /** 1 セルに表示する文字数の上限。これを超える値は末尾を省略して表示する。 */
    const CELL_DISPLAY_LIMIT = 50;

    /**
     * セルへ値を表示する。任意の値が入るテーブルなので、長い値は折り返さず末尾を省略し、
     * 全文はマウスオーバー（title 属性）とダブルクリックの編集欄で確認できるようにする。
     * 折り返してしまうと行の高さが不揃いになり、表全体が読みにくくなるため。
     *
     * @param {HTMLTableCellElement} td 対象のセル
     * @param {*} value 表示したい値
     */
    function showValue(td, value) {
        const full = value === null || value === undefined ? "" : String(value);
        const tooLong = full.length > CELL_DISPLAY_LIMIT;
        td.textContent = tooLong ? `${full.slice(0, CELL_DISPLAY_LIMIT)}…` : full;
        td.title = tooLong ? full : "ダブルクリックで編集";
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
        td.textContent = "";
        const input = document.createElement("input");
        input.value = oldValue;
        td.appendChild(input);
        input.focus();

        let done = false;
        const commit = async () => {
            if (done) return;
            done = true;
            const newValue = input.value;
            if (newValue === String(oldValue)) {
                showValue(td, oldValue);
                return;
            }
            try {
                await apiPut(`/api/admin/tables/${currentTable}/${row[pkColumn]}`, { [col]: newValue });
                clearError();
                row[col] = newValue;
                showValue(td, newValue);
            } catch (e) {
                showError(errorMessage(e));
                showValue(td, oldValue);
            }
        };
        const cancel = () => {
            done = true;
            showValue(td, oldValue);
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
        td.append(save, cancelButton);
        input.addEventListener("keydown", (ev) => {
            if (ev.key === "Enter") { ev.stopPropagation(); save.click(); }
            if (ev.key === "Escape") cancel();
        });
    }

    selectEl("tableSelect").addEventListener("change", (ev) => {
        currentTable = /** @type {HTMLSelectElement} */ (ev.target).value;
        currentPage = 0;
        loadTableData();
    });

    el("prevBtn").addEventListener("click", () => {
        if (currentPage > 0) {
            currentPage--;
            loadTableData();
        }
    });

    el("nextBtn").addEventListener("click", () => {
        if (currentPage + 1 < totalPages) {
            currentPage++;
            loadTableData();
        }
    });

    inputEl("editMode").addEventListener("change", loadTableData);
    loadTableList();
})();
