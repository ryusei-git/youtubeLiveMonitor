/** @type {boolean} 再描画による二重操作を避ける。 */
let userOperationPending = false;

/** 取得失敗時には以前の一覧を残し、操作失敗を空の一覧と取り違えない。 */
async function loadUsers() {
    const table = el("userTable");
    buttonEl("reloadUsers").disabled = true;
    setBusy(table, true);
    try {
        const users = await apiGet("/api/admin/users");
        const body = query("tbody", table);
        body.replaceChildren();
        for (const user of users) {
            const row = document.createElement("tr");
            const managed = user.role === "USER";
            row.innerHTML = `<td>${collapsibleCell(user.username)}</td>
                <td>${managed ? "一般利用者" : "管理者"}</td>
                <td>${statusLamp(user.enabled ? "live" : "idle", user.enabled ? "有効" : "無効")}</td>
                <td>${datetimeCell(user.createdAt)}</td>
                <td>${user.lastLoginAt ? datetimeCell(user.lastLoginAt) : '<span class="muted">未ログイン</span>'}</td>
                <td>${managed ? `<button type="button" class="resetUser" ${user.enabled ? "" : "disabled"}>再設定用のリンクを発行</button>
                    <button type="button" class="disableUser" ${user.enabled ? "" : "disabled"}>無効化</button>
                    <button type="button" class="deleteUser">削除</button>` : '<span class="muted">管理者は操作できません</span>'}</td>`;
            if (managed) {
                query(".resetUser", row).addEventListener("click", (ev) => issueResetLink(user, /** @type {HTMLButtonElement} */ (ev.currentTarget)));
                query(".disableUser", row).addEventListener("click", () => changeUser(user, false));
                query(".deleteUser", row).addEventListener("click", () => changeUser(user, true));
            }
            body.append(row);
        }
        bindDatetimeCells(body);
        el("userCount").textContent = `${users.length}人`;
        el("userEmpty").hidden = users.length !== 0;
        el("userEmpty").innerHTML = users.length ? "" : emptyState("利用者はいません", "招待から新しい利用者を登録できます");
        table.hidden = users.length === 0;
        clearError();
    } catch (error) {
        showError(errorMessage(error));
    } finally {
        setBusy(table, false);
        buttonEl("reloadUsers").disabled = false;
    }
}

/**
 * 取り消せない操作の対象と影響を、送信直前に確認する。
 * @param {any} user 対象の表示情報
 * @param {boolean} remove 削除か、無効化か
 */
async function changeUser(user, remove) {
    if (userOperationPending) return;
    const message = remove
        ? `「${user.username}」を削除しますか？\n利用者と購読を削除します。元には戻せません。共有チャンネル・録画・監査履歴は残ります。`
        : `「${user.username}」を無効化しますか？\nログイン中のアクセスも停止します。この画面から有効には戻せません。`;
    if (!confirm(message)) return;
    userOperationPending = true;
    const buttons = document.querySelectorAll("#userTable button, #reloadUsers");
    const previousStates = Array.from(buttons, button => {
        const control = /** @type {HTMLButtonElement} */ (button);
        const disabled = control.disabled;
        control.disabled = true;
        return { control, disabled };
    });
    try {
        if (remove) await apiDelete(`/api/admin/users/${user.id}`);
        else await apiPost(`/api/admin/users/${user.id}/disable`, {});
        showToast(remove ? "利用者を削除しました" : "利用者を無効化しました");
        await loadUsers();
    } catch (error) {
        // 最新状態を取り直し、削除済みの行を操作し続けないようにする。
        await loadUsers();
        showError(errorMessage(error));
    } finally {
        previousStates.forEach(({ control, disabled }) => {
            if (control.isConnected) control.disabled = disabled;
        });
        userOperationPending = false;
        buttonEl("reloadUsers").disabled = false;
    }
}

/**
 * 一般利用者の再設定用のリンクを発行して、表の上に出す（#326。API は #324）。
 * パスワードは管理者にも分からないまま、本人が決め直せるようにするため。
 * URL は招待リンクと同じく画面が組み立てる（サーバーは外から見える自分の URL を知らないため）。
 * @param {any} user 対象の表示情報
 * @param {HTMLButtonElement} button 押したボタン
 */
async function issueResetLink(user, button) {
    if (!confirm(`「${user.username}」の再設定用のリンクを発行しますか？\nリンクを開いた人が新しいパスワードを決められます。本人にだけ伝えてください。`)) return;
    button.disabled = true;
    try {
        const result = await apiPost(`/api/admin/users/${user.id}/password-reset`, {});
        el("resetLinkNote").textContent = `「${user.username}」の再設定用のリンクです。${formatDateTimeSimple(result.expiresAt)} まで、1 回だけ使えます。`;
        el("resetLinkField").replaceChildren(linkField(`${location.origin}/password-reset.html?token=${encodeURIComponent(result.token)}`, "再設定用のリンク"));
        el("resetLinkPanel").hidden = false;
        clearError();
        showToast("再設定用のリンクを発行しました");
    } catch (e) {
        showError(errorMessage(e));
    } finally {
        button.disabled = false;
    }
}

el("reloadUsers").addEventListener("click", loadUsers);

/**
 * 管理者が自分のパスワードを変える（#323。API は #321）。
 * 管理者の画面は利用者の /my を通らないため、同じ API をここから呼ぶ。
 * 確認欄はサーバーへ送らない。打ち間違いに気づかないまま変えると本人がログインできなくなるため、画面だけで確かめる。
 * パスワードは見せないので、入力欄は成功・失敗にかかわらず送信後に空へ戻す。
 */
formEl("passwordForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const current = inputEl("currentPassword");
    const next = inputEl("newPassword");
    const confirmInput = inputEl("newPasswordConfirm");
    if (next.value !== confirmInput.value) {
        showError("新しいパスワードが一致しません");
        return;
    }
    const button = /** @type {HTMLButtonElement} */ (query("button[type=submit]", formEl("passwordForm")));
    button.disabled = true;
    try {
        await apiPut("/api/my/password", { currentPassword: current.value, newPassword: next.value });
        clearError();
        showToast("パスワードを変更しました");
    } catch (e) {
        // 今のパスワードの誤りなどの 400 は、サーバーの文言をそのまま出す
        showError(errorMessage(e));
    } finally {
        button.disabled = false;
        current.value = next.value = confirmInput.value = "";
    }
});

loadUsers();
