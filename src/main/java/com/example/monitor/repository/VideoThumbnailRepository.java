package com.example.monitor.repository;

import com.example.monitor.entity.VideoThumbnail;
import org.springframework.data.jpa.repository.JpaRepository;

/** 同じ動画の画像を再取得せず、動画本体を保存しないライブラリの容量を抑える。 */
public interface VideoThumbnailRepository extends JpaRepository<VideoThumbnail, String> {}
