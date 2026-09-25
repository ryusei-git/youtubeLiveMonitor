# 踏み抜きやすい落とし穴

このプロジェクトで**実際に壊れたこと**と、その再発を防ぐための決まり。
開発ガイド（`AGENTS.md` / `.claude/CLAUDE.md`）から切り出した。

**触る領域に該当する項目だけ読めばよい。** 索引は開発ガイド側にある。
毎回全文を読む必要はないが、<b>該当する領域を触るなら着手前に読むこと</b>。
ここに書いてあるのは、読まずに踏んで時間を溶かした実例そのもの。

新しく事故を踏んだら、ここに追記する。書くのは「何が起きたか」ではなく
**「なぜそうしなければならないか」**。何が起きたかはコードを読めば分かるが、
判断の理由は書かなければ失われる。


### 「配信していない」と「判定できなかった」を必ず区別する

`LiveStreamDetector.detectLiveStream()` は `LiveStreamDetection`（LIVE / NOT_LIVE /
DETECTION_FAILED）を返す。両者を同じ結果（以前は `Optional.empty()`）にまとめると、
**YouTube 側の仕様変更で検知が壊れてもアプリは平常運転に見える**というサイレント故障になる。
判定失敗時は `MonitoredChannelRepository.recordDetectionFailure()` を使い、
`currentlyLive` には触れないこと（分からないものを `false` と書かない）。
連続失敗はダッシュボードの警告とチャンネル一覧の「判定失敗」表示に出る。

### 通知の再試行には上限がある

通知失敗時に「通知済み」にしないことで次サイクルの再送信になる仕組みだが、
`LiveStreamPollingScheduler.MAX_NOTIFICATION_ATTEMPTS` で上限を設けている。
Webhook 設定ミスのような直らない失敗で、配信が続く限り毎サイクル試行して
失敗履歴が際限なく増え、クォータも消費し続けるのを防ぐため。
回数が 0 に戻るのは配信が終わったとき（NOT_LIVE を検知）**だけではない**。
**前回と別の配信を検知したときにも戻す**（実際に起こりうる指摘として挙がった）。
巡回間隔内での枠の差し替えやアプリ停止中の切り替えでは NOT_LIVE を一度も挟まずに
次の配信へ移ることがあり、そこを通ると前の配信の失敗回数がそのまま適用されて、
新しい配信への通知が一度も試されないまま終わるため。
ただし**前回の配信 ID が分からない（`null`）ときは戻さない**
（分からないものを「別の配信だ」と断定しない）。

### 監視ループから `save(entity)` を呼ばない

`LiveStreamPollingScheduler` が扱うエンティティは巡回開始時に読み込んだもの。
`save` は全カラムを書き戻すため、その間に管理 API 経由で変更された
チャンネル名などを古い値で消してしまう（実際に発生した）。
更新には `MonitoredChannelRepository` の個別 UPDATE メソッドを使う。

### ID が 2 種類ある

- `MonitoredChannel.id` … DB の主キー。CLI の `channel remove -i` で指定するのはこちら
- `MonitoredChannel.youtubeChannelId` … YouTube の `UC...` で始まる ID

### 「待機所」（配信開始前の予約枠）を配信中と誤検知する（実際に発生した）

`/live` の canonical は、実際に配信中の動画だけでなく、**配信開始前の待機所ページにも
watch URL を向ける**。そのため canonical だけで判定すると、配信開始の146日も前から
「配信中」と誤判定し、Discord に誤通知が飛んだ（実際に発生した）。
`LiveStreamDetector` はレスポンス HTML に埋め込まれた JSON 内の `"isUpcoming":true` の有無で
これを見分けている（待機所のページにだけ含まれることを実機で確認済み）。
YouTube の内部実装に依存した判定なので、他の非公式手法と同様 HTML 構造の変化には弱い。

### YouTube の「ハンドル」（`@foo`）は本来のチャンネルIDと別物

利用者がブラウザのURLからコピーしがちな `@foo` 形式は、`LiveStreamDetector` が使う
`/channel/{id}/live` という URL パターンでは機能せず 404 になる（実際に発生した）。
`YouTubeStreamPlatform.normalizeChannelInput()` が登録時に
`YouTubeApiClient.resolveHandleToChannelId()`（`channels.list(forHandle=...)`, クォータ1）で
自動的に本来のIDへ解決してから保存するため、DB には常に `UC...` 形式だけが入る。
この解決ロジックを迂回して直接 DB や `MonitoredChannelRepository` にハンドルを保存しないこと。

**`@` の有無でハンドルかどうかを判断してはならない（2回発生した）。**
利用者は `@` を落として入力する。`mikenekoko`・`ShiroganeNoel` の2件で実際に起き、
どちらもそのままチャンネルIDとして保存されて 404 を返し続けた。
`YouTubeChannelInputParser` は**チャンネルIDの形（`UC` で始まる24文字、
`UC[A-Za-z0-9_-]{22}`）に一致するかどうか**で判定し、一致しなければハンドルとみなして
`@` を補う。「UCで始まる」だけを条件にすると `UC` で始まるハンドルを取りこぼすので、
長さと文字集合まで見ること。

この事故は「判定失敗」として画面に出るので気づけるが、気づくまでその配信者の通知は
一切飛ばない。テストで `UCxxxxxxxx` のような**桁数の合わないダミーIDを使うと、
この判定に引っかかって落ちる**（実際にテスト2件が落ちた）。本物と同じ24文字を使うこと。

### Twitch の `/helix/streams` は `id` に対応しておらず、`?id=` は常に無視されて人気配信の上位が返る（実際に発生した）

Twitch の `GET /helix/streams` が受け付ける絞り込みは `user_id` / `user_login` / `game_id` /
`type` / `language` だけで、**`id`（配信 ID）には対応していない**。`?id=` を付けても
一致・不一致にかかわらず**常に無視され、人気配信の上位 20 件が返る**（エラーにはならない）。
実機で `?id=316500778354` → 20 件・対象を含まず、`?user_id=787330390` → 1 件・対象を含む、を確認した。

以前は「一致しない `id` だけが無視される」と誤解し、通知の詳細を `?id=` で引いていた。
そのため**人気上位に入らない配信は毎回「配信終了済み」扱いになり、Twitch の通知がほぼ送られていなかった**。
応答の先頭をそのまま採用すれば、無関係な配信者のタイトル・サムネイル・URL で通知する事故にもなる。

**配信を特定するときは `user_id` で問い合わせ、応答の配信 ID を照合する**
（`TwitchStreamPlatform.fetchDetails()`）。照合で一致しなければ「配信終了済み」として空を返す。

### Twitch はログイン名ではなくユーザーIDで監視する

Twitch には「ログイン名」（`twitch.tv/foo` の `foo`）と「ユーザーID」（数値）があり、
**ログイン名は配信者本人が変更できる**。ログイン名で監視していると、改名された瞬間に
問い合わせが空応答を返し、アプリは「オフラインです」と言い続けて永久に気づけない
（「配信していない」と「判定できなかった」を区別するのと同じ理由）。
`TwitchStreamPlatform.normalizeChannelInput()` が登録時に必ず不変のユーザーIDへ解決する。

ただし**視聴 URL にはログイン名が要る**（ユーザーIDでは開けない）。次項につながる。

### 視聴 URL は検知結果（`LiveStreamDetection.watchUrl`）が運ぶ

URL の組み立て方はプラットフォームごとに根本的に違い、**配信の識別子だけからは
導けないことがある**。YouTube は `watch?v={動画ID}` で完結するが、Twitch の視聴 URL は
`twitch.tv/{ログイン名}` の形で、配信 ID からは作れない。ログイン名は検知時の API 応答に
含まれている。DB にも `MonitoredChannel.channelLogin` があるが、これはチャンネルページへのリンク用に
動画の収集のたびに取り直す値で、改名の直後は古いことがある。そのため視聴 URL は
**検知した者がその場で、検知時の API 応答のログイン名から組み立てる**。

そのため `StreamPlatform` に `watchUrl(videoId)` は置いていない（一度置いたが Twitch で
成立せず削除した）。録画は `LiveStreamDetection.watchUrl()`、通知は
`LiveStreamDetails.watchUrl` を使う。新しいプラットフォームを足すときは、
配信中と判定した結果に視聴 URL を必ず入れること。

`twitch.tv/videos/{配信ID}` という形も一見それらしく見えるうえ、**Twitch は SPA なので
存在しないページでも HTTP 200 を返す**。ステータスコードだけで動作確認すると誤りに気づけない
（`/helix/videos?id=` が空を返すことで実体が無いと確認できる）。

### チャンネル登録は必ず `StreamPlatform.normalizeChannelInput()` を通す

利用者の入力（URL・ハンドル・ログイン名・ID）をどう解釈するかはプラットフォームごとに違う。
`MonitoredChannelService.register()` は `StreamPlatformRegistry` 経由で各実装に解決を任せ、
**自分ではプラットフォームごとの分岐を持たない**。ここで分岐を書き始めると、
プラットフォームが増えるたびにこのメソッドが膨らむ。

保存される識別子が「監視に使える不変の ID」であることは、この解決処理が保証している。
迂回して直接リポジトリへ保存しないこと。

### `cli` プロファイルで作られない Bean に依存するコントローラーには `@Profile("!cli")` を付ける（実際に発生した）

`LiveStreamPollingScheduler` は `@Profile("!cli")` で CLI モードでは生成されない。
CLI モードは Web サーバーを起動しない（`WebApplicationType.NONE`）が、
**`@RestController` の Bean 自体はコンポーネントスキャンで作られる**ため、
依存する Bean が無いと「Bean が見つからない」で **CLI が丸ごと起動できなくなる**
（`MonitoringController` で実際に発生し、`channel list` すら実行できなかった）。

Web でしか使わないコントローラーが `!cli` の Bean に依存する場合は、
コントローラー側にも `@Profile("!cli")` を付けること。

### `build/libs` の jar を直接動かすと、稼働中の `./gradlew build` でプロセスが壊れる（実際に発生した）

実行中の JVM は jar から**必要になった時点でクラスを読み込む**。稼働中に jar を差し替えると、
まだ読み込んでいなかったクラスが見つからなくなり、それまで正常だったリクエストが突然
`ClassNotFoundException`（例: `ch.qos.logback.classic.spi.ThrowableProxy`）で失敗し始める。
API が応答しなくなって原因を探すことになった。

正しい反映の手順（build → restart）でも毎回踏んでいた。build が jar を上書きした後の restart で、
止める途中のクラスを読めず（2026-09-25）、正常終了の待ち 30 秒を使い切って**毎回 `kill -9`** になっていた。
`kill -9` では H2 のクローズなどの終了処理が走らない。

```
java.lang.NoClassDefFoundError: org/springframework/boot/web/server/GracefulShutdownCallback
Exception in thread "Thread-2" java.lang.NoClassDefFoundError: org/h2/mvstore/db/LobStorageMap$LobRemovalInfo
```

そのため `bin/service.sh start` は `build/libs` の jar を `run/youtubeLiveMonitor.jar` にコピーし、
**コピーの方を動かす**（#161）。build は稼働中のプロセスの jar に触れなくなり、ビルドだけして
restart しなくても壊れはしない（変更が反映されないだけ）。反映するときは今までどおり
**build → `bin/service.sh restart` の両方を行う**。
（画面ファイルを直したときに `restart` だけでは反映されないのと対になる注意点。）

### ログ設定は `logback-spring.xml` のみ

`application.yml` にも書くと二重管理になる。
例外はテストだけで、`src/test/resources/logback-test.xml`（コンソールのみ）を使う。本番の設定のままテストすると、作業ディレクトリの `logs/channels/` にテストのチャンネルのファイルができ、本番のシステムログにテストの行が混ざっていた（#187）。このファイルを `src/main/resources` に移さないこと（本番でも優先され、チャンネル別のログが出なくなる）。
また `<springProfile>` でログレベルを切り替える際、特定のロガーに明示レベルを設定していると
root のレベル設定が効かない（明示 level は additivity では止まらない）。

### ログ書式を変えるならパーサーも直す

`logback-spring.xml` の出力形式と `ChannelLogReader.LOG_LINE_PATTERN` は対になっている。
片方だけ変えるとログ API が解析できなくなる。
ログ画面のレベル絞り込みの選択肢もこのパターンの抽出結果（`LogEntry.level`）から作っているため、
形式を変えると「選択肢が常に空になる」という形で先に壊れる。

### 一覧のセルは折り返さない

`th, td` に `white-space: nowrap` を掛けている。折り返すと行の高さが不揃いになり
一覧全体の視認性が大きく下がるため。新しく表を追加するときは次のどちらかで逃がすこと。

- 表ごと横スクロールさせる … 表を `<div class="table-scroll">` で包む
  （ページ全体は横に広がらず、表の中だけがスクロールする）
- 開いて全文を見せる … `common.js` の `collapsibleCell(value)` を使う。
  長い値や複数行の値だけを `<details>` で折りたたみ、既定では 1 行に収める
  （DB管理画面だけは任意の値が入るうえダブルクリック編集と競合するため、
  `tables.js` の `showValue()` で末尾を省略＋`title` に全文、という別方式にしている）

行クリックに反応する表（録画一覧）で `collapsibleCell` を使う場合は、
`details` のクリックが行クリックへ伝播しないよう `stopPropagation()` すること。

### ログ画面の選択肢は決め打ちにしない

レベルの選択肢は `ChannelLogReader` がログファイルの中身から集めた値
（`LogViewResponse.availableLevels`）だけを返す。ERROR/WARN/INFO/DEBUG を固定で並べると、
そのログには 1 件も無いレベルまで選べてしまい「選んだのに 0 件」になるため。
集計は必ず<b>絞り込み前</b>の全行から行うこと（絞り込み後から集めると、一度 ERROR に絞った時点で
選択肢が ERROR だけになり他のレベルへ戻れなくなる）。件数制限も絞り込みの後に適用する。

### 録画の成否は終了コードではなく「完成ファイルの有無」で決める（実際に発生した）

`yt-dlp` の終了コードを成否の判断に使ってはならない。長時間のライブ録画では配信終了間際に
最後の数フラグメントを取得できない（`ERROR: Did not get any data blocks`）ことが珍しくなく、
yt-dlp はそれらをスキップして残りを最後までダウンロードし、**マージも正常に終えて
再生可能なファイルを完成させたうえで終了コード 1 を返す**。
実際にこれで 1 時間 30 分・736MB の正常なファイルが「失敗」と記録され、画面から
再生できなくなった。数時間ぶんの録画を最後の数秒のために丸ごと失敗扱いにするのは実態に合わない。

判断材料は `RecordingSalvager.ensurePlayable(Path)` が返す結果だけ。終了コードはログの文面を
変えるためにしか使わない（`StreamRecorder.recordOutcome()`）。`RecordingReconciler` も同じ基準で、
`RECORDING` のまま止まったものに加え、**`FAILED` なのに再生できるファイルが作れるものを救済**する
（ファイルが実在するときだけ変わるので安全）。

### 途中で終わった録画は拡張子が `.mp4` でも再生できない（実際に発生した）

`yt-dlp` は**ダウンロードを終えてから最後にまとめて MP4 へ詰め替える**。そのため配信の途中で
録画が止まると、映像そのものは残っているのにブラウザでは再生できない形で残る。実機で確認した内容:

| 状況 | ディスク上の実体 | `<video>` |
|---|---|---|
| 正常終了 | 本物の MP4 | ○ |
| 途中で停止（Twitch） | 拡張子は `.mp4` だが**中身は MPEG-TS** | **×** |
| 途中で停止（YouTube） | 映像 `.f137.mp4` と音声 `.f140.m4a` が別のまま | **×** |

**`ffprobe` で中身を見ないと気づけない。**拡張子を信じると「再生できないのに完了扱い」になる。

`RecordingSalvager` が `ffmpeg -c copy` で詰め替え・結合して救済し、`PARTIAL` として記録する
（再エンコードしないので速い。実測 40分・800MB で約1.4秒）。

**詰め替えは録画プロセスが確実に終わってから呼ぶこと。** 出力ファイルを置き換えるため、
書き込み中に走らせると録画そのものを壊す。`StreamRecorder` は `waitFor()` の後、
`RecordingReconciler` は既存の 2 段階確認の後に呼んでいる。

### 録画中にアプリを再起動すると「録画中」のまま更新されなくなる（実際に発生した）

録画完了の記録は、録画開始時に起動する仮想スレッド（`StreamRecorder.awaitCompletion`）が
担っている。アプリを再起動するとこの仮想スレッドは失われるため、`yt-dlp`自体は無事に
完了しても DB の `RECORDING` 状態を更新する者がいなくなる（完成ファイルは存在するのに
録画一覧から再生できないままになる）。

`RecordingReconciler.reconcileOrphanedRecordings()` が `RECORDING` 行を完成ファイルの
有無で完了・失敗に補正する。配信の巡回と同じ間隔で、巡回とは別の仮想スレッドから呼ばれる
（巡回の中で呼ぶと `ffmpeg` の詰め替えを待つ間、全チャンネルの検知が止まるため。#252）。

補正の対象外とする判定は **2 段階**。`StreamRecorder.isRecording()`（このアプリが追跡中か）
だけでなく、`ProcessLauncher.isRunningWithCommandLineContaining()`（OS 上に yt-dlp が
まだ生きているか）も見る。**後者が欠かせない**——録画プロセスは JVM を止めても生き残るため、
前者だけだと再起動直後に「実際はまだ録画中」のものを失敗と誤判定し、
その後 yt-dlp が完成させても永久に失敗表示のままになる。

**`StreamRecorder` とは別クラスにしている理由**: 判定には
`StreamRecorder.isRecording(videoId)`（今まさに追跡中かどうか）が要るが、
`StreamRecorder` は既に `RecordingHistoryService` に依存しているため、逆方向の依存を
足すと循環参照になる。どちらにも依存しない `RecordingReconciler` に切り出すことで解消している。

### 外部プロセスの出力を JVM へのパイプにすると、再起動で yt-dlp が止まる（実際に発生した）

`ProcessBuilder` の既定では、子プロセスの標準出力・標準エラーは JVM へのパイプになる。
JVM が止まるとパイプの読み手がいなくなり、子プロセスは次に出力した時点で書き込みに失敗する
（yt-dlp は Python 製なので `BrokenPipeError` になる）。プロセスそのものは JVM と別でも、
出力の行き先が JVM に縛られているかぎり、JVM の停止に巻き込まれる。
止まるのは**出力を書いたプロセス**なので、再現を試すときは子プロセス自身が書くコマンドを使う
（`sh -c 'while :; do date; sleep 1; done'` では毎回の `date` が止まるだけで `sh` は残り、
「パイプでも止まらない」と見誤る。`echo` のようにシェル自身が書くなら `sh` ごと止まる）。

2026-09-24 21:22:32 の `bin/service.sh restart` で、録画中だった `rmJOQEMzZlk` の映像
（`rmJOQEMzZlk.f299.mp4`）が 21:22:43 で止まり、最後の結合（映像＋音声 → mp4）も行われなかった。
`--live-from-start` の映像側のダウンロードとメインの処理が `BrokenPipeError` で終わり、
音声（`f140`）のスレッドだけが翌 0 時台まで取り続けていた。チャンネルログの `[yt-dlp]` 行も
21:22:44 で途切れ、yt-dlp の標準出力は `/dev/null` に差し替わっていた
（yt-dlp が `BrokenPipeError` を受けたときの処理）。

**JVM より長く動き続けるべきプロセス（録画）の出力はファイルへ向ける。**
`ProcessLauncher.launch(List, Path)` は標準出力と標準エラーをまとめてファイルへ追記させる
（録画は `logs/yt-dlp/<動画ID>.log`。録画フォルダの下に置くと録画ファイルの走査・削除・
孤立ファイルの判定に混ざるため `logs/` に置く）。これなら JVM を止めても yt-dlp は最後まで録り、
結合まで終える。完了・失敗の記録は前項の `RecordingReconciler` が補正する。
短時間で終わり、出力を読んで使うもの（`ExternalCommandRunner`・`NativeDirectoryPickerService`）は
従来の `launch(List)` のままでよい。

yt-dlp の出力をファイルへ流すなら `--no-progress` を付ける。進捗行は出力のほとんどを占め
（チャンネルログへ流していたときは 72,041 行中 71,647 行）、ローテーションの無いファイルが
1 本で数十 MB になる。


### 録画中かの判定は、動画 ID を含むだけの `grep`・`tail` で誤検知する（実際に発生した）

`ProcessLauncher.isRunningWithCommandLineContaining()` は以前、OS 上の**すべて**のプロセスのコマンドラインを見て、
文字列を含むものが 1 つでもあれば「まだ動いている」と答えていた。2026-09-25、録画 28 の yt-dlp を止めた直後に、
動画 ID を含む `grep` を実行中のシェルがあったため、`RecordingReconciler` が「録画プロセスは稼働中」と判断して補正を見送った
（シェルが終わってから再実行すると補正された）。`tail -f logs/yt-dlp/<動画ID>.log` で様子を見ているだけでも同じことが起きる
（#152 で動画 ID の名前のログができたため）。孤立ファイルの掃除（`RecordingFileService`・`OrphanedPreviewService`）も見送られる。

**対策（#160）**: 照合の対象を、このアプリが起動する種類のプロセス（実行ファイルが `yt-dlp`・`ffmpeg`・`ffprobe`、
または `python` で引数に `yt-dlp` を含むもの）に限る。yt-dlp だけに絞らないのは、`RecordingSalvager` の ffmpeg が
録画ファイルを詰め替えている間も掃除から守るため。
### 巡回を起動する経路を増やすなら排他を通す

`LiveStreamPollingScheduler` の巡回は定期実行（`fixedDelay`）と手動実行（`pollNow()`、
画面の「今すぐチェック」）の 2 経路がある。`fixedDelay` が防ぐのは定期実行同士の重複だけなので、
両者が同時に走ると同じ配信に対して録画プロセスが二重起動しうる
（`StreamRecorder.startRecording` の「録画中か」の確認と起動の間に割り込む余地がある）。
そのため実処理は `runPollingCycle()` に集約し、`pollingInProgress`（`AtomicBoolean`）で
1 本しか走らないようにしている。巡回の起動経路を追加するときは必ずこのメソッドを通すこと。

### フィルターは通知と録画の両方に効き、タイトルとカテゴリの両方を見る

`MonitoredChannel.matchesFilter()` は通知・録画の**両方**の判定に使う。
当初は録画だけを絞る目的だったため、DBのカラム名は `recordTitleKeywords` のままになっている
（`ddl-auto: update` はリネームしないので変更していない）。名前に引きずられて
「録画専用」「タイトル専用」と読み違えないこと。

判定材料は**タイトルと配信カテゴリの両方**（どちらかに含まれれば対象）。
Twitch にはカテゴリという独立した項目があり、内容の申告がそちらに寄るため。
実機で Twitch の「ASMR」カテゴリの配信10件を調べたところ、**2件はタイトルに ASMR を
含んでいなかった**（例:「IM SLEEPING【SUKITHON DAY 19】」）。タイトルだけ見ると取りこぼす。
カテゴリは `LiveStreamDetection.category()` で運ぶ（YouTube には相当する項目が無く常に `null`）。

判定は `LiveStreamPollingScheduler.shouldNotifyByTitle()` で、**詳細取得より前**に行う。
対象外と分かった時点で打ち切ることで `videos.list`（クォータ1）を消費せずに済ませている。
この順序を入れ替えないこと。

通知判定では `lastNotifiedVideoId` を更新しない。配信途中でタイトルにタグが足された場合に
次の巡回で拾えるようにするため（タイトルは検知時のHTMLから得ており追加のクォータは不要なので、
毎サイクル評価し直しても負荷は増えない）。

**フィルター設定済みでタイトルが取れなかった場合は WARN でログに残す。**
この分岐は「タグが無い配信」ではなく「YouTube の構造変更でタイトル抽出が壊れた」可能性があり、
黙って通知が止まると気づけない（「配信していない」と「判定できなかった」を区別するのと同じ理由）。

### クォータを消費する API を監視ループに入れない

`YouTubeApiClient.searchChannelsByName` は 1 回 100 消費（1 日の上限は既定 10,000）。
定期実行する処理からは絶対に呼ばない。

### 配信タイトルは `/live` ページの HTML から取得できる（API を叩かなくてよい）

`LiveStreamDetector` が配信中判定のために取得している `/live` ページの HTML には、
`<meta name="title" content="...">` として配信タイトルも含まれている。
YouTube Data API の `videos.list`（クォータ1消費）でも取れるが、毎サイクル呼ぶのは
クォータ的に厳しいため、追加の通信をせずこちらから取得すること
（`LiveStreamDetector.detectLiveStream()`が返す`LiveStreamDetection.title()`参照）。

### 途中で終わった録画は、コンテナの長さと映像の長さが食い違う（実際に発生した）

`RecordingSalvager` が詰め替えた `PARTIAL` の録画では、**映像と音声で取得できた量が違う**まま
1 つのファイルになる。実機で、`format=duration` は 3738 秒と申告するのに
**映像ストリームは 311 秒しか入っていない**録画があった（音声だけ最後まで取れていた）。

このため、コンテナの長さを基準にサムネイルの切り出し位置（再生時間の10%）を決めると
**映像が存在しない位置を指し、1枚も切り出せずサムネイルが永久に作られない**。
`ffmpeg` はこのとき**終了コード 0 のまま何も出力しない**ので、終了コードでは気づけない
（録画の成否を終了コードで判断しないのと同じ理由。成否は生成物の有無で見る）。

`VideoMetadataExtractor.extractThumbnail()` は、失敗したときだけ
`extractVideoStreamDurationSeconds()`（`-select_streams v:0`）で映像の長さを調べ直し、
それでも駄目なら先頭で切り出す。**毎回プローブしないのは、正常な録画に余計な
`ffprobe` 起動の費用を払わせないため**（この順序を入れ替えない）。

### 録画ファイルの配信は自前のストリーミング処理を書かない

`<video>` タグのシークバー操作には HTTP Range リクエスト（206 応答）への対応が要る。
`RecordingResourceConfig`（`WebMvcConfigurer`）で `recordings/` ディレクトリを
`/recordings/**` として静的リソース配信しているのは、Spring の静的リソース機構が
Range リクエスト対応とパストラバーサル対策を最初から持っているため。独自の
コントローラでバイト列を読み書きするストリーミング処理は書かないこと。

### DB管理画面の論理名は対応表に無いテーブル・カラムでも壊れないようにする

`DatabaseTableService` の `TABLE_LABELS`/`COLUMN_LABELS` はテーブル名・カラム名の
日本語表示用の対応表。この画面は本来「任意のテーブルを閲覧できる」ことが目的
（`DatabaseMetaData` でスキーマを動的に取得している）なので、対応表に無い
（今後追加される）テーブル・カラムが来ても、物理名をそのまま表示するだけで動作は壊さない
（`tableLabel()`/`columnLabels()` の `getOrDefault` 参照）。新しいエンティティを追加したときは
対応表を更新した方が親切だが、更新を忘れても画面が壊れることはない。

### フロントエンドの共通処理は `common.js` に集約する

`collapsibleCell`（長い値の折りたたみ）、`formatFileSize`、`datetimeCell`/`bindDatetimeCells`
（日時の簡潔表示とクリックでの精密表示切り替え）、`toggleChannelIdReveal`
（チャンネル名クリックでのID表示切り替え）、`videoLink`/`channelLink`（YouTube への外部リンク化）
は複数画面で使うため `common.js` に置いている。
新しい画面を作るときも、同じ表示パターン（長い値・日時・チャンネルID）が必要になったら
個別実装せずこれらを再利用すること。

### ディスク使用量は DB ではなく実ファイルを走査して求める

`RecordingFileService.calculateUsage()` は `Recording.fileSizeBytes` の合計ではなく
`recordings/` 配下を実際に走査する（`DirectorySizeUtils.sizeOf()`）。録画が失敗すると DB 上は完成ファイルの
記録が無いのに、映像・音声の断片ファイル（`{動画ID}.f137.mp4` 等）だけがディスクに残る
（実際に発生した）。DB 集計だとこれを取りこぼし、利用者が「アプリ上は大した使用量じゃないのに
ディスクが減っていく」という不可解な状態に陥る。ディレクトリ名がどの登録チャンネルとも
一致しない場合は削除済みチャンネルの孤児ファイルとして扱い、`(削除済みチャンネル)` と表示する
（チャンネル削除は通知履歴・録画履歴を連鎖削除するが、録画ファイル本体までは消さない設計のため）。

### 削除済みチャンネルの録画を消すときは録画中のプロセスを避ける

チャンネルを監視対象から削除しても、通知履歴・録画履歴は DB の連鎖削除で消えるが
**録画ファイル本体はディスクに残る**（誤って消さないための設計）。これをまとめて片付けるのが
`OrphanedPreviewService`（`GET /api/recordings/orphaned/preview` で削除候補を確かめ、
`DELETE /api/recordings/orphaned/confirmed` で確認したファイルだけを消す）。

対象は「録画履歴にどの動画 ID も残っていないファイル」。ただし
**`ProcessLauncher.isRunningWithCommandLineContaining()` で、動画 ID とチャンネル ID の両方を見て
録画中のものは除外する**。チャンネルを削除しても yt-dlp は JVM とは独立に動き続けるため、
書き込み中のファイルを消すとプロセス側がエラーになったり中途半端なファイルが残る
（yt-dlp のコマンドラインには出力先パスとして動画 ID とチャンネル ID が含まれるので判定に使える）。

**チャンネルを削除すると、そのチャンネルで録画中の yt-dlp も子孫ごと止まる（#444）。**
以前は止めておらず、録画履歴の行が消えて画面から見えないまま録り続けた（2026-09-26、削除したチャンネルの
`--live-from-start` が約 1 時間で 6.9GB を書いた）。`MonitoredChannelService.remove()` が削除の前に
`RECORDING` の動画 ID を集め、削除後に `ProcessLauncher.findYtDlpProcessesWithCommandLineContaining()` で
yt-dlp だけを探して `ProcessTermination.terminateTreeAndAwait()` で止める（仮想スレッドで、応答は待たせない）。
止めた録画のファイルは消さないので、上の孤立ファイルの削除で片付ける。
追跡中の録画スレッドは、行が無いことを見て記録も録り直しもしない（`StreamRecorder.awaitCompletion`）。

### エンティティを API に直接返さない

`dto` 配下のレスポンス型に詰め替える。
特に `NotificationHistory` は遅延読み込みの参照を持つため、そのまま返すと JSON 変換で問題が出る。

### 画面（HTML/CSS/JS）を直しても `bin/service.sh restart` だけでは反映されない（実際に発生した）

`src/main/resources/static/` 配下は `./gradlew build` で jar に**パッケージされる**。
`bin/service.sh restart` は今ある jar を再起動するだけでビルドはしないため、
CSS/JS/HTML を編集したのに restart だけで確認すると**古い内容のまま**になる
（ブラウザのキャッシュを疑って無駄に調査してしまった）。
画面ファイルを直したら `./gradlew build`（または `build -x test`）を挟んでから restart すること。

**ブラウザのキャッシュも実際に原因になる**（後日あらためて発生した）。静的リソースに
`Cache-Control` が付いておらず、ブラウザが独自判断で古い HTML/CSS を保持していた。
`application.yml` の `spring.web.resources.cache.cachecontrol.no-cache` で毎回問い合わせ
（変更が無ければ 304）させることで解消済み。録画ファイルは `RecordingResourceConfig` が
別に登録しているためこの設定の対象外で、キャッシュはそのまま効く。
なお **no-cache は次回取得以降に効く**ため、設定を入れる前に既にキャッシュされた分だけは
一度ハードリロードが要る。


### `ddl-auto: update` はカラムの削除・リネームをしない

`ddl-auto: update` はカラムの追加はするが削除・リネームはしない。
カラム名を変えた場合、古い NOT NULL カラムが残って INSERT が失敗する。
開発中は `data/monitor.mv.db` を削除して作り直すのが早い。

**ただし Spring Boot 4.1.1（Hibernate 7、#432）以降、列の型の違いは `ddl-auto: update` が ALTER する**（#206 で確認）。
`columnDefinition` や Java の型を変えると、次の起動で本番 DB の列の型が黙って変わる。
変える前に `bin/backup.sh` で控えを取り、DB の複製で起動して起動ログの `alter table` と値が残ることを確かめる。

### enum の列挙子を増やすと既存 DB で全更新が失敗する（実際に発生した）

`@Enumerated(EnumType.STRING)` のフィールドに `columnDefinition` を書かないと、
Hibernate は H2 の**ネイティブ ENUM 型**として列を作る。

```
STATUS | ENUM     ← 作成時の値しか許さない
```

この型は**テーブル作成時点の値だけを許す**ため、後から列挙子を追加しても
`ddl-auto: update` は型を更新せず、次のエラーで**その列に関わる全ての読み書きが壊れる**。

```
Value not permitted for column "('COMPLETED', 'FAILED', 'RECORDING')": "PARTIAL"
```

`Recording.RecordingStatus` に `PARTIAL` を足した際に実際に発生し、巡回 API が 500 を返した。

**対策**: enum のフィールドには必ず `columnDefinition = "varchar(16)"` を書く
（`MonitoredChannel.platform` と `Recording.status` 参照）。単なる文字列にしておけば、
列挙子を増やしても DB 側の変更が要らない。
既に ENUM 型になっている列は、`columnDefinition = "varchar(N)"` を付けて起動すれば Hibernate が VARCHAR に変える（#206）。

**既にネイティブ ENUM で作られてしまった列の直し方**（データは保持される）:

```sql
ALTER TABLE recordings ALTER COLUMN status SET DATA TYPE VARCHAR(16);
```

### 既存データがある状態で NOT NULL の boolean カラムを追加すると失敗する（実際に発生した）

`recordEnabled`（boolean, primitive）を `MonitoredChannel` に追加した際、登録済みチャンネルが
既に存在する DB では `ALTER TABLE ... ADD COLUMN record_enabled BOOLEAN NOT NULL` が
「既存行に入れる値がない」という理由で失敗し、以降すべてのクエリが
「カラムが見つからない」エラーで壊れた（カラム追加そのものが失敗し、テーブルに列が
作られないまま終わるため）。
boolean の primitive フィールドを新規追加するときは、原則として
`@Column(columnDefinition = "boolean default false")` のようにDB側のデフォルト値を
明示すること（`recordEnabled` 参照）。
