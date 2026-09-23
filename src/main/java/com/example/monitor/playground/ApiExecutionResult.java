package com.example.monitor.playground;

/**
 * お試し実行の結果。成功・失敗のどちらも同じ形で返し、画面はそのまま表示する。
 *
 * <p>失敗を HTTP のエラーステータスにせず本文で返すのは、API の使い方を試す場では
 * <b>エラーの内容自体が知りたい情報</b>だから。「必須パラメータが足りない」「クォータ超過」
 * といった YouTube 側の応答をそのまま読めるようにしている。
 *
 * @param success           API の呼び出しに成功したか
 * @param responseJson      応答の JSON。失敗時は {@code null}
 * @param errorMessage      失敗の理由。成功時は {@code null}
 * @param quotaCost         この実行で消費したクォータ
 * @param sessionQuotaTotal アプリ起動後にこの画面から消費したクォータの累計
 * @param elapsedMillis     呼び出しにかかった時間（ミリ秒）
 */
public record ApiExecutionResult(
        boolean success,
        String responseJson,
        String errorMessage,
        int quotaCost,
        int sessionQuotaTotal,
        long elapsedMillis
) {}
