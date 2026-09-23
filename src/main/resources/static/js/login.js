// @ts-check
/*
 * ログイン画面専用の処理。
 *
 * ログイン処理自体は素の <form method="post"> によるページ遷移で行う（Spring Security の
 * loginProcessingUrl がフォームの username/password パラメータをそのまま受け取る仕組みのため、
 * 他画面のように fetch で組み立てる必要が無い）。ここでやるのは、Spring Security が
 * 失敗時に付与する ?error、ログアウト成功時に付与する ?logout の有無を見て、
 * 対応するメッセージを表示するだけ。
 */

const loginError = document.getElementById("loginError");
if (loginError && queryParam("error") !== null) {
    loginError.style.display = "block";
}

const logoutNotice = document.getElementById("logoutNotice");
if (logoutNotice && queryParam("logout") !== null) {
    logoutNotice.style.display = "block";
}

// 通常のフォーム送信でも、APIと同じトークンを返す必要がある。
const loginForm = document.querySelector("form");
if (loginForm) {
    loginForm.addEventListener("submit", () => {
        let token = loginForm.querySelector('input[name="_csrf"]');
        if (!token) {
            token = document.createElement("input");
            token.setAttribute("type", "hidden");
            token.setAttribute("name", "_csrf");
            loginForm.appendChild(token);
        }
        token.setAttribute("value", csrfHeaders()["X-XSRF-TOKEN"] || "");
    });
}

// URLから受け取った値はサーバーが画面・権限を検証してから復帰先に使う。
const loginReturnTo = queryParam("returnTo");
if (loginForm && loginReturnTo) {
    const field = document.createElement("input");
    field.type = "hidden";
    field.name = "returnTo";
    field.value = loginReturnTo;
    loginForm.append(field);
}
if (queryParam("expired") !== null) {
    const notice = document.createElement("p");
    notice.className = "muted";
    notice.setAttribute("role", "status");
    notice.textContent = "ログインの有効期限が切れました。もう一度ログインしてください。直前の操作は自動では再実行されません。";
    loginForm?.before(notice);
}
