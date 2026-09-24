package com.example.monitor.util;

import org.springframework.data.domain.Sort;

/**
 * 録画一覧 API の検索パラメータ（並び順・視聴状態）の解釈。
 *
 * <p>管理者の {@code /api/recordings} と利用者の {@code /api/my/recordings} で共有する。
 * 各コントローラーに持たせると、受け付ける値やエラーの文言が片方だけ変わり、
 * 同じ画面部品が片方の API でだけ 400 になる、という食い違いが起きるため。
 *
 * <p>知らない値は黙って既定に寄せず 400（{@link IllegalArgumentException}）にする。
 * 画面は URL に条件を残すため、黙って読み替えると URL と表示が食い違い、
 * なぜその並び・件数なのか分からなくなる。
 */
public final class RecordingSearchParams {

    private RecordingSearchParams() { }

    /**
     * 並び順の名前を {@link Sort} に変える。
     *
     * <p>同じ値どうしの順番は開始時刻の新しい順 → 主キーの大きい順で決める。決めておかないと
     * ページをまたいだときに同じ録画が 2 回出たり抜けたりする。
     * 長さ・サイズが {@code null}（録画中・失敗）の録画は降順で末尾に来る。H2 は {@code null} を
     * 最小値として並べるため（Spring Data の {@code nullsLast()} は {@code @Query} では効かない）。
     *
     * @param sort 並び順の名前（{@code newest} / {@code oldest} / {@code longest} / {@code largest}）
     * @return 対応する並び順
     * @throws IllegalArgumentException 知らない名前の場合（400）
     */
    public static Sort toSort(String sort) {
        Sort newest = Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"));
        return switch (sort) {
            case "newest" -> newest;
            case "oldest" -> Sort.by(Sort.Order.asc("startedAt"), Sort.Order.asc("id"));
            case "longest" -> Sort.by(Sort.Order.desc("durationSeconds")).and(newest);
            case "largest" -> Sort.by(Sort.Order.desc("fileSizeBytes")).and(newest);
            default -> throw new IllegalArgumentException(
                    "sort は newest / oldest / longest / largest のいずれかで指定してください: " + sort);
        };
    }

    /**
     * 視聴状態の指定を検索条件に変える。
     *
     * @param watched {@code unwatched} / {@code watched} / {@code null}
     * @return 視聴済みだけなら {@code true}、未視聴だけなら {@code false}、絞らないなら {@code null}
     * @throws IllegalArgumentException 知らない値の場合（400）
     */
    public static Boolean toWatchedFilter(String watched) {
        if (watched == null) {
            return null;
        }
        return switch (watched) {
            case "watched" -> true;
            case "unwatched" -> false;
            default -> throw new IllegalArgumentException(
                    "watched は watched / unwatched のいずれかで指定してください: " + watched);
        };
    }
}
