-- bin/ui-snapshot.sh が撮る前に確認用インスタンス（bin/sandbox.sh）の DB へ流す表示用のデータ。
--
-- 【この形にした理由】
-- 録画・配信中の動画・新人発掘の候補は、監視や yt-dlp が作るもので、作る API が無い（監視なしで起動する
-- 確認用インスタンスでは 1 件もできない）。空の画面では iPhone の大きさで崩れるかを確かめられないので、SQL で入れる。
--   - 主キーは 9001 から決め打ちにする。変更の前後で別々に起動した確認用インスタンス（DB は毎回新しい）でも
--     同じ ID になり、再生画面の URL（/my/watch/9001）と画面に出る中身がそろう。手で足した行（1 から振られる）とも重ならない
--   - 日時はすべて 2026-09 の決め打ちの過去の時刻にする。画面の「〜前」「あと〜」は撮るときにブラウザの時計を
--     2026-10-01 12:00（日本時間）に止めて計算させるので（shoot.cjs の FIXED_NOW）、撮るたびに同じ文字になる。
--     サーバー側で「今」と比べる絞り込み（配信予定は 7 日先まで・配信中は起動後に観測したもの）を通すため、
--     配信予定は実際の今より前、配信中の観測時刻（last_observed_at）は遠い未来にしてある
--   - MERGE と DELETE で毎回同じ状態に戻す。同じ確認用インスタンスで 2 回撮ったとき、1 回目の操作
--     （再生回数・視聴済み・お気に入り）が 2 回目の画面に残らないようにするため
--   - サムネイル・アイコンは入れない（外部の画像は撮るときに通信ごと止めるので、どちらにしても出ない）
-- 利用者（snapshot）は API（招待→登録）で作る。パスワードのハッシュを SQL に書かずに済むため（bin/ui-snapshot.sh）。

MERGE INTO channels (id, platform, youtube_channel_id, channel_name, channel_login, channel_icon_url,
        currently_live, current_live_video_id, consecutive_detection_failures, notification_failure_count,
        record_enabled, record_title_keywords, upcoming_video_id, upcoming_title, upcoming_scheduled_start_time,
        last_checked_at, last_detection_success_at, last_notified_video_id, last_recorded_video_id, created_at)
    KEY (id) VALUES
    (9001, 'YOUTUBE', 'UCuisnapshot0000000009001', '星宮ミナ Ch.', NULL, NULL,
        TRUE, 'snapLive001', 0, 0, TRUE, NULL, NULL, NULL, NULL,
        TIMESTAMP '2026-10-01 11:58:00', TIMESTAMP '2026-10-01 11:58:00', 'snapLive001', 'snapLive001',
        TIMESTAMP '2026-09-01 10:00:00'),
    (9002, 'YOUTUBE', 'UCuisnapshot0000000009002', '月白ルナ / Tsukishiro Luna【個人勢VTuber】', NULL, NULL,
        FALSE, NULL, 0, 0, FALSE, NULL, 'snapUpcom01', '【歌枠】秋の夜長に、しっとりバラードだけを歌う 2 時間 #月白ルナ',
        TIMESTAMP '2026-10-02 21:00:00',
        TIMESTAMP '2026-10-01 11:58:00', TIMESTAMP '2026-10-01 11:58:00', NULL, NULL,
        TIMESTAMP '2026-09-01 10:01:00'),
    (9003, 'YOUTUBE', 'UCuisnapshot0000000009003', 'ゲーム実況のあさひ', NULL, NULL,
        FALSE, NULL, 0, 0, TRUE, 'マイクラ', 'snapUpcom02', '【マイクラ】拠点づくり再開！',
        TIMESTAMP '2026-10-01 20:00:00',
        TIMESTAMP '2026-10-01 11:58:00', TIMESTAMP '2026-10-01 11:58:00', NULL, NULL,
        TIMESTAMP '2026-09-01 10:02:00'),
    (9004, 'TWITCH', '900400001', 'kanade_plays', 'kanade_plays', NULL,
        FALSE, NULL, 0, 0, FALSE, NULL, NULL, NULL, NULL,
        TIMESTAMP '2026-10-01 11:58:00', TIMESTAMP '2026-10-01 11:58:00', NULL, NULL,
        TIMESTAMP '2026-09-01 10:03:00'),
    (9005, 'YOUTUBE', 'UCuisnapshot0000000009005', 'ねむねむ ASMR', NULL, NULL,
        FALSE, NULL, 0, 0, FALSE, NULL, NULL, NULL, NULL,
        TIMESTAMP '2026-10-01 11:58:00', TIMESTAMP '2026-10-01 11:58:00', NULL, NULL,
        TIMESTAMP '2026-09-01 10:04:00');

-- 利用者（snapshot）の購読・印は消して入れ直す（1 回目に押した操作を 2 回目に持ち越さない）
DELETE FROM recording_marks WHERE user_id = (SELECT id FROM app_users WHERE username = 'snapshot');
DELETE FROM user_subscriptions WHERE user_id = (SELECT id FROM app_users WHERE username = 'snapshot');
INSERT INTO user_subscriptions (user_id, channel_id, subscribed_at, record_enabled, record_title_keywords, notify_enabled)
    SELECT u.id, c.id, TIMESTAMP '2026-09-02 09:00:00', c.record_enabled, c.record_title_keywords, c.id <> 9005
    FROM app_users u JOIN channels c ON c.id BETWEEN 9001 AND 9005
    WHERE u.username = 'snapshot';

MERGE INTO recordings (id, channel_id, video_id, video_title, genre, file_path, file_size_bytes, duration_seconds,
        play_count, thumbnail_path, status, started_at, completed_at)
    KEY (id) VALUES
    (9001, 9001, 'snapRec0001', '【雑談】月曜の夜にゆるっと話そう！最近ハマっているゲームとか、お便り読みとか #星宮ミナ', '雑談',
        'snapshot/snapRec0001.mp4', 2147483648, 7384, 3, NULL, 'COMPLETED',
        TIMESTAMP '2026-09-28 21:00:00', TIMESTAMP '2026-09-28 23:03:04'),
    (9002, 9001, 'snapRec0002', '【歌枠】リクエスト何でも歌います', '歌枠',
        'snapshot/snapRec0002.mp4', 1288490188, 5400, 12, NULL, 'COMPLETED',
        TIMESTAMP '2026-09-25 20:00:00', TIMESTAMP '2026-09-25 21:30:00'),
    (9003, 9001, 'snapRec0003', '【ASMR】耳かきと囁き', 'ASMR',
        'snapshot/snapRec0003.mp4', 3221225472, 10800, 0, NULL, 'PARTIAL',
        TIMESTAMP '2026-09-20 23:00:00', TIMESTAMP '2026-09-21 01:12:00'),
    (9004, 9002, 'snapRec0004', '【歌枠】しっとりバラード縛り', '歌枠',
        'snapshot/snapRec0004.mp4', 943718400, 3600, 1, NULL, 'COMPLETED',
        TIMESTAMP '2026-09-27 22:00:00', TIMESTAMP '2026-09-27 23:00:00'),
    (9005, 9003, 'snapRec0005', '【マイクラ】初見さん歓迎！のんびり建築', 'マイクラ',
        'snapshot/snapRec0005.mp4', 5368709120, 14400, 7, NULL, 'COMPLETED',
        TIMESTAMP '2026-09-26 19:00:00', TIMESTAMP '2026-09-26 23:00:00'),
    (9006, 9003, 'snapRec0006', '【マイクラ】エンドラ討伐', 'マイクラ',
        'snapshot/snapRec0006.mp4', 2684354560, 6000, 2, NULL, 'COMPLETED',
        TIMESTAMP '2026-09-22 19:00:00', TIMESTAMP '2026-09-22 20:40:00'),
    (9007, 9004, 'snapRec0007', 'Late night chill stream', NULL,
        'snapshot/snapRec0007.mp4', 1610612736, 4500, 0, NULL, 'COMPLETED',
        TIMESTAMP '2026-09-24 01:00:00', TIMESTAMP '2026-09-24 02:15:00'),
    (9008, 9005, 'snapRec0008', '【ASMR】雨音と一緒に眠ろう', 'ASMR',
        'snapshot/snapRec0008.mp4', 4294967296, 18000, 5, NULL, 'COMPLETED',
        TIMESTAMP '2026-09-23 23:30:00', TIMESTAMP '2026-09-24 04:30:00');

-- 視聴済み・途中まで見た・お気に入りの印が付いたカードも撮る
INSERT INTO recording_marks (user_id, recording_id, favorite, watched_at, position_seconds, position_updated_at)
    SELECT u.id, m.recording_id, m.favorite, m.watched_at, m.position_seconds, m.position_updated_at
    FROM app_users u CROSS JOIN (VALUES
        (9002, TRUE, TIMESTAMP WITH TIME ZONE '2026-09-26 22:00:00+09:00', NULL, CAST(NULL AS TIMESTAMP WITH TIME ZONE)),
        (9005, FALSE, NULL, 3600, TIMESTAMP WITH TIME ZONE '2026-09-27 20:00:00+09:00'),
        (9004, TRUE, NULL, NULL, NULL)
    ) AS m(recording_id, favorite, watched_at, position_seconds, position_updated_at)
    WHERE u.username = 'snapshot';

MERGE INTO online_videos (id, channel_id, title, watch_url, live_watch_url, thumbnail_url, thumbnail_attempts,
        thumbnail_next_attempt_at, published_at, discovered_at, last_observed_at, live, content_kind, scheduled_start_time)
    KEY (id) VALUES
    ('YOUTUBE_snapLive001', 9001, '【雑談】月曜の夜にゆるっと話そう！最近ハマっているゲームとか、お便り読みとか #星宮ミナ',
        'https://www.youtube.com/watch?v=snapLive001', NULL, NULL, 0, NULL,
        TIMESTAMP WITH TIME ZONE '2026-10-01 11:00:00+09:00', TIMESTAMP WITH TIME ZONE '2026-10-01 11:00:00+09:00',
        TIMESTAMP WITH TIME ZONE '2099-01-01 00:00:00+09:00', TRUE, 'STREAM', NULL),
    ('YOUTUBE_snapUpcom01', 9002, '【歌枠】秋の夜長に、しっとりバラードだけを歌う 2 時間 #月白ルナ',
        'https://www.youtube.com/watch?v=snapUpcom01', NULL, NULL, 0, NULL,
        TIMESTAMP WITH TIME ZONE '2026-09-30 18:00:00+09:00', TIMESTAMP WITH TIME ZONE '2026-09-30 18:00:00+09:00',
        NULL, FALSE, 'UPCOMING', TIMESTAMP WITH TIME ZONE '2026-10-02 21:00:00+09:00'),
    ('YOUTUBE_snapStrm001', 9001, '【歌枠】リクエスト何でも歌います',
        'https://www.youtube.com/watch?v=snapStrm001', NULL, NULL, 0, NULL,
        TIMESTAMP WITH TIME ZONE '2026-09-25 20:00:00+09:00', TIMESTAMP WITH TIME ZONE '2026-09-25 20:00:00+09:00',
        NULL, FALSE, 'STREAM', NULL),
    ('YOUTUBE_snapStrm002', 9003, '【マイクラ】初見さん歓迎！のんびり建築',
        'https://www.youtube.com/watch?v=snapStrm002', NULL, NULL, 0, NULL,
        TIMESTAMP WITH TIME ZONE '2026-09-26 19:00:00+09:00', TIMESTAMP WITH TIME ZONE '2026-09-26 19:00:00+09:00',
        NULL, FALSE, 'STREAM', NULL),
    ('YOUTUBE_snapUpld001', 9002, '【オリジナル曲】月のしずく / 月白ルナ',
        'https://www.youtube.com/watch?v=snapUpld001', NULL, NULL, 0, NULL,
        TIMESTAMP WITH TIME ZONE '2026-09-20 18:00:00+09:00', TIMESTAMP WITH TIME ZONE '2026-09-20 18:00:00+09:00',
        NULL, FALSE, 'UPLOAD', NULL),
    ('YOUTUBE_snapUpld002', 9005, '【ショート】おやすみの一言',
        'https://www.youtube.com/watch?v=snapUpld002', NULL, NULL, 0, NULL,
        TIMESTAMP WITH TIME ZONE '2026-09-18 23:00:00+09:00', TIMESTAMP WITH TIME ZONE '2026-09-18 23:00:00+09:00',
        NULL, FALSE, 'UPLOAD', NULL);

MERGE INTO discovery_candidates (channel_id, title, description, icon_url, subscriber_count, subscriber_hidden,
        video_count, channel_published_at, first_upload_at, found_by_term, matched_words, sample_video_id,
        sample_video_title, status, discovered_at, refreshed_at, decided_at, decided_by)
    KEY (channel_id) VALUES
    ('UCuisnapshotCandidate0001', '新人VTuber 桜井ひより', '9 月にデビューしました！歌と雑談をメインに活動しています。よろしくお願いします。',
        NULL, 1250, FALSE, 8, TIMESTAMP WITH TIME ZONE '2026-08-20 12:00:00+09:00',
        TIMESTAMP WITH TIME ZONE '2026-09-05 20:00:00+09:00', '新人VTuber', 'VTuber,デビュー', 'snapCand001',
        '【初配信】はじめまして！桜井ひよりです', 'CANDIDATE',
        TIMESTAMP WITH TIME ZONE '2026-09-29 03:00:00+09:00', TIMESTAMP WITH TIME ZONE '2026-09-30 03:00:00+09:00', NULL, NULL),
    ('UCuisnapshotCandidate0002', 'Lumi Ch. ルミ', NULL,
        NULL, NULL, TRUE, 3, TIMESTAMP WITH TIME ZONE '2026-09-10 12:00:00+09:00',
        TIMESTAMP WITH TIME ZONE '2026-09-15 21:00:00+09:00', '個人勢', 'Vtuber', 'snapCand002',
        '【自己紹介】ルミってどんな子？', 'CANDIDATE',
        TIMESTAMP WITH TIME ZONE '2026-09-28 03:00:00+09:00', TIMESTAMP WITH TIME ZONE '2026-09-30 03:00:00+09:00', NULL, NULL),
    ('UCuisnapshotCandidate0003', '夜更かし研究所', '深夜のまったり雑談配信。',
        NULL, 320, FALSE, 15, TIMESTAMP WITH TIME ZONE '2026-07-01 12:00:00+09:00',
        TIMESTAMP WITH TIME ZONE '2026-09-01 01:00:00+09:00', '新人VTuber', '新人', 'snapCand003',
        '【雑談】眠れない人あつまれ', 'CANDIDATE',
        TIMESTAMP WITH TIME ZONE '2026-09-27 03:00:00+09:00', TIMESTAMP WITH TIME ZONE '2026-09-30 03:00:00+09:00', NULL, NULL);
