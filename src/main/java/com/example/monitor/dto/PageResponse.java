package com.example.monitor.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * ページングされた一覧を API のレスポンスとして返す形。
 *
 * <h2>なぜ {@link Page} をそのまま返さないのか</h2>
 * Spring Data の {@link Page}（実体は {@code PageImpl}）をそのままレスポンスにすると、
 * 起動時に「{@code Serializing PageImpl instances as-is is not supported}」という警告が出る。
 * <b>JSON の構造が Spring 内部のクラス構造そのままになり、バージョンアップで黙って変わりうる</b>
 * ためで、実際 {@code pageable} や {@code sort} といった入れ子は Spring の都合で決まっている。
 * 「エンティティを API に直接返さず {@code dto} のレスポンス型に詰め替える」という
 * このプロジェクトの方針からも外れていた。
 *
 * <h2>なぜ {@code VIA_DTO} ではなく自前の型なのか</h2>
 * Spring が用意する {@code @EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)} でも
 * 警告は消せるが、あれは JSON を {@code {content, page:{totalPages,...}}} という
 * <b>入れ子構造に変えてしまう</b>。画面側（{@code recordings.js} / {@code notifications.js} /
 * {@code player.js}）は {@code content} / {@code totalPages} / {@code totalElements} を
 * トップレベルで読んでいるため、全ページの追従が必要になる。
 * この型はそれらの名前と意味を従来どおり保つことだけを目的にしている。
 *
 * <p>逆に {@code pageable} と {@code sort} は載せていない。Spring 内部の型を
 * そのまま露出したもので、まさに上記の警告が指している「壊れやすい部分」そのものだから
 * （画面はどちらも参照していない）。
 *
 * @param <T>              1 件分のレスポンス型
 * @param content          このページに含まれる要素
 * @param number           現在のページ番号（0 始まり）
 * @param size             1 ページあたりの要求件数
 * @param totalElements    ページングを無視した全体の件数
 * @param totalPages       全体のページ数
 * @param first            先頭ページなら {@code true}
 * @param last             最終ページなら {@code true}
 * @param numberOfElements このページに実際に含まれる件数（最終ページでは {@link #size} より少なくなる）
 * @param empty            このページに 1 件も無ければ {@code true}
 */
public record PageResponse<T>(
        List<T> content,
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        int numberOfElements,
        boolean empty
) {

    /**
     * Spring Data のページからレスポンスを組み立てる。
     *
     * <p>詰め替えはこのメソッドに集約している。各コントローラーで項目を並べ直すと、
     * 画面が依存している項目名が API ごとにずれる余地ができるため。
     *
     * @param page 変換元のページ
     * @param <T>  1 件分のレスポンス型
     * @return 変換後のレスポンス
     */
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                page.getNumberOfElements(),
                page.isEmpty()
        );
    }
}
