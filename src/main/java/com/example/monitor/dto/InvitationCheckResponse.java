package com.example.monitor.dto;

/**
 * 招待リンクが今使えるかどうかの確認結果。登録画面を開いた時点で表示を切り替えるために使う。
 *
 * <p>使えない理由まで返すのは、招待された側が<b>次にどうすればよいか判断できる</b>ようにするため
 * （期限切れなら管理者に再発行を頼む、使用済みならそのままログインする）。
 * token は推測できない長さの乱数なので、存在の有無を答えても総当たりの助けにはならない。
 *
 * @param usable このリンクから登録できるか
 * @param reason 使えない場合の理由。使える場合は {@code null}
 */
public record InvitationCheckResponse(boolean usable, String reason) {

    /** 使える招待を表す結果を返す。
     *  <p>レコードの項目名 {@code usable} とメソッド名が衝突するため別の名前にしている。
     *  @return 結果 */
    public static InvitationCheckResponse allowed() {
        return new InvitationCheckResponse(true, null);
    }

    /**
     * 使えない招待を表す結果を返す。
     *
     * @param reason 画面に出す理由
     * @return 結果
     */
    public static InvitationCheckResponse rejected(String reason) {
        return new InvitationCheckResponse(false, reason);
    }
}
