# 動画・配信ライブラリ（2026-09-23）

## 利用方法

メニューの「動画・配信」を開き、サムネイルまたはタイトルを押すと、その画面内の公式プレーヤーで再生する。
閉じる／Escでプレーヤーを破棄し、再生を止める。配信中のダッシュボードも同じカードを使う。
チャンネル一覧とヘッダーの配信数からもサービス内の視聴画面に移動できる。

管理者は登録チャンネル全体、一般利用者は購読中のチャンネルだけを表示する。
画像URLの直接アクセスにも同じ閲覧制限を適用する。

## 保存・収集

- 保存内容：視聴URL、サムネイル画像・取得元URL、タイトル、チャンネル、公開／発見／最終観測時刻、配信状態。
- 動画本体のダウンロードや録画はこの機能では行わない。既存の自動録画設定は独立して動く。
- 通知条件・録画条件で除外された配信も視聴先を保存する。
- ライブ：既存の判定結果を利用し、追加の外部問い合わせをライブ監視ループに入れない。
- 新着動画：別スレッドで約10分間隔。YouTubeのフィードを優先し、404・500等の場合は公式uploadsプレイリストAPIへ切り替える。Twitchは公式の動画一覧APIを利用する。
- 初回収集開始時刻以降の投稿を蓄積する。開始時刻を永続化するため再起動前後の未取得動画も次回に拾う。停止中の投稿も取得範囲に含まれうる。
- APIによる再取得は前回成功から1日分を重ねる。RSSは最新分に限られるため、大量投稿や長期間の停止では全件取得を保証できない。
- 公式YouTube APIの収集用呼び出しは1日3,000回まで。太平洋時間の日付で更新し、再起動でも使用回数を保持する。1回100単位の検索APIは使わない。
- 一覧の「表示を更新」は保存済みデータの再表示であり、外部APIの収集を追加実行しない。
- サムネイルは対応サービスの画像ホストから取得し、1画像2MBまで。動画本体とは別にDBへ保存する。画像取得失敗では動画URLを消さず、次回再試行する。
- チャンネルを削除した場合は、ライブラリと画像・収集状態をDBの外部キーで連鎖削除する。購読解除では他人のデータを消さない。

## 配信終了・再生制限

判定失敗を配信終了へ変換しない。再起動直後の未確認状態も配信中として表示しない。
Twitchの配信IDをVOD IDとして使わず、APIが返す対応関係でアーカイブURLを保存する。
配信中はライブURL、終了後は取得できたVOD URLを使う。VODが無い場合は再生不可と表示する。

YouTube・Twitchの公式埋め込みを使用する。投稿者による埋め込み禁止、非公開・削除、視聴制限、配信元の障害などでは再生できない。
プレーヤーには配信元で開くリンクも用意する。Twitchは公開環境のHTTPSなど公式の埋め込み条件にも従う。

YouTubeの埋め込みに必要なRefererはiframeに限定して送信し、サイト全体のReferrer-Policyは緩めていない。
CSPのframe-srcも公式プレーヤーのホストだけに限定する。

## 追加API・データ

- `GET /api/videos`：検索・チャンネル・配信中の絞り込み、ページ分割。
- `GET /api/videos/{id}`：閲覧できる動画の詳細。
- `GET /api/videos/{id}/thumbnail`：保存済みサムネイル。
- `GET /api/videos/channels`：閲覧対象チャンネルと収集の成功／失敗・確認時刻。
- `GET /api/videos/viewer`：画面ナビゲーション用の管理者判定。
- 新規テーブル：`online_videos`、`video_thumbnails`、`video_collection_states`、`video_collection_quota`。
- 収集間隔の設定：`monitor.video-collection-interval-ms`（既定600000）。

## 検証

- `./gradlew clean build javadoc`：Javaテスト606件成功。コンパイル・JavaDocの警告なし。テスト用JVMのクラス共有警告は従来どおり1件。
- 最終調整の400/404応答はSecurityConfigTestで追加確認。
- JavaScriptの回帰テスト5件成功、`tsc --noEmit`成功。
- データの重複、初回取得の境界、再起動、判定失敗、Twitch VODへの対応付け、購読による認可、画像保存、連鎖削除、API予算上限、取得失敗時の継続をテスト。
- 全体テストで見つかった既存の並列処理テストの順序依存はテスト側だけを修正。本番の並列検知ロジックは変更していない。
- 実環境の登録20チャンネルで新着取得が成功することを確認。
- [YouTube公式サンプルの埋め込み再生](video-library-2026-09-23/youtube-player.png)を実ブラウザで確認。確認用の動画は画面上だけに指定し、本番ライブラリには登録していない。
- TwitchはURL変換・API応答・保存処理を自動テスト。実際のライブ再生は未確認。

## 参照した公式仕様

- [YouTubeのフィード](https://developers.google.com/youtube/v3/guides/push_notifications)
- [YouTube uploadsプレイリスト](https://developers.google.com/youtube/v3/docs/channels#contentDetails.relatedPlaylists.uploads)
- [playlistItems.listとクォータ](https://developers.google.com/youtube/v3/docs/playlistItems/list)
- [YouTube埋め込みのReferer・プレーヤー要件](https://developers.google.com/youtube/terms/required-minimum-functionality)
- [Twitch埋め込み](https://dev.twitch.tv/docs/embed/video-and-clips/)
- [Twitch Get Videos](https://dev.twitch.tv/docs/api/reference/#get-videos)
