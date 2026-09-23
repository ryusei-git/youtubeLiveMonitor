package com.example.monitor.util;

import org.springframework.data.domain.PageRequest;

/** 一覧APIの件数制限と、JPAが扱えないオフセットの検証を共有する。 */
public final class PageRequestUtils {
    private PageRequestUtils() { }

    /**
     * 大量取得とオフセットの整数範囲超過を、DB問い合わせより前に拒否する。
     * @param page 0から始まるページ番号
     * @param size 要求件数
     * @param maxSize APIごとの最大件数
     * @return 検証済みページ指定
     * @throws IllegalArgumentException 入力が許容範囲外の場合
     */
    public static PageRequest bounded(int page, int size, int maxSize) {
        if (page < 0) throw new IllegalArgumentException("ページ番号は0以上で指定してください");
        if (size < 1 || size > maxSize) {
            throw new IllegalArgumentException("件数は1〜" + maxSize + "で指定してください");
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("ページ番号が大きすぎます");
        }
        return PageRequest.of(page, size);
    }
}
