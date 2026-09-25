/**
 * 再設定用のリンクからパスワードを決め直す画面。
 *
 * <p>この画面は未ログインで開ける。パスワードを忘れてログインできない人が開くため。
 * 代わりに URL の token が鍵になっており、無効なら入力欄そのものを出さない。
 */

/** 再設定用のリンクに載っている token。register.js の token と同じ名前にすると、tsc が全画面の JS を 1 つの大域として見るため重複宣言になる。 */
const resetToken = queryParam("token") || "";

/**
 * 再設定用のリンクが使えるか確かめて、画面の表示を切り替える。
 *
 * <p>先に確認してから入力欄を出す。無効なリンクにも入力欄を見せると、
 * 利用者が入力し終えてから「使えません」と言うことになる。
 */
async function checkResetToken() {
    if (!resetToken) {
        showRejected("再設定用のリンクが正しくありません。管理者から届いたURLをそのまま開いてください。");
        return;
    }
    try {
        const result = await apiGet(`/api/password-reset?token=${encodeURIComponent(resetToken)}`);
        el("checking").style.display = "none";
        if (result.usable) {
            el("form").style.display = "block";
            inputEl("password").focus();
        } else {
            showRejected(result.reason);
        }
    } catch (e) {
        el("checking").style.display = "none";
        showError(errorMessage(e));
    }
}

/**
 * 使えないリンクであることを伝える。
 *
 * @param {string} reason 理由
 */
function showRejected(reason) {
    el("checking").style.display = "none";
    el("form").style.display = "none";
    const box = el("rejected");
    box.style.display = "block";
    box.innerHTML = emptyState("このリンクからは再設定できません", reason)
        + `<p class="muted"><a href="/userLogin.html">ログイン画面へ</a></p>`;
}

el("resetForm").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const password = inputEl("password").value;
    const confirm = inputEl("passwordConfirm").value;

    // 打ち間違いに気づかないまま再設定されると、本人がまたログインできなくなる
    if (password !== confirm) {
        showError("パスワードが一致しません");
        return;
    }

    const btn = /** @type {HTMLButtonElement} */ (query("button[type=submit]", el("resetForm")));
    btn.disabled = true;
    try {
        await apiPost("/api/password-reset", { token: resetToken, password });
        clearError();
        el("form").style.display = "none";
        const done = el("done");
        done.style.display = "block";
        done.innerHTML = emptyState("パスワードを再設定しました", "新しいパスワードでログインしてください")
            + `<p><a href="/userLogin.html">ログイン画面へ進む</a></p>`;
    } catch (e) {
        showError(errorMessage(e));
    } finally {
        btn.disabled = false;
    }
});

checkResetToken();
