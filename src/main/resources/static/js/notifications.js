// @ts-check
// 画面固有の状態をグローバルへ漏らさないため、全体を即時実行関数で包む。
// （各画面のスクリプトは <script> で読み込まれ、既定では同じスコープを共有するため）
(() => {
    let currentPage = 0;
    const pageSize = 20;
    let totalPages = 1;
    /** 読み込みの通し番号。条件を続けて変えたとき、遅れて届いた古い応答で表を上書きしないため */
    let historyRequest = 0;

    /**
     * 結果の選択肢の値。URL から条件を戻すとき、これ以外の値を API へ送らないために使う
     * （API は status を列挙型で受けるので、手で書き換えた URL の値をそのまま送るとエラーになる）。
     */
    const STATUS_VALUES = ["SUCCESS", "FAILED"];

    async function loadChannelOptions() {
        try {
            const channels = await apiGet("/api/channels");
            const select = selectEl("channelFilter");
            for (const ch of channels) {
                const opt = document.createElement("option");
                opt.value = ch.id;
                opt.textContent = ch.channelName;
                select.appendChild(opt);
            }
        } catch (e) {
            showError(errorMessage(e));
        }
    }

    /**
     * 今の入力欄の絞り込み条件をクエリにする。API への要求と URL の両方で使う。
     * 空の条件は載せない（API は省略を「条件なし」として扱い、URL も短く保てるため）。
     *
     * @returns {URLSearchParams} 条件だけのクエリ（page・size は含まない）
     */
    function filterParams() {
        const params = new URLSearchParams();
        const channelId = selectEl("channelFilter").value;
        const status = selectEl("statusFilter").value;
        const keyword = inputEl("keywordFilter").value.trim();
        const since = inputEl("sinceFilter").value;
        const until = inputEl("untilFilter").value;
        if (channelId) params.set("channelId", channelId);
        if (status) params.set("status", status);
        if (keyword) params.set("keyword", keyword);
        if (since) params.set("since", since);
        if (until) params.set("until", until);
        return params;
    }

    /**
     * URL のクエリから絞り込みの入力欄を埋める。
     *
     * <p>ダッシュボードの「うち送信失敗 N件」から、失敗だけ・直近 24 時間に絞った状態で開くため。
     * また、再読み込みやブックマークで同じ条件に戻れるようにするため。
     * 選択欄は選択肢に無い値（手で書き換えた URL・削除済みのチャンネル）を既定に戻す。
     * 日時の欄は形式の合わない値を入れるとブラウザが空にするので、そのまま入れてよい。
     * チャンネルの選択肢は API から読むので、loadChannelOptions() の後に呼ぶこと。
     */
    function restoreFiltersFromUrl() {
        const params = new URLSearchParams(location.search);
        const channelSelect = selectEl("channelFilter");
        const channelId = params.get("channelId") ?? "";
        channelSelect.value = Array.from(channelSelect.options).some((o) => o.value === channelId) ? channelId : "";
        const status = params.get("status") ?? "";
        selectEl("statusFilter").value = STATUS_VALUES.includes(status) ? status : "";
        inputEl("keywordFilter").value = (params.get("keyword") ?? "").trim().slice(0, 200);
        inputEl("sinceFilter").value = params.get("since") ?? "";
        inputEl("untilFilter").value = params.get("until") ?? "";
    }

    /**
     * 今の絞り込み条件を URL に書き出す。ページ番号は載せない（条件を変えたら 1 ページ目に戻すため）。
     * 履歴を積まない replaceState にするのは、この画面が「戻る」で条件をさかのぼる処理（popstate）を持たず、
     * 積むと「戻る」で URL だけが変わって表と食い違うため。
     */
    function syncUrl() {
        const params = filterParams().toString();
        history.replaceState(null, "", params ? `${location.pathname}?${params}` : location.pathname);
    }

    async function loadHistory() {
        const request = ++historyRequest;
        try {
            const params = filterParams();
            const hasFilter = params.toString() !== "";
            params.set("page", String(currentPage));
            params.set("size", String(pageSize));
            const data = await apiGet(`/api/notifications?${params}`);
            if (request !== historyRequest) return;
            clearError();
            totalPages = data.totalPages || 1;

            const tbody = query("#historyTable tbody");
            tbody.innerHTML = "";
            for (const h of data.content) {
                const tr = document.createElement("tr");
                tr.innerHTML = `
                    <td>${datetimeCell(h.notifiedAt)}</td>
                    <td>${channelLink(h.channelName, h.channelUrl)}</td>
                    <td>${collapsibleCell(h.videoTitle)}</td>
                    <td>${videoLink(h.videoId)}</td>
                    <td>${h.status === "SUCCESS" ? "成功" : '<span class="error">失敗</span>'}</td>
                    <td>${collapsibleCell(h.errorMessage)}</td>
                `;
                tbody.appendChild(tr);
            }
            bindDatetimeCells(tbody);

            // 0 件のときは空の表を出さない。見出しだけの表では、読み込みに失敗したのか該当が無いのか区別できないため
            const isEmpty = data.content.length === 0;
            el("historyTableWrap").hidden = isEmpty;
            const empty = el("historyEmpty");
            empty.hidden = !isEmpty;
            empty.innerHTML = !isEmpty ? ""
                : hasFilter
                    ? emptyState("条件に合う通知はありません", "条件を変えるか、「条件をクリア」を押してください。")
                    : emptyState("通知はまだありません", "監視中のチャンネルが配信を始めて通知を送ると、ここに記録されます。");

            el("pageInfo").textContent = `${currentPage + 1} / ${totalPages}`;
            updatePagination(currentPage, totalPages);
        } catch (e) {
            if (request === historyRequest) showError(errorMessage(e));
        }
    }

    el("filterForm").addEventListener("submit", (ev) => {
        ev.preventDefault();
        currentPage = 0;
        syncUrl();
        loadHistory();
    });

    el("resetBtn").addEventListener("click", () => {
        selectEl("channelFilter").value = "";
        selectEl("statusFilter").value = "";
        inputEl("keywordFilter").value = "";
        inputEl("sinceFilter").value = "";
        inputEl("untilFilter").value = "";
        currentPage = 0;
        syncUrl();
        loadHistory();
    });

    el("prevBtn").addEventListener("click", () => {
        if (currentPage > 0) {
            currentPage--;
            loadHistory();
        }
    });

    el("nextBtn").addEventListener("click", () => {
        if (currentPage + 1 < totalPages) {
            currentPage++;
            loadHistory();
        }
    });

    /**
     * チャンネルの選択肢を読んでから URL の条件を戻し、一覧を読む。
     * 先に戻すと、まだ無い選択肢を選べずにチャンネルの条件が落ちるため。
     * 戻した後に syncUrl() で URL を書き直し、選択肢に無かった値などを URL からも消す（表と URL を食い違わせない）。
     */
    async function init() {
        await loadChannelOptions();
        restoreFiltersFromUrl();
        syncUrl();
        await loadHistory();
    }

    init();
})();
