// @ts-check
// APIお試しタブ専用。不要になったらこのファイルごと削除できるよう、
// common.js には一切追記していない（共通処理に混ぜると消すときに切り分けられなくなるため）。
// 画面固有の状態をグローバルへ漏らさないよう、全体を即時実行関数で包む。
(() => {
    /**
     * サーバーから受け取った API 定義。
     * @typedef {object} ApiParameterDefinition
     * @property {string} name
     * @property {string} label
     * @property {boolean} required
     * @property {string} placeholder
     *
     * @typedef {object} ApiDefinition
     * @property {string} id
     * @property {string} name
     * @property {string} description
     * @property {number} quotaCost
     * @property {ApiParameterDefinition[]} parameters
     */

    /** 「高コスト」として警告を出す閾値。search.list（100）を引っ掛けるための線引き。 */
    const EXPENSIVE_QUOTA_THRESHOLD = 50;

    /** @type {ApiDefinition[]} */
    let apiDefinitions = [];

    /**
     * 選択中の API 定義を返す。
     *
     * @returns {ApiDefinition|undefined} 見つかった定義
     */
    function selectedApi() {
        const id = selectEl("apiSelect").value;
        return apiDefinitions.find((api) => api.id === id);
    }

    /**
     * 選択中の API に合わせて、説明文と入力欄を組み直す。
     */
    function renderSelectedApi() {
        const api = selectedApi();
        const description = el("apiDescription");
        const container = el("apiParameters");
        container.innerHTML = "";

        if (!api) {
            description.textContent = "";
            return;
        }

        const badgeClass = api.quotaCost >= EXPENSIVE_QUOTA_THRESHOLD ? "quotaBadge expensive" : "quotaBadge";
        description.innerHTML = `${escapeHtml(api.description)}`
            + `<span class="${badgeClass}">クォータ ${api.quotaCost}</span>`;

        for (const parameter of api.parameters) {
            const label = document.createElement("label");
            label.className = "fieldLabel";
            label.setAttribute("for", `param_${parameter.name}`);
            label.textContent = parameter.required ? `${parameter.label}（必須）` : parameter.label;

            const input = document.createElement("input");
            input.type = "text";
            input.id = `param_${parameter.name}`;
            input.dataset.paramName = parameter.name;
            input.placeholder = parameter.placeholder;

            container.appendChild(label);
            container.appendChild(input);
        }
    }

    /**
     * 入力欄の値を、送信用のオブジェクトに集める。
     *
     * @returns {Record<string, string>} パラメータ名と値の組
     */
    function collectParameters() {
        /** @type {Record<string, string>} */
        const parameters = {};
        const inputs = /** @type {NodeListOf<HTMLInputElement>} */ (
            el("apiParameters").querySelectorAll("input[data-param-name]"));
        for (const input of inputs) {
            parameters[input.dataset.paramName ?? ""] = input.value;
        }
        return parameters;
    }

    async function loadApiList() {
        try {
            const data = await apiGet("/api/playground/apis");
            clearError();
            apiDefinitions = data.apis;
            el("quotaTotal").textContent = String(data.sessionQuotaTotal);

            const select = selectEl("apiSelect");
            select.innerHTML = "";
            for (const api of apiDefinitions) {
                const option = document.createElement("option");
                option.value = api.id;
                option.textContent = `${api.id}  ―  ${api.name}（クォータ ${api.quotaCost}）`;
                select.appendChild(option);
            }
            renderSelectedApi();
        } catch (e) {
            showError(errorMessage(e));
        }
    }

    selectEl("apiSelect").addEventListener("change", renderSelectedApi);

    formEl("apiForm").addEventListener("submit", async (ev) => {
        ev.preventDefault();
        const api = selectedApi();
        if (!api) return;

        // 高コストなAPIは押し間違いの損失が大きいので、実行前に一度止める
        if (api.quotaCost >= EXPENSIVE_QUOTA_THRESHOLD) {
            const confirmed = confirm(`${api.id} は1回で ${api.quotaCost} クォータを消費します。\n`
                + `1日の上限は既定10,000で、使い切ると監視・通知側も失敗するようになります。\n\n`
                + `実行しますか？`);
            if (!confirmed) return;
        }

        const btn = buttonEl("executeBtn");
        const status = el("executeStatus");
        btn.disabled = true;
        status.textContent = "実行中...";
        try {
            const result = await apiPost("/api/playground/execute", {
                apiId: api.id,
                parameters: collectParameters(),
            });
            clearError();
            el("quotaTotal").textContent = String(result.sessionQuotaTotal);

            const meta = el("responseMeta");
            if (result.success) {
                meta.textContent =
                    `成功 ・ ${result.elapsedMillis}ms ・ 消費クォータ ${result.quotaCost}`;
                el("responseBody").textContent = result.responseJson;
            } else {
                meta.innerHTML = `<span class="failed">失敗</span>`
                    + ` ・ ${result.elapsedMillis}ms ・ 消費クォータ ${result.quotaCost}`;
                // 失敗の内容自体が知りたい情報なので、そのまま本文へ出す
                el("responseBody").textContent = result.errorMessage;
            }
            status.textContent = "";
        } catch (e) {
            status.textContent = "";
            showError(errorMessage(e));
        } finally {
            btn.disabled = false;
        }
    });

    loadApiList();
})();
