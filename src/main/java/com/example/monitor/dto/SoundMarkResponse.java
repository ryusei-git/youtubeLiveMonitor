package com.example.monitor.dto;

import com.example.monitor.entity.AppUser;
import com.example.monitor.entity.SoundMark;

import java.time.Instant;

/**
 * 録画に付いた音の印 1 件。
 *
 * <p>付けた人は返さず、ログイン中の利用者の印かどうか（{@code mine}）だけを返す。
 * 画面が要るのは「自分の印なら消せる」の判定だけのため。
 *
 * @param id         印の主キー（削除に使う）
 * @param kind       種類
 * @param positionMs 録画の先頭からの位置（ミリ秒）
 * @param mine       ログイン中の利用者が付けた印か
 * @param createdAt  付けた時刻
 */
public record SoundMarkResponse(Long id, SoundMark.Kind kind, long positionMs, boolean mine, Instant createdAt) {

    /**
     * 印のエンティティから作る。
     *
     * @param mark    印
     * @param current ログイン中の利用者
     * @return レスポンス
     */
    public static SoundMarkResponse from(SoundMark mark, AppUser current) {
        return new SoundMarkResponse(mark.getId(), mark.getKind(), mark.getPositionMs(),
                mark.getUser().getId().equals(current.getId()), mark.getCreatedAt());
    }
}
