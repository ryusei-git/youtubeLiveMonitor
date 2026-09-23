package com.example.monitor.repository;
import com.example.monitor.entity.VideoCollectionQuota;
import org.springframework.data.jpa.repository.JpaRepository;

/** 収集専用の予算を、監視・通知のAPI呼び出しから分離する。 */
public interface VideoCollectionQuotaRepository extends JpaRepository<VideoCollectionQuota, String> {}
