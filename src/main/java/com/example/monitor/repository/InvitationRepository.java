package com.example.monitor.repository;

import com.example.monitor.entity.Invitation;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
