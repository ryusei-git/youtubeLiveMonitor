package com.example.monitor.repository;
import com.example.monitor.entity.VideoCollectionState;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code VideoCollectionState}（チャンネルごとの動画収集の状態）の読み書き。
 *
 * <p>収集開始の境界と失敗状態を、アプリの再起動で失わないよう保存する。
 */
public interface VideoCollectionStateRepository extends JpaRepository<VideoCollectionState, Long> {}
