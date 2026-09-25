/**
 * 招待リンクの発行と管理（管理者用）。
 *
 * <p>招待リンクの URL は<b>この画面が組み立てる</b>。サーバーは token だけを返す。
 * LAN からも Tailscale 経由でも開かれるため、サーバー自身は
 * 「外から見える自分の URL」を正しく知る手段がなく、{@code localhost} を返すと
 * 受け取った相手が開けないリンクになるため。
 *
 * @param {string} token 招待の token
 * @returns {string} 相手に送る URL
 */
function registrationUrl(token) {
    return `${window.location.origin}/register.html?token=${encodeURIComponent(token)}`;
}

/**
 * 招待の状態を表示用の HTML にする。
 *
 * @param {any} invitation 招待1件
 * @returns {string} 差し込む HTML
 */
function invitationState(invitation) {
    if (invitation.acceptedAt) {
        return statusLamp("idle", `使用済み（${invitation.acceptedUsername}）`,
            "この招待からアカウントが作られました");
    }
    if (!invitation.usable) {
        return statusLamp("failed", "期限切れ", "期限を過ぎたため、このリンクからは登録できません");
    }
    return statusLamp("live", "有効", "このリンクから登録できます");
}

/** 招待の一覧を読み込んで並べる。 */
async function loadInvitations() {
    const table = el("invitationTable");
    setBusy(table, true);
    try {
        const invitations = await apiGet("/api/admin/invitations");
        const tbody = query("#invitationTable tbody");
        tbody.replaceChildren();

        const empty = el("invitationEmpty");
        table.style.display = invitations.length === 0 ? "none" : "";
        empty.style.display = invitations.length === 0 ? "block" : "none";
        empty.innerHTML = invitations.length === 0
            ? emptyState("まだ招待を発行していません", "上のフォームから発行して、相手にリンクを送ってください")
            : "";

        for (const inv of invitations) {
            const tr = document.createElement("tr");
            tr.innerHTML = `
                <td>${escapeHtml(inv.label || "（覚え書きなし）")}</td>
                <td>${invitationState(inv)}</td>
                <td class="linkCell"></td>
                <td>${datetimeCell(inv.expiresAt)}</td>
                <td>${datetimeCell(inv.createdAt)}</td>
                <td><button data-id="${inv.id}" class="removeBtn">取消</button></td>
            `;
            // 使えない招待の token はサーバーが返さないので、リンクも出しようがない
            const linkCell = query(".linkCell", tr);
            if (inv.token) {
                linkCell.appendChild(linkField(registrationUrl(inv.token), "招待リンク"));
            } else {
                linkCell.innerHTML = '<span class="muted">—</span>';
            }
            query(".removeBtn", tr).addEventListener("click", async (ev) => {
                const btn = /** @type {HTMLButtonElement} */ (ev.currentTarget);
                if (!confirm("この招待を取り消しますか？\nまだ使われていない場合、送ったリンクは使えなくなります。")) return;
                try {
                    await apiDelete(`/api/admin/invitations/${btn.dataset.id}`);
                    clearError();
                    showToast("招待を取り消しました", "danger");
                    loadInvitations();
                } catch (e) {
                    showError(errorMessage(e));
                }
            });
            tbody.appendChild(tr);
        }
        bindDatetimeCells(tbody);
    } catch (e) {
        showError(errorMessage(e));
    } finally {
        setBusy(table, false);
    }
}

el("issueForm").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const label = inputEl("issueLabel").value.trim();
    const validDays = Number(inputEl("issueDays").value);
    const btn = /** @type {HTMLButtonElement} */ (query("button[type=submit]", el("issueForm")));
    btn.disabled = true;
    try {
        const invitation = await apiPost("/api/admin/invitations", { label, validDays });
        clearError();
        inputEl("issueLabel").value = "";

        // 発行したリンクは一覧より先に、目立つ場所へ出す（発行直後に送りたいため）
        const issued = el("issued");
        issued.style.display = "block";
        issued.replaceChildren();
        const heading = document.createElement("p");
        heading.innerHTML = `<strong>発行しました。</strong>`
            + `<span class="muted">このリンクを相手に送ってください（1回使うと無効になります）。</span>`;
        issued.append(heading, linkField(registrationUrl(invitation.token), "招待リンク"));

        showToast("招待リンクを発行しました");
        loadInvitations();
    } catch (e) {
        showError(errorMessage(e));
    } finally {
        btn.disabled = false;
    }
});

loadInvitations();
