# YouTube Live Monitor

YouTube と Twitch のライブ配信を監視し、配信開始時に Discord へ自動通知するサービスです。
（YouTube 専用として作り始めたため、名前はそのままになっています。）

**目的**: Java・Spring Boot・API 統合の学習プロジェクト

## 技術スタック

- **言語**: Java 21
- **フレームワーク**: Spring Boot 4.1.1
- **ビルドツール**: Gradle
- **DB**: H2（ファイルモード）+ Spring Data JPA
- **CLI**: Picocli
- **ライブラリ**:
  - google-api-services-youtube（YouTube API クライアント）
  - jsoup（配信中判定のための HTML 解析）
  - yt-dlp（配信の録画。Java 製で継続的なライブ配信録画に対応するライブラリが存在しないため、
    外部プロセスとして起動している。アプリのソースコード自体は 100% Java）
- **対応プラットフォーム**: YouTube / Twitch
  （Twitch はクライアントライブラリを使わず、`java.net.http.HttpClient` で Helix API を直接呼んでいる）
- **Discord 通知**: ライブラリを使わず、`java.net.http.HttpClient` で Webhook へ JSON を POST している

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
│   ├── service.sh                            # 起動・停止・状態確認コマンド
│   ├── monitor.sh                            # CLI（チャンネルの登録など）をリポジトリ直下の DB に対して実行する
│   └── youtube-live-monitor.service          # 自動起動用の systemd ユーザーユニット
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

- JDK 21（ビルドに使う。`build.gradle` の toolchain が 21 に決めてあり、ほかの版を自動で取ってくる設定は無いので、
  JDK 21 が見つからないとビルドが止まる。`JAVA_HOME` か `PATH` の `java` を JDK 21 にしておくのが確実）。実行は Java 21 以上
- Google Cloud Console アカウント
- Discord サーバー管理権限（Webhook 作成用）
- 録画機能を使う場合のみ: `yt-dlp` と `ffmpeg`（後述）。耳キスの検出（後述）も `ffmpeg` を使う

### 1. リポジトリのクローン

```bash
cd ~/youtubeLiveMonitor
```

### 2. 環境変数の設定

```bash
cp .env.example .env
chmod 600 .env   # API キーや Webhook を同じ端末のほかのユーザーから読めないようにする
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
python3 -m pip install --user --break-system-packages "yt-dlp[default]"
```

`[default]` を付けると、YouTube の JavaScript の暗号を解く部品 `yt-dlp-ejs` も入ります。無いと形式が欠け、
ログインした状態の Cookie（後述「YouTube の Cookie を置く」）を渡しても取得できません
（[落とし穴](docs/pitfalls.md)）。入ったかは、次の出力に `Signature solving failed` が出ないことで確かめます。

```bash
yt-dlp -v --simulate "https://www.youtube.com/watch?v=<動画ID>" 2>&1 | grep "Signature solving failed"
```

#### yt-dlp の更新

YouTube 側の変更で録画が失敗するようになったときは、まず yt-dlp を最新にします。

```bash
python3 -m pip install -U --user --break-system-packages "yt-dlp[default]"
yt-dlp --version
```

**録画中は更新しないでください**（`pgrep -f "^/usr/bin/python3 .*yt-dlp"` が何かを返す間は録画中です）。

`ffmpeg` も別途必要です（映像・音声の結合と、一覧に出すサムネイル・再生時間の取り出しに使用）。
Debian/Ubuntu 系なら次の通りです。`ffprobe` は `ffmpeg` パッケージに同梱されています。

```bash
sudo apt install ffmpeg
```

サムネイルは録画ファイルと同じ場所に `{動画ID}.jpg` として作られます（再生時間の 10% の位置を
切り出したもの）。作成は巡回処理のついでに後追いで行われるため、録画完了の直後は
「サムネイル生成待ち」と表示されることがあります。取り出しに失敗しても録画の再生には影響しません。

#### YouTube の Cookie を置く（ログインが要る配信を録画する場合）

録画が `Sign in to confirm you’re not a bot` で失敗するときは、yt-dlp を更新しても直りません
（2026-10 以降、ASMR 系の配信などはログインなしでは断られます）。ログインした状態の Cookie を
ファイルで渡します。

1. **録画専用の Google アカウント**を作り、ブラウザ（普段使いとは別のプロファイルか、プライベートウィンドウ）で
   YouTube にログインする。普段のアカウントは使わないでください（Cookie はアカウントそのもので、
   取得の頻度によってはアカウントに制限が掛かることがあります）。
2. Cookie を **Netscape 形式**（`# Netscape HTTP Cookie File` で始まるテキスト）で書き出す拡張機能
   （「Get cookies.txt LOCALLY」など）で、`youtube.com` の Cookie を書き出す。
   書き出したら、そのブラウザのウィンドウは YouTube を開かずに閉じる（開いたままだと Cookie が入れ替わり、
   書き出した方が早く無効になります）。
3. サービスの作業ディレクトリの `data/youtube-cookies.txt` に、本人だけが読める権限で置く。

   ```bash
   install -m 600 ~/Downloads/cookies.txt data/youtube-cookies.txt
   ```

置くだけで、次に起動する yt-dlp（録画・手動ダウンロード・下調べ）から効きます。再起動は要りません。
場所を変えるときは `.env` の `MONITOR_YTDLP_COOKIES` にパスを書きます。ファイルが無ければ今までどおり
Cookie なしで取得します。yt-dlp には起動のたびに作る写し（`data/ytdlp-cookies/`）を渡し、使い終わった写しは
後始末の見回りで消えます（同時に動く yt-dlp が、終了時に同じファイルへ書き戻して壊すのを避けるため）。
ログインが切れたら（同じエラーに戻ったら）、2〜3 をやり直してください。

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

### 5. systemd で自動起動する（任意）

`bin/service.sh start` だけでは、OS の再起動・停電・JVM の異常終了（OOM を含む）の後、
手で起動し直すまで監視・通知・録画が止まったままになります。
`bin/youtube-live-monitor.service`（systemd のユーザーユニット）を入れると、systemd が起こし直します。

- `kill -9` や OOM で落ちたら 10 秒後に起こし直す（5 分に 5 回落ちたら諦めて `failed` のまま残す）
- `loginctl enable-linger` と合わせて、ログインしなくても OS の起動時に立ち上がる
- **停止・再起動で録画中の yt-dlp を止めない**（`KillMode=process`。理由はユニットファイルのコメント）
- 入れた後も操作は `bin/service.sh start|stop|restart|rollback|status` のまま（中で `systemctl --user` を呼ぶ）。
  `status` に `systemd: youtube-live-monitor.service` と出ていれば systemd 経由で動いている
- ログは今までどおり `logs/service.log`。起こし直した記録は `journalctl --user -u youtube-live-monitor`

ユニットはリポジトリが `~/youtubeLiveMonitor` にある前提です。別の場所なら `WorkingDirectory` と
`ExecStart` を書き換えてください。

**切り替えの手順**（録画が無いときに行う。リポジトリの場所で実行する）:

```bash
# 1. nohup で動いている今のプロセスを止める。ユニットを入れた後の bin/service.sh は systemd の側しか
#    見ないので、先に止めておかないと、古いプロセスがポートを掴んだまま残る
bin/service.sh stop

# 2. ユニットを登録して起動し、OS の起動時にも立ち上がるようにする
systemctl --user link "$PWD/bin/youtube-live-monitor.service"
systemctl --user enable --now youtube-live-monitor
loginctl enable-linger "$USER"

# 3. 確かめる
bin/service.sh status                         # 「systemd: youtube-live-monitor.service」と出る
kill -9 "$(systemctl --user show -p MainPID --value youtube-live-monitor)"
sleep 40 && bin/service.sh status             # 別の PID で起動中に戻る（10 秒待ってから起動し直す）
```

`link` なのでリポジトリのユニットファイルを直接読みます。ユニットファイルを更新したら
`systemctl --user daemon-reload` を実行してください。
JVM のオプションを変えるときは `systemctl --user edit youtube-live-monitor` で
`[Service]` に `Environment=JAVA_OPTS=...` を足します（端末の環境変数はユニットに届きません）。

ユニットの `PATH` は `~/.local/bin:/usr/local/bin:/usr/bin:/bin` に決めてあります（ログインしていないときの
systemd には、シェルの `PATH` が届かないため）。`java`・`yt-dlp`・`ffmpeg`・`deno`（yt-dlp が既定で探す
JavaScript のランタイム）をこの外（SDKMAN の `~/.sdkman/candidates/java/current/bin`、`deno` の公式の
インストーラーが入れる `~/.deno/bin` など）に入れているなら、`systemctl --user edit youtube-live-monitor` で
`[Service]` に `Environment=PATH=<足す場所>:%h/.local/bin:/usr/local/bin:/usr/bin:/bin` を書き足してください
（JavaScript のランタイムだけなら、`.env` の `MONITOR_YTDLP_JS_RUNTIME` に `deno:/home/<user>/.deno/bin/deno` のように
「ランタイムの名前:フルパス」の形で書いてもかまいません）。
書き足さないと、`nohup` で起動したときと systemd で起動したときとで、起動できるかどうかや、録画できる形式が変わります。

**元に戻す（手動起動に戻す）**:

```bash
systemctl --user disable --now youtube-live-monitor
rm -f ~/.config/systemd/user/youtube-live-monitor.service
systemctl --user daemon-reload
loginctl disable-linger "$USER"               # ほかに linger を使うものが無ければ
bin/service.sh start                          # 以後は nohup で起動する
```

### 6. 巡回が止まったら Discord へ知らせる（任意）

サービスが落ちたり、巡回が固まったりしても、ポートが開いている限り `bin/service.sh status` は「起動中」と表示します。
巡回が回っているかは `GET /api/health`（ログイン不要）で分かり、`bin/service.sh status` の「巡回:」の行にも出ます。

| 状態 | HTTP | 意味 |
|---|---|---|
| `UP` | 200 | 巡回が回っている |
| `STARTING` | 200 | 起動直後で、まだ 1 巡していない |
| `DISABLED` | 200 | 確認用の起動（`bin/preview.sh`）で巡回を止めている |
| `STALE` | 503 | 最後の巡回から巡回間隔の 3 倍より長く経った（巡回が止まっている） |

`bin/health-watch.sh` を cron から 5 分ごとに呼ぶと、2 回続けて `/api/health` が失敗したとき（落ちている・`STALE`）に
`.env` の `DISCORD_WEBHOOK_URL` へ 1 度知らせ、戻ったらもう 1 度知らせます。

```bash
crontab -e
# 次の 1 行を足す
*/5 * * * * $HOME/youtubeLiveMonitor/bin/health-watch.sh
```

**配信状態を判定できない状態が続いたとき（設定は要りません）**:
巡回は回っていても、YouTube 側の HTML の変更や Twitch の認証情報の失効で、配信状態を判定できなくなることがあります
（このとき `/api/health` は `UP` のままです）。これはアプリ自身が `.env` の `DISCORD_WEBHOOK_URL` へ知らせます。

- 同じプラットフォームの登録チャンネルの過半数が、5 巡回（既定の間隔で約 10 分）続けて判定できなかったとき
- 1 つのチャンネルが 30 回（既定の間隔で約 1 時間）続けて判定できなかったとき（チャンネルの削除・改名など）

どちらも 1 度だけ知らせ、判定できるようになったらもう 1 度知らせます。知らせたかどうかはメモリに持つため、
失敗が続いたまま再起動すると、もう 1 度知らせることがあります。

### 7. Docker で動かす（本番）

本番は、運用リポジトリ（非公開の `ryusei-git/server-stacks`）の `stacks/youtube-live-monitor` が Docker Compose で動かします（#856）。
このリポジトリが持つのはイメージの作り方（`Dockerfile`・`docker/entrypoint.sh`）と、イメージを作る CI だけです。
上の 4〜6（`bin/service.sh`・systemd・`bin/health-watch.sh`）は、開発の端末や旧方式で使うものとして残しています。

**イメージ**: main に入ると CI（`.github/workflows/ci.yml` の image ジョブ）が、テストを通った jar で
`ghcr.io/ryusei-git/youtube-live-monitor:main` と `:sha-<7 桁>` を作ります。PR ではビルドだけ確かめます。
中身は Java 21 の JRE・`yt-dlp[default]`（yt-dlp-ejs 入り）・ffmpeg/ffprobe・deno・curl です。yt-dlp は版を固定せず、
CI が毎週月曜 03:00（日本時間）にイメージを作り直して新しくします。YouTube 側の変更で壊れて急ぐときは、GitHub の
Actions から CI を手で実行（workflow_dispatch）して作り直します。

**反映**: 本番への反映は運用側の `deploy.sh` が、時間帯と guard（`/api/health` の `"recording":true` を見て録画中なら見送る）を
見て行います。手で `docker compose up` し直さないでください。**コンテナを止めると録画中の yt-dlp も一緒に止まります**
（`docs/pitfalls.md`「Docker ではコンテナを止めると録画も止まる」）。

**ファイルの置き場所**（コンテナの中の作業ディレクトリは `/app`。既定の相対パスがそのまま合います）

| コンテナの中 | 中身 | 運用側のマウント |
|---|---|---|
| `/app/data` | DB・remember-me の鍵・Cookie・`.env`（`/app/.env` はここへのリンク） | SSD（`stacks/youtube-live-monitor/data`） |
| `/app/logs` | チャンネル別ログ・`service-app.log`・`logs/yt-dlp/` | SSD（`stacks/youtube-live-monitor/logs`） |
| `/app/storage/recordings` | 録画（`MONITOR_RECORDING_DIRECTORY` で指す） | HDD |

`.env` は `data/.env` に置きます。設定画面の保存もここに書きます（秘密をコンテナの環境変数にすると yt-dlp・ffmpeg に
引き継がれるので、compose の `env_file` にはしません）。Docker に固有の値（`SERVER_ADDRESS=0.0.0.0`・録画の場所・
信頼するプロキシ）は compose の `environment` に書き、こちらが `.env` より優先されます。

**CLI とバックアップ**: H2 の `AUTO_SERVER` はコンテナの中のループバックで待ち受けるので、稼働中の DB に CLI を当てるときは
`docker exec` で同じコンテナの中から動かします（運用側の `stacks/youtube-live-monitor/cli.sh`）。ホストで `bin/monitor.sh` を
動かすと、リポジトリの `data/` に空の DB を作ってしまいます。DB の控えは運用側が毎晩、コンテナの中の H2
（`/app/tools/h2.jar`。サービスと同じ版）で `BACKUP TO` して取ります。

**友人用の入口**: 友人は管理者とは別の Tailscale の入口（運用側の関所。利用者の画面と API だけを通す）から使います。
関所はアプリへ渡す要求に必ずヘッダ `X-YLM-Friend-Gate` を付け、アプリはこのヘッダの有る要求では管理者として扱いません
（フォームのログイン・「ログインしたまま」の自動ログイン・既にあるセッションのどれも。`security/FriendGate`）。
関所の IP は `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` に足します。足さないと `X-Forwarded-Proto` が無視されて
ログイン後の転送が `http://` になり、ログイン試行の制限と監査ログの IP も全員が関所の IP になります。

## 使用方法

### 監視対象チャンネルの登録

チャンネル ID が分かっている場合はそのまま登録します。

```bash
bin/monitor.sh channel add -i UCxxxxxxxxxxxxxxxxxxxxxx -n "配信者名"
```

`-i` にはチャンネル ID のほか、**ハンドル（`@foo`）やチャンネルページの URL をそのまま**渡せます。
URL の場合は自動で本来のチャンネル ID に解決されます。
ハンドルの解決には YouTube API キーが要ります（キーが無いと「YouTube の API キーが設定されていません」で止まります）。
空白など、ハンドルに使えない文字を含む入力は、API に問い合わせずに断ります。

```bash
bin/monitor.sh channel add -i "https://www.youtube.com/@foo" -n "配信者名"
```

Twitch を監視する場合は `-p TWITCH` を付けます（省略時は YouTube）。
`-i` には URL か、`twitch.tv/` の後ろに出ているチャンネル名を渡します。

```bash
bin/monitor.sh channel add -p TWITCH -i "https://www.twitch.tv/foo" -n "配信者名"
```

チャンネル名は配信者本人が変更できるため、登録時に**変更されない数値のユーザー ID へ自動で解決**して
保存します。名前のまま保存すると、改名された瞬間に「ずっとオフライン」と判定し続けて
気づけなくなるためです。

配信を検知したら自動録画したい場合は `-r` を付けます（`yt-dlp` のインストールが必要）。
`-k` で録画対象をタイトルで絞り込むこともできます。

```bash
bin/monitor.sh channel add -i UCxxxxxxxxxxxxxxxxxxxxxx -n "配信者名" -r -k "ASMR,生配信"
```

登録済みチャンネルの録画設定は後からでも切り替えられます。`-i` に指定する ID は
`channel list` の先頭列の値で、YouTube のチャンネル ID ではありません。

```bash
bin/monitor.sh channel record -i <id> --on
bin/monitor.sh channel record -i <id> --off
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
bin/monitor.sh channel record -i <id> --on -k "ASMR,生配信"
```

チャンネル ID が分からない場合は名前から検索できます（100 クォータを消費するので多用しないこと）。

```bash
bin/monitor.sh channel search -n "配信者名"
```

一覧の確認と削除は次のとおりです。`remove` で指定する ID は一覧の先頭列の値で、
YouTube のチャンネル ID ではありません。

```bash
bin/monitor.sh channel list
```

サービスが常駐している最中でも CLI は実行できます。

`bin/monitor.sh` は、どのディレクトリから呼んでもリポジトリ直下へ移ってから CLI を起動します。
DB（`data/monitor`）と `.env` は `java` を起動した場所を基準に探すため、別の場所で `java -jar` を直接実行すると、
その場所に空の DB が新しくでき、「登録しました」と表示されてもサービスには届きません。
jar は稼働中のサービスと同じ `run/youtubeLiveMonitor.jar` を使い、まだ 1 度も起動していなければ `build/libs` の jar を使います。
CLI も起動時にテーブルを自分の版の形に合わせる（`ddl-auto: update`）ため、ビルドしただけで再起動していない新しい版で
稼働中のサービスの DB を触らないようにしています。
引数を付けずに実行すると、コマンドの一覧を表示します（jar は引数が無いとサービスとして起動するため、`--help` に置き換えています）。

### 画面

サービス起動中にブラウザで `http://localhost:8080/` を開くと使えます。
一般利用者（管理者でない利用者）が `/` を開くと、利用者の画面（`/my`）へ移ります。
ログインしていないときは管理者用のログイン画面が出るので、その下の「利用者用のログイン画面」から
ログインしてください。

| 画面 | URL | 内容 |
|---|---|---|
| ダッシュボード | `/index.html` | 主要指標のタイル・内訳グラフ（チャンネル状態／録画状態／ディスク使用量）・チャンネル別使用量の横棒・検知失敗の警告・設定の変更（APIキー・Webhook・Twitch認証情報を含む）・今すぐチェック |
| チャンネル管理 | `/channels.html` | 一覧・登録（プラットフォーム選択／URL貼り付け可）・削除・名前検索（YouTubeのみ）・録画設定・今すぐチェック |
| 通知履歴 | `/notifications.html` | 送信結果の一覧（失敗理由も表示） |
| 録画 | `/recordings.html` | 録画のサムネイル付きギャラリー・検索（タイトル/チャンネル名のキーワード、チャンネル、録画状態）・削除・ディスク使用量（チャンネル別）・削除済みチャンネルの録画の一括削除 |
| 再生 | `/player.html?id=` | 録画 1 本の再生画面。録画の詳細情報と、同じチャンネルの他の録画も並ぶ |
| ログ | `/logs.html` | チャンネル別・システムログの閲覧。対象はチャンネル名（配信元）で選ぶ。日付で回った過去のファイル（14 日分）もさかのぼり、新しい順に表示。レベルでの絞り込み（選択肢はそのログに実在する値のみ） |
| DB管理 | `/tables.html` | テーブルの閲覧と、編集モードを有効にしたときのセルのクリック（または Enter）での直接編集（主キーとバイナリの列は編集できない）。テーブル名・カラム名は論理名（日本語）で表示し、カーソルを当てると物理名（DB上の実際の名前）を確認できる |

素の HTML・CSS・JavaScript のみで作られており（フレームワーク不使用）、上記の REST API を
`fetch` で叩いているだけです。npm や webpack のようなフロントエンドのビルドは必要ありません。

ただし `src/main/resources/static/` 配下は `./gradlew build` で jar に**パッケージされる**ため、
画面を編集したら次のようにビルドを挟んでから再起動してください
（`bin/service.sh restart` だけでは今ある jar を再起動するだけで、編集内容は反映されません）。

```bash
./gradlew clean build -x test && bin/service.sh restart
```

`clean` は、前の名前の jar（`version` を上げる前のものなど）を `build/libs` から
消すためです（`./gradlew build` は消しません）。jar が 2 個残っていると、`bin/service.sh` は
古い版を動かさないよう「build/libs に jar が 2 個あります」と出して起動せずに終わります
（restart なら動いているサービスは止めません）。

静的リソースには `Cache-Control: no-cache` を付けています（`application.yml`）。
ブラウザは毎回サーバへ問い合わせ、変更が無ければ 304 が返るため、
画面を更新したのに古い内容が表示される、という状態になりません。
録画ファイル（`/recordings/**`）はこの設定の対象外で、キャッシュがそのまま効きます。

### 利用者管理（一般利用者の名前とパスワードを変える）

管理画面の「利用者管理」（`/users.html`）の表で、一般利用者の利用者名とパスワードを変えられます。

- **パスワードは誰にも見られません**（管理者にも）。保存しているのは元に戻せないハッシュだけなので、表には伏せ字（`●●●●●●●●`。実際の長さとは関係ありません）が出ます。忘れた利用者には、新しいパスワードを決めて伝えるか、「操作」の列の「再設定リンク」（再設定用のリンクの発行）で本人に決め直してもらってください。
- 利用者名: 「利用者名」の列の「変更」→ 新しい利用者名（3 文字以上）と、ログイン中の管理者のパスワードを入れて保存します。同じ名前がすでにあれば変えられません。
- パスワード: 「パスワード」の列の「変更」→ 新しいパスワード（8 文字以上）を 2 回と、管理者のパスワードを入れて保存します。
- どちらも、変えるとその利用者のログイン中の画面と「ログインしたままにする」はすべての端末で切れます。新しい利用者名・パスワードは本人に伝えてください（パスワードを変えると、発行済みの再設定用のリンクも使えなくなります）。
- 管理者のパスワードを 5 回続けて間違えると、15 分のあいだ変更できなくなります。
- 管理者の行は変えられません。管理者自身のパスワードは、同じ画面の「自分のパスワード」で変えます。

### ログインしたままにする

ログイン画面で「ログインしたままにする（30 日）」を選ぶと、その端末ではログインした時から 30 日間
ログインが続きます。30 分操作しなくても、アプリを再起動しても、ブラウザを閉じても切れません
（使っても 30 日は延びません。過ぎたらログインし直してください）。

- パスワードを変えると、その利用者の「ログインしたまま」は全端末で無効になります。ただし自分のアカウントの画面で変えたときは、変えた端末で「ログインしたまま」にしていれば、その端末の分だけ新しいパスワードで作り直し、そこから 30 日続きます。利用者を無効化・削除したときは全端末で無効になります。
- ログアウトすると、その端末の「ログインしたまま」は解除されます（ほかの端末は続きます）。
- Cookie の署名の鍵は `data/remember-me.key`（本人だけが読める 600）にあり、初回の起動で作られます。
  場所は `MONITOR_REMEMBER_ME_KEY_FILE` で変えられます。
- 全員をログインし直させたいときは、`data/remember-me.key` を消して再起動します（鍵が作り直されます）。
- 「ログインしたまま」で自動的にログインし直したときも、監査ログに「ログイン成功」（補足「「ログインしたままにする」の Cookie による自動ログイン」）が残り、利用者一覧の「最終ログイン」も更新されます。

### 外から使う（tailscale serve）

アプリは `127.0.0.1:8080` だけで待ち受けます（`application.yml` の `server.address`）。
全インターフェースで待ち受けると、LAN やグローバル IPv6 からログインのパスワードと
セッションの Cookie が平文の HTTP で流れるためです。ほかの端末からは、同じ tailnet の中で
`tailscale serve` の HTTPS を通して開きます。

```bash
# 443 の HTTPS で受けて 127.0.0.1:8080 へ渡す（--bg で常駐し、端末の再起動後も残る）
tailscale serve --bg --https=443 http://127.0.0.1:8080
tailscale serve status        # https://<端末名>.<tailnet>.ts.net/ が表示される
# やめるとき
tailscale serve --https=443 off
```

tailnet の端末（iPhone など）から `https://<端末名>.<tailnet>.ts.net/` を開きます。
初回は tailnet の管理画面で MagicDNS と HTTPS 証明書を有効にしておく必要があります
（無効だと `tailscale serve` がその旨を表示します）。

`tailscale serve` は `X-Forwarded-For`・`X-Forwarded-Proto: https` を付けて渡すので、アプリは
これを読んで監査ログやログイン試行の制限に本来の接続元（100.x）を使い、ログイン後の遷移も
https のままにします。**このヘッダーを信じるのはループバックから来た要求だけ**です
（`server.tomcat.remoteip.internal-proxies`）。tailnet や LAN から直接つないだ人が
`X-Forwarded-For` を偽装しても無視されます。

待ち受けを絞ったうえで、次の 2 つも確かめておくと安心です。

```bash
ss -ltnp | grep 8080          # 127.0.0.1:8080（Java は [::ffff:127.0.0.1]:8080 と表示される）だけで、*:8080 が無いこと
sudo ufw status verbose       # 受信は既定で拒否（deny (incoming)）、許可は tailscale0 と lo だけ
#   設定する場合: sudo ufw default deny incoming && sudo ufw allow in on tailscale0 && sudo ufw allow in on lo && sudo ufw enable
```

ルーターの管理画面では、IPv6 の受信（外から端末へのパケットフィルター）が「遮断」に
なっていることを確かめます。IPv6 はルーターの NAT の内側に隠れず、端末ごとのグローバル
アドレスへ直接届くためです。

#### LAN から直接見たい場合の戻し方

`.env` に次の 1 行を書いて再起動すると、以前と同じく全インターフェース（IPv6 を含む）で待ち受けます
（平文の HTTP に戻るので、信頼できる LAN の中だけで使ってください）。

```bash
echo "SERVER_ADDRESS=0.0.0.0" >> .env
echo "SESSION_COOKIE_SECURE=false" >> .env
bin/service.sh restart
```

2 行目は、セッションの Cookie（`JSESSIONID`）と「ログインしたまま」の Cookie（`remember-me`）に既定で付く `Secure` を外す設定です。
`Secure` の Cookie はブラウザが HTTPS でしか送らないため、`http://192.168.x.x:8080` では
ログインしても Cookie が捨てられ、ログイン画面に戻されます。外しても、`tailscale serve` を通った
HTTPS の要求にはどちらの Cookie にも `Secure` が付くので、tailnet 経由の Cookie は守られたままです。

`bin/api.sh`・`bin/preview.sh`・`bin/health-watch.sh`・`bin/service.sh status` は
`localhost` / `127.0.0.1` へつなぐので、どちらの設定でもそのまま動きます
（curl・Chromium は `localhost` / `127.0.0.1` を安全な接続先として扱い、http でも `Secure` の Cookie を送ります）。

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
- **Webhook URL** は Discord が発行する `https://discord.com/api/webhooks/<数字>/<トークン>` の形だけを受け付けます。
  `?thread_id=` などを付けたもの・別のサイトの URL は保存の時点で断ります（`ptb.` や `canary.` が付いている場合は外してください）。
  `.env` を直接書き換えて形を誤った場合もアプリは起動しますが、全体向けの通知は届かず、起動時のログに WARN が出ます
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
  誤ってリモートから押した場合も、サーバー側のダイアログは 5 分で自動的に閉じられ、入力欄は変わりません。
  Linux で `zenity`（多くのディストリビューションに標準で入っています）が
  インストールされていない環境でも同様に、入力欄への直接入力で代替できます。

**保存しても実行中のアプリにはすぐ反映されません。** 設定は起動時に一度だけ `.env` から
読み込まれる仕組みのため（`MonitorProperties`参照）、保存後は次のコマンドで再起動してください。

```bash
bin/service.sh restart
```

`.env` はコメントを保ったまま該当行だけを書き換えます（無ければ末尾に追記）。ファイルが
存在しない場合は `.env.example` を土台に新規作成します。

`.env` があるのに読めない（UTF-8 以外で保存した等）ときは、作り直さずに保存を断ります。
書き込みは同じフォルダの一時ファイルに書いてから置き換えるので、途中で失敗しても元の `.env` が残ります。
置き換えた `.env` は本人だけが読める権限（Linux は 600、Windows は所有者だけの ACL）になります。

`.env` は bin のスクリプトが bash で読み込むため、空白や `#`・日本語などを含む値は `"..."` で囲んで書きます。
改行などの制御文字・`"`・`$`・`` ` `` を含む値と、`\` で終わる値・`\\` を含む値は保存できません（400）。
Windows のドライブ直下は `D:\` ではなく `D:/` と書いてください。
監視間隔は 30〜86400 秒、画質上限は 0〜4320 の範囲で保存できます。`.env` を直接編集して監視間隔を
30 秒未満にしている場合、画面から保存すると断られるので、選択肢から選び直してください。

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

### 画面のスクリーンショットを撮り比べる

`bin/ui-snapshot.sh` は、利用者の画面（`/my` 以下・ログイン・登録・パスワード再設定）を
PC（1280×800・1920×1080、Chromium）と iPhone（375×667・393×852・430×932、WebKit）の大きさで撮り、
変更の前後で PC の画像に 1 ピクセルでも差があるかを数えます（iPhone 向けの変更が PC の画面を変えていないことの確認）。
撮る相手は確認用インスタンス（`bin/sandbox.sh`）で、表示用のチャンネル・録画は撮る前に自動で入れます。

```bash
# PR のベースブランチの作業ツリーで
./gradlew clean build && bin/sandbox.sh start && bin/ui-snapshot.sh shoot /tmp/before && bin/sandbox.sh stop
# 変更した作業ツリーで
./gradlew clean build && bin/sandbox.sh start && bin/ui-snapshot.sh shoot /tmp/after && bin/sandbox.sh stop
bin/ui-snapshot.sh diff /tmp/before /tmp/after   # PC の画像に差があれば終了コード 1。差分の画像は /tmp/after/diff/
```

- 初回に Playwright と pngjs を `~/.cache/youtubeLiveMonitor/ui-snapshot`（`UI_SNAPSHOT_DEPS` で変更可）へ入れます。git には載せません
- ポートを変えて起動した確認用インスタンスは `SANDBOX_PORT=<ポート>` を付けて撮ります
- Windows で Smart App Control が有効だと WebKit が起動できません。そのときは
  `UI_SNAPSHOT_IPHONE_ENGINE=chromium` を付けると、iPhone の大きさも Chromium で撮ります（出力先は `iphone-…-chromium`）

### 画面の共通の操作

- **補足説明はホバーで表示**: 見出しやラベルの横にある `ⓘ` にカーソルを当てると説明が出ます。
  常時表示せずホバー式にしているのは画面を広く使うためです。
- **チャンネル名をクリックするとチャンネルIDが表示**: 一覧では基本的にチャンネル名だけを表示し、
  CLI コマンドなどでチャンネルIDが必要になったときだけ名前をクリックして確認します（もう一度
  クリックで隠れます）。
- **日時はクリックで精密表示に切り替え**: 一覧の日時は既定で秒までの表示（例:
  `2026-09-13 20:24:01`）にしており、クリックすると秒未満の精度を含む元の値に切り替わります。

### 耳キスの検出

録画の中の「耳キス」（ASMR の音）の位置を集めて、再生画面からその位置へ飛べるようにする機能です（試験中。#459）。

- **印**: 利用者の再生画面（`/my/watch/<録画の番号>`）の「耳キス」の欄で、聞きながら「ここは耳キス」を押すと
  その位置に印が付きます。印は全員で共有し、「前の耳キスへ」「次の耳キスへ」で飛べます。消せるのは自分の印だけです。
- **候補**: 検出器が録画に自動で付けた位置です。再生画面に「自動」として並び、聞いた人が「耳キス／ちがう」を
  答えます。答えも全員で共有し（最後の答えが残る）、検出器を学び直すときの正解になります。

**見回り**（`SoundDetectionScheduler`）が 10 分ごとに、今の版の検出器でまだ検出していない録画
（完了・途中まで、再生時間が分かっているもの）を、新しい順に 1 本ずつ検出します。

- 検出は 2 時間の録画で CPU を 40 秒ほど使い、録画と CPU を取り合うため、**録画中の録画がある間は始めません**
  （1 本終えるごとに確かめ、残りは次の見回りに回します）。
- **6 時間を超える録画は検出しません。** 検出器のメモリは録画の長さに比例し、足りないと JVM ごと落ちるためです。
  再生画面には `6 時間を超える録画は、自動の検出の対象外です` と出ます（失敗とは分けて出します）。
- 失敗が 3 回たまった録画は、見回りの対象から外れます（同じ録画で何度も落ちないため）。回数は最後に完了してから数え、
  ふつうの停止（`bin/service.sh restart` など）で止めた回は数えません。
- 検出 1 本には時間の上限（録画の長さの 1/10 ＋ 10 分。2 時間の録画で 22 分）があります。ffmpeg が固まるなどして超えると、
  次の見回り（または今すぐ検出の 409）のときに、ログに `耳キスの検出が時間の上限を超えたので止めます` を出して止め、失敗の 1 回に数えます。
- 音声は `nice -n 19 ffmpeg` で取り出すので、`ffmpeg` が要ります。
- 見回りを止めるには、`.env` に `MONITOR_SOUND_DETECTION_ENABLED=false` を書いて再起動します。
  印を付ける・候補に答える操作は、止めても使えます。確認用の起動（`bin/preview.sh`）では、監視と一緒に止まっています。

**検出器の版を上げる**（利用者の答えで学び直した重みに替える）:

1. 新しい重みの JSON を `src/main/resources/sound/` に足す。`version` は前の版と違う名前にする（例 `ear-kiss-linear12-v3`）。
2. `EarKissModel` の `RESOURCE` を新しいファイルに替える。
3. ビルドして再起動する。見回りが、全部の録画を新しい版で付け直します（1 本ずつなので、録画の本数に応じて時間がかかります）。
   前の版で答えた候補は、答えごと同じ位置の今の版の候補として引き継がれ（その前後 1 秒以内には新しい候補を作りません）、
   答え直す必要はありません。引き継ぎは付け直しを待たず、版を上げた後の最初の見回りで済みます（録画中でも行います）。

**検出器をその場で試す**（CLI。DB には保存しません）:

```bash
bin/monitor.sh sound detect 23 --from 0 --to 600
```

`23` は録画の番号（録画一覧の ID）、`--from`・`--to` は秒です（省略すると録画全体）。範囲を絞ると、
背景の音や候補の上限がその範囲だけで決まるので、録画全体で探したときと結果が変わることがあります。

**候補が付かないとき**:

- 管理者は `bin/api.sh GET "/api/recordings/<録画の番号>/sound-detection?kind=EAR_KISS"` で、今の版の実行記録を見られます。
  404 は、今の版でまだ一度も検出していない（順番待ち、または再生時間が分からない録画）ことを表します。
  `status` が `FAILED` なら `message` に理由があります（検出の途中も `FAILED`・`実行中に止まった` と出ます）。
  検出の途中でアプリが落ちたとき（メモリ不足・`kill -9`）は `実行中に止まった` のまま残り、失敗の 1 回に数えます。
  ふつうの停止で止めた回は、次の見回りが `アプリの終了で止めた（回数に数えない）` に直して、また試します。
  まとめて見るときは、DB 管理画面の「音の検出の実行記録」（`SOUND_DETECTION_RUNS`）を開きます。
  失敗はシステムログにも `耳キスの検出に失敗しました` として出ます。
- 失敗が 3 回たまった録画や、やり直したい録画は、`bin/api.sh POST "/api/recordings/<録画の番号>/sound-detection?kind=EAR_KISS"`
  で今すぐ検出します（202 が返り、終わるまで数十秒かかる。検出が走っている間は 409）。今の版で検出済みの録画をやり直すときは
  `&force=true` を付けます。やり直しても、答えのある候補は消えません。

### REST API

主な API だけを載せています（すべてではありません）。ここに無い API（利用者ポータル `/api/my/**` の多く・利用者の管理・招待・
監査ログ・動画のダウンロード・収集した動画・新人発掘・検索など）は、各コントローラーの JavaDoc を見てください（`./gradlew javadoc`）。

| メソッド | パス | 説明 |
|---|---|---|
| GET | `/api/dashboard` | 監視状況の集計（配信中のチャンネル、通知件数と失敗件数、検知失敗の警告、録画の状態別件数など） |
| GET | `/api/settings` | 現在有効な設定値（APIキー等は設定有無のみ、値は返さない） |
| PUT | `/api/settings` | 設定値を`.env`へ保存（反映には再起動が必要。後述） |
| POST | `/api/settings/directories/pick?initialDirectory=` | OSのフォルダ選択ダイアログを起動し、選ばれたパスを返す（キャンセル・5 分以内に閉じられなかったとき・ダイアログを表示できなかったときは204） |
| POST | `/api/monitor/check` | 次の巡回を待たずに今すぐ全チャンネルをチェック（実行中、または監視を止めた確認用の起動なら 409。理由は `error` に入る） |
| GET | `/api/health` | 巡回が回っているか（ログイン不要。`{"status":"UP","secondsSinceLastPoll":42}`、止まっていれば 503） |
| GET | `/api/platforms` | 対応している配信プラットフォームの一覧（登録画面の選択肢。認証情報の設定有無も返す） |
| GET | `/api/channels` | 監視対象の一覧 |
| POST | `/api/channels` | 監視対象の登録（`platform` は省略可、既定は `YOUTUBE`） |
| PUT | `/api/channels/{id}/record` | 録画設定の ON/OFF 切り替え |
| PUT | `/api/channels/{id}/record-title-filter` | 録画対象を絞り込むタイトルキーワードの更新 |
| DELETE | `/api/channels/{id}` | 監視対象の削除。通知履歴・録画履歴（視聴済み・お気に入り・耳キスの印と候補を含む）・購読・収集した動画（サムネイル・収集の状態を含む）・チャンネル別ログも消え、録画中の yt-dlp は止める。録画ファイルは残る（録画画面の「削除済みチャンネルの録画を一括削除」で片付ける） |
| GET | `/api/channels/search?name=` | チャンネル名から検索（**YouTube のみ**。1回100クォータ消費。API キーが無い・呼び出しに失敗したときは 503、キーが無いときは回数を使わない） |
| GET | `/api/notifications` | 通知履歴（失敗した試行も含む） |
| GET | `/api/recordings?keyword=&status=&channelId=` | 録画履歴（録画中・失敗も含む）。`keyword` は配信タイトルとチャンネル名の部分一致 |
| GET | `/api/recordings/{id}` | 録画履歴 1 件（再生画面用） |
| DELETE | `/api/recordings/{id}` | 録画履歴と録画ファイルの削除（録画中は409） |
| GET | `/api/recordings/disk-usage` | 録画ディレクトリの使用量（合計・チャンネル別、登録有無フラグ付き） |
| GET | `/api/recordings/orphaned/preview` | 孤立した録画ファイル（録画履歴に動画 ID が無いもの）の削除候補と確認トークン。ファイルは変更しない（録画中は対象外） |
| DELETE | `/api/recordings/orphaned/confirmed?token=` | プレビューで確認した削除候補だけをファイル単位で削除（確認後に対象が変わっていれば 400） |
| GET | `/recordings/**` | 録画ファイル本体の配信（静的リソース、HTTP Range 対応） |
| GET | `/api/logs/channels` | ログがあるチャンネルの一覧 |
| GET | `/api/logs/channels/{channelId}?limit=&level=` | チャンネル別ログ（`level` でレベル絞り込み。日付で回った過去のファイルも含めて、新しい方から `limit` 件） |
| GET | `/api/logs/system?limit=&level=` | システムログ（`level` でレベル絞り込み。日付で回った過去のファイルも含めて、新しい方から `limit` 件） |
| GET | `/api/admin/tables` | DB のテーブル一覧 |
| GET | `/api/admin/tables/{name}` | テーブルの内容 |
| PUT | `/api/admin/tables/{name}/{pk}` | 行の更新 |
| PUT | `/api/admin/users/{id}/username` | 一般利用者の利用者名を変える（管理者のみ。本文 `{"username":"...","adminPassword":"<管理者自身の今のパスワード>"}`。成功は 204 で、その利用者のログイン中の状態は切れる。管理者のパスワード違いは 403、要件違反は 400、管理者・自分自身・同じ名前は 409） |
| PUT | `/api/admin/users/{id}/password` | 一般利用者の新しいパスワードを決める（管理者のみ。本文 `{"password":"...","adminPassword":"..."}`。今のパスワードは見られない。成功は 204 で、その利用者のほかの端末のログイン・「ログインしたままにする」・再設定用のリンクは切れる。403・400・409 は上と同じ） |
| POST | `/api/recordings/{recordingId}/sound-detection?kind=EAR_KISS&force=` | 録画の耳キスの検出を今すぐ始める（202。今の版で検出済みなら `force=true` が要る。検出が走っている・再生できない録画は 409） |
| GET | `/api/recordings/{recordingId}/sound-detection?kind=EAR_KISS` | 今の版の検出の実行記録（`status`・`candidateCount`・`attempts`・`message` など。まだ一度も検出していなければ 404） |
| GET | `/api/my/recordings/{recordingId}/sound-marks?kind=EAR_KISS` | 録画に付いた耳キスの印（全員の分）を位置の順に返す（ログインしていれば誰でも使える。以下の `/api/my/**` も同じ） |
| POST | `/api/my/recordings/{recordingId}/sound-marks` | 印を付ける（本文 `{"kind":"EAR_KISS","positionMs":123456}`。同じ人が前後 1 秒以内に付け直すと前の印を返す） |
| DELETE | `/api/my/recordings/{recordingId}/sound-marks/{markId}` | 自分の印を消す（ほかの人の印は 404） |
| GET | `/api/my/recordings/{recordingId}/sound-candidates?kind=EAR_KISS` | 検出器が付けた今の版の候補と、今の版の検出の状態（`state`: `PENDING`・`DONE`・`FAILED`・`UNSUPPORTED`。`UNSUPPORTED` は 6 時間を超えて検出しない録画） |
| PUT | `/api/my/recordings/{recordingId}/sound-candidates/{candidateId}/verdict` | 候補に答える（本文 `{"verdict":"CONFIRMED"}` が耳キス、`"REJECTED"` がちがう、`null` が取り消し） |

`/api/admin/tables` 以下は任意のテーブルの任意の行を書き換えられるため、管理者（ADMIN）だけが使えます（`SecurityConfig`）。
監査ログ・ログイン利用者・招待のテーブル（`AUDIT_LOGS`・`APP_USERS`・`INVITATIONS`）は、証跡の改ざんと秘密
（パスワードのハッシュ・再設定と招待のトークン・利用者の Webhook）の露出を防ぐため、一覧にも出しません。

### ログ

監視ログはチャンネルごとに分かれて出力されます。

```bash
ls logs/channels/
```

特定のチャンネルだけを追いたいときは、そのファイルを見れば他チャンネルの出力に埋もれません。

保持の期間と上限は次のとおりです（`src/main/resources/logback-spring.xml`）。

- `logs/service-app.log`（Spring・Hibernate・Tomcat を含むサービス全体の INFO 以上）… 日ごとに `logs/service-app.<日付>.log` へ回し、14 日・合計 500MB まで残ります。
- `logs/channels/` … チャンネルごとに 14 日・100MB まで残ります（上限はチャンネルごとで、全体の上限ではありません）。
- `logs/service.log`（標準出力）は起動のたびに回るので、起動直後の失敗を見るのに使います。長い期間の記録は `service-app` 側を見ます。

画面・API の操作（リクエスト）の処理中に出た行には、本文の先頭に相関ID が `[3f2a9c1e-0b7d-…] ` の形で付きます
（巡回など、リクエストの外で出た行には付きません）。同じ相関ID は監査ログ画面の「相関ID」列と、応答ヘッダー
`X-Request-Id` にも載ります。監査ログで気になる操作を見つけたら、その相関ID でログを探すと、その操作の処理中に
何が起きたかを追えます。

```bash
grep -r "<相関ID>" logs/
```

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
- 取るたびに、zip が壊れていないか（`unzip -t`）と、一時ディレクトリへ展開して読み取り専用で開き、
  利用者（`app_users`）を数えられるかを確かめます。成功すると
  `バックアップしました: data/backups/monitor-<日時>.zip（戻せることを確認: 利用者 <人数> 人）` と出します。
- 失敗すると 0 以外の終了コードで終わり、理由を標準エラーに出して、`.env` の `DISCORD_WEBHOOK_URL`
  （`bin/health-watch.sh` と同じ宛先）へ「バックアップに失敗しました」と知らせます。
  確かめられなかった控えは消し、古い控えは消しません。
- H2 のクライアントは、動いているサービスの jar（`run/youtubeLiveMonitor.jar`。無ければ `build/libs/*.jar`）から
  取り出して使うので、サービスと同じ版になります。
- 録画ファイルは容量が大きいので対象外です。
- 控えは DB と同じディスクにあるので、ディスクごと壊れると一緒に失われます。別のディスクへもコピーすると安全です。

毎日 4 時に取る cron の例（`crontab -e` で登録）:

```
0 4 * * * cd <リポジトリ> && bin/backup.sh >> logs/backup.log 2>&1
```

cron の `PATH` は短く、`java` が見つからないことがあります。そのときは `logs/backup.log` に
`java が見つかりません` と残り、Discord へ知らせます。crontab の先頭に `PATH=<java のあるディレクトリ>:/usr/bin:/bin` を書いてください。

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
bin/api.sh GET /api/notifications
```

履歴が 1 件も無ければ検知の段階で止まっています。該当チャンネルのログを確認してください。

### 同じ配信の通知が来ない

仕様です。同じ配信に対しては 1 度しか通知しません。
判定には「最後に通知した動画 ID」を使っており、配信が変われば ID も変わるため次の配信では通知されます。
再通知を試したい場合は該当チャンネルの `LAST_NOTIFIED_VIDEO_ID` を空にしてください。

```bash
bin/api.sh PUT /api/admin/tables/CHANNELS/1 '{"LAST_NOTIFIED_VIDEO_ID":null}'
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

**Twitch だけは既定で 720p までに抑えます**（`MONITOR_RECORDING_TWITCH_MAX_HEIGHT=720`）。Twitch は配信者が
送った映像のまま（ソース画質）で配られるため容量が大きくなりやすいからです。自動録画と URL 指定のダウンロード・
端末保存の両方に効き、`0` にすると上限なしになります。どちらの上限でも、上限以下の形式が無い配信は
録画を諦めずに最高画質で保存します。

**空き容量が `MONITOR_RECORDING_MIN_FREE_GB`（既定 20GB）を下回ると、新しい録画・動画のダウンロード・端末保存を始めません。**
録画を見送ったときは、ログに `空き容量がしきい値を下回っているため録画を始めません` が出て、管理者の Discord
（`DISCORD_WEBHOOK_URL`）へ 1 度知らせます（空きが戻ってからまた下回ると、もう 1 度知らせます）。
録画中に空きがしきい値の 1/4（既定 5GB）を下回ると、そのとき録画中の録画をすべて止めます（ディスクが満杯になって DB まで止まるのを防ぐため）。
「録画が始まらない」ときは、まずこれを確かめてください。`0` にすると確かめません。

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

まず yt-dlp を更新してください（上の「yt-dlp の更新」）。

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

### 管理者のパスワードを忘れた

`.env` の `ADMIN_PASSWORD` を書き換えて再起動しても戻りません。`.env` の値を使うのは、管理者が 1 人もいないときの
初回の作成だけです（画面で変えたパスワードを、再起動で消さないため）。DB管理画面もログイン利用者のテーブルは扱いません。

リポジトリの直下で次を実行し、表示に従って新しいパスワードを入力します（端末なら入力した文字は画面に出ません）。
サービスが動いたままで実行できます。

```bash
bin/monitor.sh set-password -u admin -p
```

- `-u` はログイン ID です。間違えると「利用者が見つかりません」と一緒に、管理者のログイン ID の一覧が表示されます。
- `-p` の後ろにパスワードを書かないでください（シェルの履歴に残るため、書くとエラーになります）。
- パスワードの要件は画面と同じです。管理者は 8 文字未満（`admin` など）にもできます。一般利用者は 8 文字以上です。
- 変えると、その利用者のログイン中の画面と「ログインしたままにする」は、すべての端末で無効になります。
- 思い出そうとしてログインに 5 回続けて失敗していた場合、ログインは最後の失敗から 15 分のあいだ一時制限されたままです
  （このコマンドでは解除されません）。「ログインを一時制限しています」と出たら、時間をおいて新しいパスワードでログインしてください。
- 監査ログには「パスワード変更」として残ります（利用者と IP は `-`、詳細は `CLI（set-password）`）。
- `bin/api.sh` は `.env` の `ADMIN_USERNAME` / `ADMIN_PASSWORD` でログインします。画面かこのコマンドで管理者の
  パスワードを変えたら、`.env` の `ADMIN_PASSWORD` も同じ値に書き換えてください。書き換えないと、`bin/api.sh` が
  「ログインに失敗しました」で止まります。アプリの再起動は要りません。

## 参考リソース

- [Spring Boot 公式ドキュメント](https://spring.io/projects/spring-boot)
- [YouTube API v3 リファレンス](https://developers.google.com/youtube/v3/docs)
- [Discord Webhook ドキュメント](https://discord.com/developers/docs/resources/webhook)
- [Java 21 ドキュメント](https://docs.oracle.com/en/java/javase/21/)

## ライセンス

このプロジェクトは学習目的で自由に使用・改変できます。
