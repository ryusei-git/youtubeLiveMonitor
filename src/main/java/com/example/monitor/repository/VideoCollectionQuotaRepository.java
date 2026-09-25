package com.example.monitor.repository;
import com.example.monitor.entity.VideoCollectionQuota;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code VideoCollectionQuota}（動画収集が 1 日に使った YouTube Data API の回数）の読み書き。
 *
 * <p>収集専用の予算を、監視・通知のAPI呼び出しから分離する。
 */
public interface VideoCollectionQuotaRepository extends JpaRepository<VideoCollectionQuota, String> {}
