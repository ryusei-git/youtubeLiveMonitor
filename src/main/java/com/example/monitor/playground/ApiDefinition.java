package com.example.monitor.playground;

import java.util.List;

/**
 * お試し実行できる API 1件分の定義。画面の一覧と入力フォームはこの定義から組み立てる。
 *
 * @param id          API の識別子。{@code videos.list} のように YouTube Data API の呼び名に合わせる
 * @param name        画面に出す名前
 * @param description 何が取れるかの短い説明
 * @param quotaCost   1回の実行で消費するクォータ。実行前に画面へ出して注意を促すために持つ
 * @param parameters  入力欄の定義
 */
public record ApiDefinition(
        String id,
        String name,
        String description,
        int quotaCost,
        List<ApiParameter> parameters
) {}
