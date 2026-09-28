package com.example.monitor.repository;

import com.example.monitor.entity.Invitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 招待の保管庫。
 */
public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    /**
     * 招待リンクの token から招待を引く。
     *
     * @param token 招待リンクに載っていた文字列
     * @return 該当する招待。無ければ {@link Optional#empty()}
     */
    Optional<Invitation> findByToken(String token);

    /**
     * 発行の新しい順に全件返す。管理者の一覧表示に使う。
     *
     * @return 招待の一覧
     */
    List<Invitation> findAllByOrderByCreatedAtDesc();

    /**
     * 招待を使用済みにし、この招待で作る利用者名を書き込む。
     *
     * <p>未使用・期限内を更新条件に入れた 1 本の UPDATE にしているのは、同じ招待で同時に 2 回登録されても
     * 1 回しか通さないため。読んでから {@code save} する 2 段にすると、両方が「未使用」を読んで両方通り、
     * 1 つの招待から複数のアカウントができる。{@link AppUserRepository#resetPasswordByToken} と同じ考え方。
     *
     * <p>{@code @Transactional} を付けないのは、呼び出し側（利用者の登録）のトランザクションに入れ、
     * 利用者の作成が失敗したときに使用済みの書き込みも一緒に巻き戻すため。
     *
     * @param id       招待の主キー
     * @param username この招待で作る利用者名
     * @param now      使った時刻（期限の判定の基準にも使う）
     * @return 更新した件数。使用済み・期限切れ・取り消し済みなら 0
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Invitation i SET i.acceptedAt = :now, i.acceptedUsername = :username "
            + "WHERE i.id = :id AND i.acceptedAt IS NULL AND i.expiresAt > :now")
    int markAccepted(@Param("id") Long id, @Param("username") String username, @Param("now") LocalDateTime now);
}
