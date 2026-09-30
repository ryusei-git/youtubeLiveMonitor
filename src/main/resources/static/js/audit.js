// @ts-check
// 画面固有の状態をグローバルへ漏らさないため、全体を即時実行関数で包む。
// （各画面のスクリプトは <script> で読み込まれ、既定では同じスコープを共有するため）
(() => {
    const pageSize = 20;
    const pager = bindPager({ load: () => loadAuditLogs(true) });

    /** 操作種別（Java の {@code AuditAction}）を表示用の日本語にする。フィルターの選択肢と対応させる。
     * @type {Record<string, string>} */
    const ACTION_LABEL = {
        LOGIN_SUCCESS: "ログイン成功",
        LOGIN_FAILURE: "ログイン失敗",
        LOGOUT: "ログアウト",
        PASSWORD_CHANGE: "パスワード変更",
        PASSWORD_RESET_ISSUE: "再設定リンク発行",
        PASSWORD_RESET: "パスワード再設定",
        USER_CREATE: "利用者作成",
        USER_DELETE: "利用者削除",
        USER_DISABLE: "利用者無効化",
        USER_ENABLE: "利用者有効化",
        INVITATION_ISSUE: "招待発行",
        INVITATION_REVOKE: "招待取消",
        ACCESS_DENIED: "アクセス拒否",
        CHANNEL_REGISTER: "チャンネル登録",
        CHANNEL_DELETE: "チャンネル削除",
        CHANNEL_SETTING_CHANGE: "チャンネル設定変更",
        CHANNEL_SUBSCRIBE: "チャンネル購読",
        CHANNEL_UNSUBSCRIBE: "チャンネル購読解除",
        DOWNLOAD_REQUEST: "ダウンロード要求",
        RECORDING_DELETE: "録画削除",
        NOTIFICATION_SETTING_CHANGE: "通知設定変更",
        APP_SETTING_CHANGE: "アプリ設定変更",
    };

    /**
     * 操作対象のセルを組み立てる。種類と識別子のどちらも無い操作（ログイン等）では "-"。
     * @param {string|null} targetType
     * @param {string|null} targetId
     * @returns {string}
     */
    function targetCell(targetType, targetId) {
        if (!targetType && !targetId) return "-";
        return escapeHtml([targetType, targetId].filter(Boolean).join(" #"));
    }

    /**
     * 監査ログを読み込んで表を描き直す。
     *
     * @param {boolean} [reveal] 失敗したとき、エラー帯が見える位置まで画面を動かすか。利用者が押した
     *   操作（絞り込み・条件のクリア・ページ送り）のときだけ true にする。表の下のページ送りで
     *   失敗すると、帯が画面の外に出て、「n / m」の番号だけが進み、何も起きなかったように見えるため。
     *   画面を開いたときの読み込みは false のまま（開いた直後は先頭にいて帯が見えている。
     *   showError の決まりと同じ）
     */
    async function loadAuditLogs(reveal = false) {
        try {
            const params = new URLSearchParams({ page: String(pager.page()), size: String(pageSize) });
            const since = inputEl("sinceFilter").value;
            const until = inputEl("untilFilter").value;
            const username = inputEl("usernameFilter").value.trim();
            const action = selectEl("actionFilter").value;
            const outcome = selectEl("outcomeFilter").value;
            const requestId = inputEl("requestIdFilter").value.trim();
            const hasFilter = [since, until, username, action, outcome, requestId].some(Boolean);
            if (since) params.set("since", since);
            if (until) params.set("until", until);
            if (username) params.set("username", username);
            if (action) params.set("action", action);
            if (outcome) params.set("outcome", outcome);
            if (requestId) params.set("requestId", requestId);

            const data = await apiGet(`/api/audit-logs?${params}`);
            clearError();

            const tbody = query("#auditTable tbody");
            tbody.innerHTML = "";
            for (const a of data.content) {
                const tr = document.createElement("tr");
                tr.innerHTML = `
                    <td>${datetimeCell(a.occurredAt)}</td>
                    <td>${escapeHtml(a.username ?? "-")}</td>
                    <td>${escapeHtml(ACTION_LABEL[a.action] ?? a.action)}</td>
                    <td>${targetCell(a.targetType, a.targetId)}</td>
                    <td>${a.outcome === "SUCCESS" ? "成功" : '<span class="error">失敗</span>'}</td>
                    <td>${escapeHtml(a.clientIp ?? "-")}</td>
                    <td>${escapeHtml(a.requestId ?? "-")}</td>
                    <td>${collapsibleCell(a.detail)}</td>
                `;
                tbody.appendChild(tr);
            }
            bindDatetimeCells(tbody);

            // 0 件のときは空の表を出さない。見出しだけの表では、読み込みに失敗したのか該当が無いのか区別できないため
            const isEmpty = data.content.length === 0;
            el("auditTableWrap").hidden = isEmpty;
            const empty = el("auditEmpty");
            empty.hidden = !isEmpty;
            empty.innerHTML = !isEmpty ? ""
                : hasFilter
                    ? emptyState("条件に合う監査ログはありません", "条件を変えるか、「条件をクリア」を押してください。")
                    : emptyState("監査ログはまだありません", "ログインや設定の変更などの操作をすると、ここに記録されます。");

            pager.update(data.totalPages || 1);
        } catch (e) {
            clearResults();
            showError(errorMessage(e), { reveal });
        }
    }

    /**
     * 表示中の結果を消す。読み込みに失敗したときに呼ぶ。
     *
     * <p>消さずにエラー帯だけ出すと、前の条件・前のページの行が残り、入力欄の条件と表の中身が
     * 食い違う（期間の開始と終了を逆にして絞り込むと、エラーの下に前の結果がそのまま出ていた）。
     * ページ送りの番号は触らない。update(1) で総ページ数だけ戻すと「4 / 1」のような表示になり、
     * 「次へ」で失敗したページを読み直せなくなるため。
     */
    function clearResults() {
        query("#auditTable tbody").innerHTML = "";
        el("auditTableWrap").hidden = true;
        const empty = el("auditEmpty");
        empty.hidden = true;
        empty.innerHTML = "";
    }

    el("filterForm").addEventListener("submit", (ev) => {
        ev.preventDefault();
        pager.reset();
        loadAuditLogs(true);
    });

    el("resetBtn").addEventListener("click", () => {
        inputEl("sinceFilter").value = "";
        inputEl("untilFilter").value = "";
        inputEl("usernameFilter").value = "";
        selectEl("actionFilter").value = "";
        selectEl("outcomeFilter").value = "";
        inputEl("requestIdFilter").value = "";
        pager.reset();
        loadAuditLogs(true);
    });

    loadAuditLogs();
})();
