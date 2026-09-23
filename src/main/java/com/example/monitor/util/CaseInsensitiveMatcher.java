package com.example.monitor.util;

import java.util.Collection;
import java.util.Optional;

/**
 * 大文字小文字を無視した文字列の一致検索をまとめた共通処理。
 *
 * <p>{@code DatabaseTableService} で「利用者が指定した名前」と「DB から取得した実在の名前」を
 * 大文字小文字を無視して照合する処理が複数箇所（テーブル名の確認、カラム名の確認）で
 * 必要になったため、重複させずに独立クラスへ切り出した。
 */
public final class CaseInsensitiveMatcher {

    private CaseInsensitiveMatcher() {
    }

    /**
     * 候補の中から、大文字小文字を無視して指定した文字列と一致するものを探す。
     *
     * <p>戻り値は候補側の文字列（利用者が指定した文字列ではない）。
     * SQL に埋め込んでよいのは DB から取得した正式な表記だけ、という使い方を想定しているため、
     * 呼び出し側が誤って利用者の入力をそのまま使ってしまうのを構造的に防いでいる。
     *
     * @param candidates 検索対象の候補一覧
     * @param target     探したい文字列
     * @return 一致した候補（候補側の表記）。見つからなければ {@link Optional#empty()}
     */
    public static Optional<String> findIgnoreCase(Collection<String> candidates, String target) {
        return candidates.stream()
                .filter(candidate -> candidate.equalsIgnoreCase(target))
                .findFirst();
    }
}
