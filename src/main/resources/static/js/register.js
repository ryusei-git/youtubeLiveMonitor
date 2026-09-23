/**
 * 招待リンクからの利用者登録画面。
 *
 * <p>この画面だけは未ログインで開ける。まだアカウントが無い時点で開くため。
 * 代わりに URL の token が鍵になっており、無効なら入力欄そのものを出さない。
 */

/** 招待リンクに載っている token。 */
const token = queryParam("token") || "";

/**
 * 招待が使えるか確かめて、画面の表示を切り替える。
 *
 * <p>先に確認してから入力欄を出す。無効なリンクにも入力欄を見せると、
 * 利用者が入力し終えてから「使えません」と言うことになる。
 */
async function checkInvitation() {
    if (!token) {
        showRejected("招待リンクが正しくありません。管理者から届いたURLをそのまま開いてください。");
        return;
    }
    try {
        const result = await apiGet(`/api/registration?token=${encodeURIComponent(token)}`);
        el("checking").style.display = "none";
        if (result.usable) {
            el("form").style.display = "block";
            inputEl("username").focus();
        } else {
            showRejected(result.reason);
        }
    } catch (e) {
        el("checking").style.display = "none";
        showError(errorMessage(e));
    }
}

/**
 * 使えない招待であることを伝える。
 *
 * @param {string} reason 理由
 */
function showRejected(reason) {
    el("checking").style.display = "none";
    el("form").style.display = "none";
    const box = el("rejected");
    box.style.display = "block";
    box.innerHTML = emptyState("このリンクからは登録できません", reason)
        + `<p class="muted">既にアカウントをお持ちなら <a href="/login.html">ログイン</a> してください。</p>`;
}

el("registerForm").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const username = inputEl("username").value.trim();
    const password = inputEl("password").value;
    const confirm = inputEl("passwordConfirm").value;

    // 打ち間違いに気づかないまま登録されると、本人がログインできなくなる
    if (password !== confirm) {
        showError("パスワードが一致しません");
        return;
    }

    const btn = /** @type {HTMLButtonElement} */ (query("button[type=submit]", el("registerForm")));
    btn.disabled = true;
    try {
        await apiPost("/api/registration", { token, username, password });
        clearError();
        el("form").style.display = "none";
        const done = el("done");
        done.style.display = "block";
        done.innerHTML = emptyState("登録が完了しました", `利用者名「${escapeHtml(username)}」で利用できます`)
            + `<p><a href="/login.html">ログイン画面へ進む</a></p>`;
    } catch (e) {
        showError(errorMessage(e));
    } finally {
        btn.disabled = false;
    }
});

checkInvitation();
