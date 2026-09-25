# YouTube Live Monitor

YouTube と Twitch のライブ配信を監視し、配信開始時に Discord へ自動通知するサービスです。
（YouTube 専用として作り始めたため、名前はそのままになっています。）

**目的**: Java・Spring Boot・API 統合の学習プロジェクト

## 技術スタック

- **言語**: Java 21
- **フレームワーク**: Spring Boot 3.3.3
- **ビルドツール**: Gradle
- **DB**: H2（ファイルモード）+ Spring Data JPA
- **CLI**: Picocli
- **ライブラリ**:
  - google-api-services-youtube（YouTube API クライアント）
  - discord-webhooks（Discord 通知）
  - jsoup（配信中判定のための HTML 解析）
  - yt-dlp（配信の録画。Java 製で継続的なライブ配信録画に対応するライブラリが存在しないため、
    外部プロセスとして起動している。アプリのソースコード自体は 100% Java）
- **対応プラットフォーム**: YouTube / Twitch
  （Twitch はクライアントライブラリを使わず、`java.net.http.HttpClient` で Helix API を直接呼んでいる）

## 配信を検知する仕組み

### YouTube

「このチャンネルが今配信中か」を公式 API（`search.list`）で調べると 1 回あたり 100 クォータを消費し、
10 チャンネルを 1 時間おきに調べるだけで 1 日の上限（既定 10,000）を超えてしまいます。

そこで本アプリでは 2 段構えにしています。

| 段階 | 手段 | クォータ | 頻度 |
|---|---|---|---|
| 配信中かどうかの検知 | `channel/{id}/live` の canonical タグを解析 | 0 | 毎サイクル |
| 通知に使う詳細情報の取得 | YouTube API `videos.list` | 1 | 検知した瞬間のみ |
| チャンネル名からの検索 | YouTube API `search.list` | 100 | 手動実行時のみ |

前者は YouTube が公式に保証した仕様ではないため、HTML 構造が変われば動かなくなります。
その場合は **「配信していない」ではなく「判定できなかった」として扱い**、配信状態は書き換えません。
両者を同じ扱いにすると、検知が壊れていても画面上は「誰も配信していない」平常運転に見えてしまうためです。
判定に連続で失敗しているチャンネルはダッシュボードに警告として表示されます。

`/live` の canonical は配信中の動画だけでなく、**配信開始前の「待機所」ページにも
向く**ことがあります。そのままでは何日も前から「配信中」と誤検知してしまうため、
埋め込みJSON内の `isUpcoming` フラグで待機所かどうかも追加で判定しています。

詳細は `LiveStreamDetector` の JavaDoc を参照してください。

### Twitch

Twitch には YouTube のようなクォータ制限がないため、**公式 API をそのまま毎サイクル呼べます**。
HTML 解析のような回避策は要りません。

| 段階 | 手段 | 制限 |
|---|---|---|
| 配信中かどうかの検知 | Helix API `/helix/streams` | 1 リクエストで最大 100 チャンネル |
| 通知に使う詳細情報の取得 | Helix API `/helix/streams?user_id=` | クォータ消費なし |

`/helix/streams` は**配信中のチャンネルしか返しません**。応答に含まれない ID は
「配信していない」という意味ですが、**通信自体に失敗した場合も見た目は同じ**になります。
これを取り違えないよう、問い合わせに失敗したときは全件を「判定できなかった」として扱います。

詳細は `TwitchStreamPlatform` の JavaDoc を参照してください。

### プラットフォームを増やすには

`AbstractStreamPlatform` を継承して `@Component` を付けたクラスを作るだけです。
`StreamPlatformRegistry` が Spring 経由で自動的に拾うため、登録用の一覧に書き足す必要はありません。
録画・通知・履歴・ディスク使用量の集計はプラットフォームに依存しない作りになっています。

## プロジェクト構成

```
YouTubeLiveMonitor/
├── bin/
│   └── service.sh                            # 起動・停止・状態確認コマンド
├── src/main/java/com/example/monitor/
│   ├── YouTubeLiveMonitorApplication.java    # 起動クラス（サービス/CLI の分岐）
│   ├── scheduler/
│   │   └── LiveStreamPollingScheduler.java   # 監視ループ本体
│   ├── service/
│   │   ├── LiveStreamDetector.java           # 配信中かの検知（クォータ消費なし）
│   │   ├── YouTubeApiClient.java             # YouTube API の呼び出し窓口
│   │   ├── NotificationDispatcher.java       # 通知の送出と結果の判定
│   │   ├── NotificationHistoryService.java   # 通知履歴の記録・照会
│   │   ├── MonitoredChannelService.java      # チャンネルの登録・削除（API/CLI 共通）
│   │   ├── ChannelLogReader.java             # ログファイルの読み取り・構造化
│   │   ├── DashboardService.java             # ダッシュボードの集計
│   │   ├── DatabaseTableService.java         # DB テーブルの閲覧・編集
│   │   ├── UptimeTracker.java                # 稼働時間の記録
│   │   ├── StreamRecorder.java                # 配信の録画（yt-dlp を外部プロセスとして起動）
│   │   ├── RecordingHistoryService.java      # 録画履歴の記録・検索
│   │   ├── RecordingFileService.java         # 録画ファイルの削除・使用量集計
│   │   ├── RecordingReconciler.java          # 録画状態の補正とサムネイルの後追い生成
│   │   ├── VideoMetadataExtractor.java       # 録画ファイルからの再生時間・サムネイル抽出（ffprobe/ffmpeg）
│   │   └── ProcessLauncher.java / DefaultProcessLauncher.java  # プロセス起動のテスト容易化用の窓口
│   ├── entity/                               # DB のテーブルに対応する型
│   ├── repository/                           # DB アクセス
│   ├── controller/                           # REST API
│   ├── cli/                                  # CLI コマンド
│   ├── dto/                                  # 層をまたいで受け渡す型
│   ├── exception/                            # 独自例外
│   ├── notification/
│   │   └── DiscordNotifier.java              # Discord への送信
│   └── config/
│       ├── MonitorProperties.java            # 独自設定値のまとめ
│       ├── YouTubeApiConfig.java             # YouTube クライアントの組み立て
│       └── HttpClientConfig.java             # HttpClient の組み立て
├── src/main/resources/
│   ├── application.yml                       # Spring Boot 設定
│   ├── logback-spring.xml                    # ログ出力設定（チャンネル別振り分け）
│   └── static/                               # 画面（素のHTML+JS、フレームワーク不使用）
│       ├── index.html / channels.html / notifications.html / logs.html / tables.html
│       ├── recordings.html / player.html     # 録画のギャラリーと再生画面
│       ├── css/style.css                     # 共通スタイル（最小限）
│       └── js/                               # 各画面のロジック + common.js（fetchラッパー等）
├── build.gradle                              # Gradle ビルド設定
├── .env.example                              # 環境変数テンプレート
└── README.md                                 # このファイル
```

各クラスの役割と設計判断の理由は JavaDoc に書かれています。

```bash
./gradlew javadoc
```

生成先は `build/docs/javadoc/index.html` です。

## セットアップ

### 前提条件

- Java 21 以上
- Google Cloud Console アカウント
- Discord サーバー管理権限（Webhook 作成用）
- 録画機能を使う場合のみ: `yt-dlp` と `ffmpeg`（後述）

### 1. リポジトリのクローン

```bash
cd ~/youtubeLiveMonitor
```

### 2. 環境変数の設定

```bash
cp .env.example .env
```

`.env` ファイルを編集して、以下の情報を設定してください：

#### YouTube API キーの取得

1. [Google Cloud Console](https://console.cloud.google.com/) にアクセス
2. 新しいプロジェクトを作成
3. YouTube Data API v3 を有効化
4. API キーを生成
5. `.env` の `YOUTUBE_API_KEY` に貼り付け

#### Discord Webhook URL の取得

1. Discord サーバーの設定を開く
2. 「ウェブフック」を選択
3. 「ウェブフックを作成する」をクリック
4. URL をコピー
5. `.env` の `DISCORD_WEBHOOK_URL` に貼り付け

#### Twitch の Client ID / Client Secret の取得（Twitch を監視する場合）

**YouTube だけを使うなら不要です。**未設定でもアプリは起動し、YouTube の監視には一切影響しません。

1. Twitch アカウントで**二要素認証を有効にする**（`設定 → セキュリティとプライバシー → 二要素認証`）。
   電話番号の登録が必要で、これを済ませないとアプリ登録が拒否されます
2. 有効化したら**一度ログアウトして入り直す**。セッションに「2FA 済み」が記録されるのはログイン時のため、
   これをしないと有効にしても拒否され続けます
3. [Twitch Developer Console](https://dev.twitch.tv/console) で「アプリケーションを登録」
   - **OAuth リダイレクト URL**: `http://localhost:8080`（本アプリでは使いませんが入力必須）
   - **カテゴリー**: 任意（API の使用可否に影響しません）
   - **クライアントタイプ**: **機密（Confidential）** ← 公開を選ぶと Client Secret が発行されません
4. Client ID をコピーし、「新しい秘密鍵」で Client Secret を発行（**表示は 1 回きり**）
5. `.env` の `TWITCH_CLIENT_ID` / `TWITCH_CLIENT_SECRET` に貼り付け
   （ダッシュボードの「現在の設定」からも入力できます）

権限（スコープ）の選択欄はありません。この方式（Client Credentials Grant）で得られるトークンは
公開データ専用で、本アプリが使う「配信中かどうか」と「チャンネル名 → ユーザー ID の解決」は
どちらもスコープ不要です。配信者本人の許可なしに監視できます。

#### yt-dlp のインストール（録画機能を使う場合）

録画機能はチャンネルごとに `録画する` を有効にした場合のみ動作します。無効なチャンネルだけを
使うのであればインストール不要です。

```bash
python3 -m pip install --user --break-system-packages yt-dlp
```

`ffmpeg` も別途必要です（映像・音声の結合と、一覧に出すサムネイル・再生時間の取り出しに使用）。
Debian/Ubuntu 系なら次の通りです。`ffprobe` は `ffmpeg` パッケージに同梱されています。

```bash
sudo apt install ffmpeg
```

サムネイルは録画ファイルと同じ場所に `{動画ID}.jpg` として作られます（再生時間の 10% の位置を
切り出したもの）。作成は巡回処理のついでに後追いで行われるため、録画完了の直後は
「サムネイル生成待ち」と表示されることがあります。取り出しに失敗しても録画の再生には影響しません。

### 3. 依存関係のインストール

```bash
./gradlew build
```

### 4. アプリケーション起動

```bash
bin/service.sh start
```

停止・再起動・状態確認も同じスクリプトで行います。

```bash
bin/service.sh status
```

`stop` はプロセスが完全に終了してポートが解放されるまで待ってから完了するため、
停止直後に起動してもポートの取り合いになりません。

## 使用方法

### 監視対象チャンネルの登録

チャンネル ID が分かっている場合はそのまま登録します。

```bash
java -jar build/libs/youtubeLiveMonitor-0.1.0.jar channel add -i UCxxxxxxxxxxxxxxxxxxxxxx -n "配信者名"
```

`-i` にはチャンネル ID のほか、**ハンドル（`@foo`）やチャンネルページの URL をそのまま**渡せます。
URL の場合は自動で本来のチャンネル ID に解決されます。

```bash
java -jar build/libs/youtubeLiveMonitor-0.1.0.jar channel add -i "https://www.youtube.com/@foo" -n "配信者名"
```

Twitch を監視する場合は `-p TWITCH` を付けます（省略時は YouTube）。
`-i` には URL か、`twitch.tv/` の後ろに出ているチャンネル名を渡します。

```bash
java -jar build/libs/youtubeLiveMonitor-0.1.0.jar channel add -p TWITCH -i "https://www.twitch.tv/foo" -n "配信者名"
```

チャンネル名は配信者本人が変更できるため、登録時に**変更されない数値のユーザー ID へ自動で解決**して
保存します。名前のまま保存すると、改名された瞬間に「ずっとオフライン」と判定し続けて
気づけなくなるためです。

配信を検知したら自動録画したい場合は `-r` を付けます（`yt-dlp` のインストールが必要）。
`-k` で録画対象をタイトルで絞り込むこともできます。

```bash
java -jar build/libs/youtubeLiveMonitor-0.1.0.jar channel add -i UCxxxxxxxxxxxxxxxxxxxxxx -n "配信者名" -r -k "ASMR,生配信"
```

登録済みチャンネルの録画設定は後からでも切り替えられます。`-i` に指定する ID は
`channel list` の先頭列の値で、YouTube のチャンネル ID ではありません。

```bash
java -jar build/libs/youtubeLiveMonitor-0.1.0.jar channel record -i <id> --on
java -jar build/libs/youtubeLiveMonitor-0.1.0.jar channel record -i <id> --off
```

配信タイトルで対象を絞り込むこともできます。YouTuber がタイトル先頭に付ける
「【ASMR】」「【生配信】」のような角括弧タグを使い、カンマ区切りでキーワードを指定すると、
いずれか1つでもタイトルに含まれる配信だけが対象になります（大文字小文字は区別しません）。

**Twitch では配信のカテゴリも判定材料になります。** Twitch にはカテゴリという独立した項目があり、
内容の申告はそちらに寄るためです。実際に Twitch の「ASMR」カテゴリで配信中の10人を調べたところ、
**2人はタイトルに ASMR を含んでいませんでした**（例:「IM SLEEPING【SUKITHON DAY 19】」）。
タイトルだけを見ているとこうした配信を取りこぼすため、タイトルとカテゴリのどちらかに
含まれていれば対象としています。YouTube には配信ごとのカテゴリ欄が無いため、タイトルのみで判定します。

**このフィルターは通知と録画の両方に効きます。** 指定したキーワードがタイトルにもカテゴリにも
無い配信は Discord へ通知されません。対象外と判断した時点で打ち切るため、その配信では
詳細取得（`videos.list`、クォータ1）も消費しません。

判定材料のタイトルは配信検知と同じ HTML から読んでいるため、毎巡回で評価し直されます。
配信の途中でタイトルにタグが足された場合は、次の巡回で対象になって通知が飛びます。

なお、フィルターを設定していてタイトルが取得できなかった場合は「タグが入っている」と
確認できないため通知を見送りますが、これは YouTube 側の構造変更でタイトル抽出だけが
壊れた可能性もあるため、**警告としてログに残します**（黙って通知が止まらないようにするため）。
`-k ""` のように空文字を指定すると絞り込みを解除できます。画面(`channels.html`)からは
チャンネル一覧の「フィルター」欄をダブルクリックして設定できます。

```bash
java -jar build/libs/youtubeLiveMonitor-0.1.0.jar channel record -i <id> --on -k "ASMR,生配信"
```

チャンネル ID が分からない場合は名前から検索できます（100 クォータを消費するので多用しないこと）。

```bash
java -jar build/libs/youtubeLiveMonitor-0.1.0.jar channel search -n "配信者名"
```

一覧の確認と削除は次のとおりです。`remove` で指定する ID は一覧の先頭列の値で、
YouTube のチャンネル ID ではありません。

```bash
java -jar build/libs/youtubeLiveMonitor-0.1.0.jar channel list
```

サービスが常駐している最中でも CLI は実行できます。

### 画面

サービス起動中にブラウザで `http://localhost:8080/` を開くと使えます。

| 画面 | URL | 内容 |
|---|---|---|
| ダッシュボード | `/index.html` | 主要指標のタイル・内訳グラフ（チャンネル状態／録画状態／ディスク使用量）・チャンネル別使用量の横棒・検知失敗の警告・設定の変更（APIキー・Webhook・Twitch認証情報を含む）・今すぐチェック |
| チャンネル管理 | `/channels.html` | 一覧・登録（プラットフォーム選択／URL貼り付け可）・削除・名前検索（YouTubeのみ）・録画設定・今すぐチェック |
| 通知履歴 | `/notifications.html` | 送信結果の一覧（失敗理由も表示） |
| 録画 | `/recordings.html` | 録画のサムネイル付きギャラリー・検索（タイトル/チャンネル名のキーワード、チャンネル、録画状態）・削除・ディスク使用量（チャンネル別）・削除済みチャンネルの録画の一括削除 |
| 再生 | `/player.html?id=` | 録画 1 本の再生画面。録画の詳細情報と、同じチャンネルの他の録画も並ぶ |
| ログ | `/logs.html` | チャンネル別・システムログの閲覧。レベルでの絞り込み（選択肢はそのログに実在する値のみ） |
| DB管理 | `/tables.html` | テーブルの閲覧・セルのダブルクリックでの直接編集。テーブル名・カラム名は論理名（日本語）で表示し、カーソルを当てると物理名（DB上の実際の名前）を確認できる |

素の HTML・CSS・JavaScript のみで作られており（フレームワーク不使用）、上記の REST API を
`fetch` で叩いているだけです。npm や webpack のようなフロントエンドのビルドは必要ありません。

ただし `src/main/resources/static/` 配下は `./gradlew build` で jar に**パッケージされる**ため、
画面を編集したら次のようにビルドを挟んでから再起動してください
（`bin/service.sh restart` だけでは今ある jar を再起動するだけで、編集内容は反映されません）。

```bash
./gradlew build -x test && bin/service.sh restart
```

静的リソースには `Cache-Control: no-cache` を付けています（`application.yml`）。
ブラウザは毎回サーバへ問い合わせ、変更が無ければ 304 が返るため、
画面を更新したのに古い内容が表示される、という状態になりません。
録画ファイル（`/recordings/**`）はこの設定の対象外で、キャッシュがそのまま効きます。

### 画面の見た目

濃紺を基調にした業務システム向けの配色で、次の方針で統一しています。

- 角丸は 2px まで、影はほぼ使わない。要素の境界は線で示す
- アクセントはヘッダ下の金のヘアライン1本のみ。状態表示（録画中・失敗）以外で色を足さない
- 文字サイズではなく太さと字間の差で階層を作り、情報密度を落とさない
- 数字は等幅（`tabular-nums`）に揃える

色は `css/style.css` 冒頭の CSS カスタムプロパティに集約しているので、
配色を変えたい場合はそこだけ触れば全画面に反映されます。

### 設定の変更

ダッシュボードの「現在の設定」から、YouTube APIキー・Discord Webhook URL・監視間隔・
録画の保存先・録画の画質上限を変更できます。

- **APIキー・Webhook URL** は入力欄です。空欄のまま保存すると変更しません
  （画面には値そのものを表示しない設計のため。設定済みかどうかだけプレースホルダーで示します）
- **監視間隔・画質上限** はプルダウンです。`.env` を直接編集して選択肢に無い値にしている場合、
  その値が選択肢の末尾に追加されて選択された状態になります（黙って別の値に変わらないように）
- **録画の保存先** は入力欄と「参照...」ボタンの組み合わせです。「参照...」を押すと
  **サーバー上のプロセスから OS 本来のフォルダ選択ダイアログ**（Linux は `zenity`、
  Windows は `FolderBrowserDialog`）を起動し、選ばれた絶対パスを入力欄へ反映します。
  ブラウザのネイティブなフォルダ選択（`<input type="file" webkitdirectory>` 等）は
  アップロード用途専用で絶対パスを取得できないため、この方式を取っています。

  **この機能はブラウザとサーバーが同じマシン上にある場合だけ使えます。** ダイアログは
  サーバー側の画面に表示されるため、Tailscale 等でリモートから使っている場合は
  ダイアログが見えず操作できません。その場合は入力欄に直接パスを入力してください。
  Linux で `zenity`（多くのディストリビューションに標準で入っています）が
  インストールされていない環境でも同様に、入力欄への直接入力で代替できます。

**保存しても実行中のアプリにはすぐ反映されません。** 設定は起動時に一度だけ `.env` から
読み込まれる仕組みのため（`MonitorProperties`参照）、保存後は次のコマンドで再起動してください。

```bash
bin/service.sh restart
```

`.env` はコメントを保ったまま該当行だけを書き換えます（無ければ末尾に追記）。ファイルが
存在しない場合は `.env.example` を土台に新規作成します。

### ダッシュボードのグラフ

チャート用ライブラリは使わず、**素の SVG を組み立てています**。必要なのはドーナツと横棒だけで
依存を増やす理由が無く、色を CSS カスタムプロパティで指定できるため
`style.css` の配色変更にそのまま追従するのが利点です（ライブラリを使うと既定の配色を
全部上書きすることになります）。描画処理は `common.js` の `donutChart()` / `barChart()` にあります。

配色の決まりは次の通りです。内訳は濃紺の濃淡だけで表し、色相は増やしません。

| 色 | 意味 |
|---|---|
| 濃紺の濃淡（`--chart-1`〜`--chart-5`） | 内訳の区分。濃いほど値が大きい |
| 金（`--chart-active`） | 進行中（配信中・録画中） |
| 赤（`--chart-failure`） | 異常（録画失敗） |

**ドーナツの区画は互いに重なってはいけません**（合計が全体と一致すること）。
たとえばチャンネル状態のドーナツを「配信中／休止中／判定失敗」の3区分にすると、
判定失敗のチャンネルは配信中か休止中にも数えられているため合計がチャンネル数を超え、
面積の比が意味を失います。そのため区画は「配信中／休止中」だけにし、
判定失敗は上部の警告欄が担当しています。

ディスク使用量は**同じ内訳をドーナツと横棒の両方**で出しています。
円グラフは3〜5項目までしか判別できないため、チャンネルが増えたときに
「順位と差」を読む手段として横棒を併置しています。

なお、通知数の推移や時間帯別の配信傾向といった時系列グラフは実装していません。
実装が難しいからではなく、履歴が溜まるまではほぼ空のグラフになるためです。

### 画面スクリプトの型チェック

JavaScript は素のまま（TypeScript は使わない）ですが、各ファイル先頭の `// @ts-check` と
`static/jsconfig.json` により、**ビルド手順を増やさずに**エディタ上で型チェックが効きます。
型は JSDoc で書きます。サーバのレスポンス型は `common.js` の `@typedef` に定義してあり、
Java の `dto` パッケージと対になっています（片方を変えたらもう片方も直すこと）。

コマンドラインで確認する場合は次の通りです。

```bash
cd src/main/resources/static && npx -y -p typescript tsc -p jsconfig.json
```

### 画面の共通の操作

- **補足説明はホバーで表示**: 見出しやラベルの横にある `ⓘ` にカーソルを当てると説明が出ます。
  常時表示せずホバー式にしているのは画面を広く使うためです。
- **チャンネル名をクリックするとチャンネルIDが表示**: 一覧では基本的にチャンネル名だけを表示し、
  CLI コマンドなどでチャンネルIDが必要になったときだけ名前をクリックして確認します（もう一度
  クリックで隠れます）。
- **日時はクリックで精密表示に切り替え**: 一覧の日時は既定で秒までの表示（例:
  `2026-09-13 20:24:01`）にしており、クリックすると秒未満の精度を含む元の値に切り替わります。

### REST API

| メソッド | パス | 説明 |
|---|---|---|
| GET | `/api/dashboard` | 監視状況の集計（配信中のチャンネル、通知件数と失敗件数、検知失敗の警告、録画の状態別件数など） |
| GET | `/api/settings` | 現在有効な設定値（APIキー等は設定有無のみ、値は返さない） |
| PUT | `/api/settings` | 設定値を`.env`へ保存（反映には再起動が必要。後述） |
| POST | `/api/settings/directories/pick?initialDirectory=` | OSのフォルダ選択ダイアログを起動し、選ばれたパスを返す（キャンセル時は204） |
| POST | `/api/monitor/check` | 次の巡回を待たずに今すぐ全チャンネルをチェック（実行中なら 409） |
| GET | `/api/platforms` | 対応している配信プラットフォームの一覧（登録画面の選択肢。認証情報の設定有無も返す） |
| GET | `/api/channels` | 監視対象の一覧 |
| POST | `/api/channels` | 監視対象の登録（`platform` は省略可、既定は `YOUTUBE`） |
| PUT | `/api/channels/{id}/record` | 録画設定の ON/OFF 切り替え |
| PUT | `/api/channels/{id}/record-title-filter` | 録画対象を絞り込むタイトルキーワードの更新 |
| DELETE | `/api/channels/{id}` | 監視対象の削除（通知履歴も一緒に消える） |
| GET | `/api/channels/search?name=` | チャンネル名から検索（**YouTube のみ**。1回100クォータ消費） |
| GET | `/api/notifications` | 通知履歴（失敗した試行も含む） |
| GET | `/api/recordings?keyword=&status=&channelId=` | 録画履歴（録画中・失敗も含む）。`keyword` は配信タイトルとチャンネル名の部分一致 |
| GET | `/api/recordings/{id}` | 録画履歴 1 件（再生画面用） |
| DELETE | `/api/recordings/{id}` | 録画履歴と録画ファイルの削除（録画中は409） |
| GET | `/api/recordings/disk-usage` | 録画ディレクトリの使用量（合計・チャンネル別、登録有無フラグ付き） |
| GET | `/api/recordings/orphaned/preview` | 孤立した録画ファイル（録画履歴に動画 ID が無いもの）の削除候補と確認トークン。ファイルは変更しない（録画中は対象外） |
| DELETE | `/api/recordings/orphaned/confirmed?token=` | プレビューで確認した削除候補だけをファイル単位で削除（確認後に対象が変わっていれば 400） |
| GET | `/recordings/**` | 録画ファイル本体の配信（静的リソース、HTTP Range 対応） |
| GET | `/api/logs/channels` | ログがあるチャンネルの一覧 |
| GET | `/api/logs/channels/{channelId}?limit=&level=` | チャンネル別ログ（`level` でレベル絞り込み） |
| GET | `/api/logs/system?limit=&level=` | システムログ（`level` でレベル絞り込み） |
| GET | `/api/admin/tables` | DB のテーブル一覧 |
| GET | `/api/admin/tables/{name}` | テーブルの内容 |
| PUT | `/api/admin/tables/{name}/{pk}` | 行の更新 |

`/api/admin/tables` 以下は任意のテーブルを書き換えられます。認証を持たない個人用ツールを
前提とした機能なので、外部からアクセスできる環境には置かないでください。

### ログ

監視ログはチャンネルごとに分かれて出力されます。

```bash
ls logs/channels/
```

特定のチャンネルだけを追いたいときは、そのファイルを見れば他チャンネルの出力に埋もれません。

録画と手動ダウンロードの yt-dlp の出力は、動画ごとに `logs/yt-dlp/<動画ID>.log` に出ます（進捗は出しません）。
録画・ダウンロードが失敗・途中で終わったときの原因はここを見ます。

## 開発・学習

このプロジェクトは **学習目的** で設計されています。

詳細は [.claude/CLAUDE.md](.claude/CLAUDE.md) を参照してください。

### 実装順序

実装済み・未実装は次のとおりです。

- 済: 配信の検知、Discord 通知、重複通知の防止、失敗時の自動リトライ
- 済: チャンネル管理（REST API / CLI）、通知履歴、チャンネル別ログ、ダッシュボード集計、DB テーブルの閲覧・編集
- 済: 画面（素のHTML/JS、上記機能一式）、テストコード（JUnit5 + Mockito）
- 済: 配信の自動録画（チャンネルごとに ON/OFF、タイトルによる絞り込み、yt-dlp を外部プロセスとして起動）
- 済: 録画の一覧・再生（サムネイル付きグリッド、キーワード/状態での検索、専用の再生画面、Range リクエスト対応で seek 可能）
- 済: 録画の削除・ディスク使用量表示（合計・チャンネル別、削除済みチャンネルの孤児ファイルも検出）
- 未: 10チャンネル分の一括登録コマンド

### よくある質問

**Q: コンパイルエラーが出ました**  
A: `./gradlew clean build` を試してください。IDE のキャッシュをクリアしてから再度ビルドしてください。

**Q: 監視が開始されません**  
A: ログを確認してください。`YOUTUBE_API_KEY` と `DISCORD_WEBHOOK_URL` が正しく設定されているか確認しましょう。

**Q: ビルドファイルをクリーンアップしたい**  
A: `./gradlew clean` を実行してください。

## テスト

```bash
./gradlew test
```

## ログレベルの変更

ログ設定は `src/main/resources/logback-spring.xml` に集約しています。
`application.yml` 側には書かないでください（二重管理になるため）。

## バックアップと復元

H2 の DB（`data/monitor.mv.db`）には、利用者のアカウント・購読・視聴済み／お気に入り・
利用者ごとの Webhook・監査ログが入っています。壊れると全員が登録し直しになるので、定期的に控えを取ります。

```bash
bin/backup.sh    # data/backups/monitor-<日時>.zip を作り、新しい 14 個だけ残す
```

- サービスの稼働中でも止まっていても取れます（H2 の `BACKUP TO` で、書き込みの途中を写さない控えになる）。
- DB にパスワードを付けていれば `.env` の `SPRING_DATASOURCE_PASSWORD` を使います（無ければ空）。
- zip はパスワードのハッシュや Webhook の URL を含むので、本人だけが読める（600）にしてあります。
- 録画ファイルは容量が大きいので対象外です。
- 控えは DB と同じディスクにあるので、ディスクごと壊れると一緒に失われます。別のディスクへもコピーすると安全です。
- H2 のクライアントは `~/.gradle/caches` にある最新の版を使います。H2 の版を上げた直後は、
  キャッシュの状態によってサービスと違う版になりうるので、失敗したら `./gradlew build` の後に試してください。

毎日 4 時に取る cron の例（`crontab -e` で登録）:

```
0 4 * * * cd <リポジトリ> && bin/backup.sh >> logs/backup.log 2>&1
```

### 復元

```bash
bin/service.sh stop
mv data/monitor.mv.db data/monitor.mv.db.bad          # 今の DB は消さずに退避しておく
unzip -o data/backups/monitor-<日時>.zip -d data/
bin/service.sh start
```

DB のパスワードは DB の中に入っているので、復元した DB は**控えを取った時点のパスワード**に戻ります。
その後に `.env` の `SPRING_DATASOURCE_PASSWORD` を付けた・変えた場合は、起動しません
（ログに `Wrong user name or password`）。そのときは止めたまま、控えの時点のパスワード
（付ける前なら空）で入って今の値に合わせてから起動します（`H2_JAR` の求め方は `.env.example` のとおり）。

```bash
java -cp "$H2_JAR" org.h2.tools.Shell -url "jdbc:h2:file:./data/monitor" \
    -user sa -password "<控えの時点のパスワード>" -sql "ALTER USER SA SET PASSWORD '<.env の値>'"
```

## トラブルシューティング

### 通知が届かない

まず「そもそも検知できていないのか」「検知したが送信に失敗したのか」を切り分けます。
通知履歴には失敗した試行も残るので、`errorMessage` を見れば後者かどうかが分かります。

```bash
curl -s "http://localhost:8080/api/notifications"
```

履歴が 1 件も無ければ検知の段階で止まっています。該当チャンネルのログを確認してください。

### 同じ配信の通知が来ない

仕様です。同じ配信に対しては 1 度しか通知しません。
判定には「最後に通知した動画 ID」を使っており、配信が変われば ID も変わるため次の配信では通知されます。
再通知を試したい場合は該当チャンネルの `LAST_NOTIFIED_VIDEO_ID` を空にしてください。

```bash
curl -s -X PUT http://localhost:8080/api/admin/tables/CHANNELS/1 -H "Content-Type: application/json" -d '{"LAST_NOTIFIED_VIDEO_ID":null}'
```

### 録画したファイルでディスクを圧迫する

録画は配信終了まで続くため、長時間配信（24時間配信など）を対象に有効化するとディスク使用量が
数十GB以上になることがあります。既定では画質・音質を落とさず配信の最高品質のまま録画する設計
（`MONITOR_RECORDING_MAX_HEIGHT=0`）のため、特に大きくなりやすい点に注意してください。
`/recordings.html` の「ディスク使用量」（合計・チャンネル別）で定期的に確認し、
不要な録画は一覧から削除してください（録画ファイルも一緒に削除されます。録画中のものは
削除できません）。

**チャンネルを監視対象から削除しても、録画ファイル本体はディスクに残ります**（誤って消さない
ための設計）。残ったものは同じ画面の「削除済みチャンネルの録画を一括削除」ボタンでまとめて
片付けられます。該当が無いときはボタン自体が表示されません。この使用量はファイルシステムを実際に走査して求めているため、
録画に失敗して DB 上は記録が残っていない断片ファイル（`{動画ID}.f***.mp4`）が
ディスクに残っている場合も反映されます。手動で消す場合は `recordings/` 配下を直接操作してください。
どうしても容量を優先したい場合のみ `MONITOR_RECORDING_MAX_HEIGHT` に正の値（例: 1080）を
設定すると解像度に上限がかかります。

### 録画一覧の「途中まで」とは

配信の途中で録画が止まったが、**そこまでの内容は再生できる**状態です。完了と同じように
一覧から再生できます。完了と区別しているのは、「3時間の配信のはずが40分で終わっている」
理由が分からなくなるのを避けるためです。

`yt-dlp` は**ダウンロードを終えてから最後にまとめて MP4 へ詰め替える**ため、途中で止まると
映像は残っているのにブラウザで再生できない形になります。中身は次のとおりです。

| 状況 | ディスク上の実体 | そのまま再生 |
|---|---|---|
| 正常終了 | 本物の MP4 | できる |
| 途中で停止（Twitch） | 拡張子は `.mp4` だが**中身は MPEG-TS** | できない |
| 途中で停止（YouTube） | 映像 `.f137.mp4` と音声 `.f140.m4a` が別のまま | できない |

これをアプリが自動で `ffmpeg` により詰め替え・結合し、再生できる形にしてから「途中まで」として
記録します。映像・音声は再エンコードせずそのままコピーするので、画質は劣化せず処理も高速です
（実測: 40分・800MB のファイルで約1.4秒）。

詰め替えは**録画プロセスが終わっていることを確認してから**行うため、録画中の配信には影響しません。

### 録画一覧に「失敗」と表示され再生できない

録画の成否は **再生できるファイルを用意できたかどうか** だけで判定しています。
配信終了間際に一部のデータを取得できず `yt-dlp` がエラー終了しても、ファイルさえ
出来ていれば「完了」として扱われ、そのまま再生できます。

「失敗」と表示されるのは、途中までの内容すら残っていなかった場合です
（録画開始直後に停止した場合など）。ファイルが少しでも残っていれば、次の巡回で
自動的に「途中まで」へ救済されます。

### 録画は完了しているはずなのに、一覧が「録画中」のままで再生できない

録画の完了記録は、録画開始時に起動する仮想スレッドが担っています。**この録画が終わる前に
サービスを再起動すると**、`yt-dlp` 自体は無事に完了しても DB の状態を更新する者がいなくなり、
「録画中」のまま止まってしまいます（完成ファイル自体は存在します）。

この状態は、通常の巡回サイクル（既定の間隔、または「今すぐチェック」ボタン）のたびに
自動で補正され、完成ファイルがあれば「完了」、無ければ「失敗」に更新されます。
サービスの再起動を待たずに「今すぐチェック」を押せばすぐ直ります。

### ポートが使用中で起動できない

`bin/service.sh start` は管理外のプロセスがポートを掴んでいる場合に起動を中止します。
まず状態を確認してください。

```bash
bin/service.sh status
```

## 参考リソース

- [Spring Boot 公式ドキュメント](https://spring.io/projects/spring-boot)
- [YouTube API v3 リファレンス](https://developers.google.com/youtube/v3/docs)
- [Discord Webhook ドキュメント](https://discord.com/developers/docs/resources/webhook)
- [Java 21 ドキュメント](https://docs.oracle.com/en/java/javase/21/)

## ライセンス

このプロジェクトは学習目的で自由に使用・改変できます。
