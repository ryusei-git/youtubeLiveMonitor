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
            // パスワードは BCrypt のハッシュしか無く見られないので、どの行も同じ伏せ字にする（長さも実際とは関係ない）
            row.innerHTML = `<td>${collapsibleCell(user.username)}${managed ? ' <button type="button" class="renameUser" aria-label="利用者名を変更" title="利用者名を変更">変更</button>' : ""}</td>
                <td><span class="muted maskedPassword" title="パスワードは見られません">●●●●●●●●</span>${managed ? ' <button type="button" class="setUserPassword" aria-label="パスワードを変更" title="パスワードを変更">変更</button>' : ""}</td>
                <td>${managed ? "一般利用者" : "管理者"}</td>
                <td>${statusLamp(user.enabled ? "live" : "idle", user.enabled ? "有効" : "無効")}</td>
                <td>${datetimeCell(user.createdAt)}</td>
                <td>${user.lastLoginAt ? datetimeCell(user.lastLoginAt) : '<span class="muted">未ログイン</span>'}</td>
                <td>${managed ? `<button type="button" class="resetUser" aria-label="再設定用のリンクを発行" title="再設定用のリンクを発行" ${user.enabled ? "" : "disabled"}>再設定リンク</button>
                    ${user.enabled ? '<button type="button" class="disableUser">無効化</button>' : '<button type="button" class="enableUser">有効化</button>'}
                    <button type="button" class="deleteUser">削除</button>` : '<span class="muted">管理者は操作できません</span>'}</td>`;
            if (managed) {
                query(".resetUser", row).addEventListener("click", (ev) => issueResetLink(user, /** @type {HTMLButtonElement} */ (ev.currentTarget)));
                query(user.enabled ? ".disableUser" : ".enableUser", row).addEventListener("click", () => changeUser(user, user.enabled ? "disable" : "enable"));
                query(".deleteUser", row).addEventListener("click", () => changeUser(user, "delete"));
                query(".renameUser", row).addEventListener("click", () => openUserEdit(user, "rename"));
                query(".setUserPassword", row).addEventListener("click", () => openUserEdit(user, "password"));
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
 * @param {"disable"|"delete"|"enable"} action 行う操作
 */
async function changeUser(user, action) {
    if (userOperationPending) return;
    const message = action === "delete"
        ? `「${user.username}」を削除しますか？\n利用者と購読を削除します。元には戻せません。共有チャンネル・録画・監査履歴は残ります。`
        : action === "disable"
            ? `「${user.username}」を無効化しますか？\nログイン中のアクセスも停止します。`
            : `「${user.username}」を有効に戻しますか？`;
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
        if (action === "delete") await apiDelete(`/api/admin/users/${user.id}`);
        else await apiPost(`/api/admin/users/${user.id}/${action}`, {});
        showToast(action === "delete" ? "利用者を削除しました" : action === "disable" ? "利用者を無効化しました" : "利用者を有効に戻しました");
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

/** @type {any} 「利用者の変更」の欄で変えている利用者。閉じているときは null。 */
let userEditTarget = null;
/** @type {boolean} 保存の二重送信を避ける。送信中に別の行のボタンで欄の中身を差し替えさせないためにも使う。 */
let userEditPending = false;

/** 欄の入力を空へ戻す。パスワードは見せないので、閉じる・別の利用者へ切り替えるたびに残さない。 */
function clearUserEditInputs() {
    for (const id of ["renameUsername", "renameAdminPassword", "userNewPassword", "userNewPasswordConfirm", "userPasswordAdminPassword"]) {
        inputEl(id).value = "";
    }
}

/** @param {string|null} message 欄の中に出す失敗の文言。null なら隠す */
function showUserEditError(message) {
    const box = el("userEditError");
    box.textContent = message ?? "";
    box.hidden = message === null;
}

/**
 * 一般利用者の利用者名・パスワードを変える欄を、表の上に出す（#808。API は #807）。
 * 再設定用のリンク（{@link issueResetLink}）と同じく表の上に出し、どの行の操作かを見出しで示す。
 * @param {any} user 対象の表示情報
 * @param {"rename"|"password"} mode 変えるもの
 */
function openUserEdit(user, mode) {
    if (userEditPending) return;
    userEditTarget = user;
    clearUserEditInputs();
    showUserEditError(null);
    el("userEditTitle").textContent = mode === "rename"
        ? `${user.username} の利用者名を変更`
        : `${user.username} のパスワードを変更`;
    formEl("renameForm").hidden = mode !== "rename";
    formEl("userPasswordForm").hidden = mode !== "password";
    const panel = el("userEditPanel");
    panel.hidden = false;
    panel.scrollIntoView({ block: "nearest" });
    inputEl(mode === "rename" ? "renameUsername" : "userNewPassword").focus();
}

function closeUserEdit() {
    if (userEditPending) return;
    userEditTarget = null;
    clearUserEditInputs();
    showUserEditError(null);
    el("userEditPanel").hidden = true;
}

/**
 * 欄の保存を送る。成功したら欄を閉じて表を読み直す。
 * 失敗したら欄は開いたまま、新しい値は打ち直さずに済むよう残し、管理者のパスワードだけ消す。
 * API が断った（403・404・409 など）ときは、ほかの管理者が先に変えたかもしれないので表も読み直す。
 * 通信そのものの失敗（authenticatedFetch が TypeError を cause に包む）では、表の読み直しも失敗するので行わない。
 * @param {HTMLFormElement} form 送るフォーム
 * @param {HTMLInputElement} adminPassword 管理者のパスワードの入力欄
 * @param {(user: any) => Promise<string>} send API を呼び、成功時のトーストの文言を返す
 */
async function submitUserEdit(form, adminPassword, send) {
    const user = userEditTarget;
    if (userEditPending || !user) return;
    userEditPending = true;
    const buttons = form.querySelectorAll("button");
    buttons.forEach(button => { button.disabled = true; });
    try {
        const message = await send(user);
        userEditPending = false;
        closeUserEdit();
        showToast(message);
        await loadUsers();
    } catch (e) {
        showUserEditError(errorMessage(e));
        if (!(e instanceof Error && e.cause instanceof TypeError)) await loadUsers();
    } finally {
        adminPassword.value = "";
        buttons.forEach(button => { button.disabled = false; });
        userEditPending = false;
    }
}

formEl("renameForm").addEventListener("submit", (event) => {
    event.preventDefault();
    const username = inputEl("renameUsername");
    const adminPassword = inputEl("renameAdminPassword");
    submitUserEdit(formEl("renameForm"), adminPassword, async (user) => {
        const next = username.value.trim();
        await apiPut(`/api/admin/users/${user.id}/username`, { username: username.value, adminPassword: adminPassword.value });
        return `${user.username} の利用者名を ${next} に変更しました。本人に新しい利用者名を伝えてください`;
    });
});

/** 確認欄はサーバーへ送らない。自分のパスワードの変更と同じく、打ち間違いを画面だけで確かめる。 */
formEl("userPasswordForm").addEventListener("submit", (event) => {
    event.preventDefault();
    const next = inputEl("userNewPassword");
    const confirmInput = inputEl("userNewPasswordConfirm");
    const adminPassword = inputEl("userPasswordAdminPassword");
    if (next.value !== confirmInput.value) {
        showUserEditError("新しいパスワードが一致しません");
        adminPassword.value = "";
        return;
    }
    submitUserEdit(formEl("userPasswordForm"), adminPassword, async (user) => {
        await apiPut(`/api/admin/users/${user.id}/password`, { password: next.value, adminPassword: adminPassword.value });
        return `${user.username} のパスワードを変更しました。本人に新しいパスワードを伝えてください`;
    });
});

document.querySelectorAll(".cancelUserEdit").forEach(button => button.addEventListener("click", closeUserEdit));

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
