# YouTube Live Monitor - 開発ガイド

## 概要

YouTube のライブ配信を監視し、配信開始時に Discord へ通知する個人用サービス。
Java 21 / Spring Boot 3.3.3 / Gradle / H2（ファイルモード）。

セットアップ手順・API 一覧・トラブルシューティングは [README.md](../README.md) を参照。
各クラスの役割と設計判断の理由は JavaDoc に記載（`./gradlew javadoc`）。

## コードを書くときの約束

- JavaDoc の `{@link}` で Lombok（`@Getter`/`@Setter`）が生成するメソッドを参照しない
  （実際に発生した：`{@link MonitoredChannel#getLastRecordedVideoId()}` が
  `./gradlew javadoc` で「参照が見つかりません」エラーになった。javadoc ツールはソースを
  そのまま読むため、注釈処理で生成される Lombok のメソッドは存在しないものとして扱われる）。
  フィールドを直接参照する（`{@link MonitoredChannel#lastRecordedVideoId}`）か、
  手書きのメソッドだけを `{@link}` の対象にすること。

- 識別子は英語、JavaDoc とコメントは日本語。
- JavaDoc には「何をするか」ではなく**「なぜそうしているか」**を書く。
  何をするかはコードを読めば分かるが、なぜその判断をしたかは書かなければ失われる。
- `./gradlew clean build` と `./gradlew javadoc` は**どちらも警告ゼロを維持する**。
- **複数クラスで使う可能性のある処理は `util` パッケージに独立クラスとして切り出す**（状態を持たない
  static メソッド）。特定の機能に紐づく private メソッドのままにしておくと、
  同じ処理が別クラスにも必要になったときに複製されやすい。
  既存の例: `FileNameUtils`（拡張子除去）, `EpochTimeConverter`（日時変換）,
  `CaseInsensitiveMatcher`（大文字小文字を無視した一致検索）。

## タスクの進め方（Claude と Codex の共通ルール）

このリポジトリは **Claude と Codex が同時に触る**。同じファイルを同時に編集すると、
後から書いた方が相手の変更を黙って消す。実際に「ファイルが消えた」と誤診して
1時間を溶かし、通知履歴の検索機能を一度失った。以下は事故を繰り返さないための取り決め。

残作業は [Project](https://github.com/users/ryusei-git/projects/4) で管理する
（リポジトリの Issue と連動）。

### 1. 着手する前

1. **Backlog から優先度の高いものを選ぶ**（Priority: P0 > P1 > P2、同じなら番号順）。
2. **相手のラベル（`claude` / `codex`）が付いた Issue は選ばない。** どうしても触る
   必要があるなら、その Issue にコメントを残して相手の判断を待つ。
3. **`git status` と `git diff` を見る。** 相手の未コミット変更が残っていることがある。
   **見慣れない変更を「壊れている」と決めつけて戻さない**（消えたように見えるのは、
   相手の編集途中であることが多い）。
4. **細分化できないか検討する。** 1コミットで終わらない大きさなら、サブイシューに割る。
   割った親は「まとめ」として残し、実作業はサブイシューで行う。
5. **宣言する。** Status を `In progress` にし、自分のラベルを付ける。

```bash
gh issue edit <番号> --repo ryusei-git/youtubeLiveMonitor --add-label claude
# Status の変更は Project の item-edit（下の「コマンド」参照）
```

### 2. 作業中

- **1タスク＝1コミット。ついでの修正をしない。**
- **別の問題を見つけたら、その場で直さずサブイシューを作る。**
  脱線は必ずここへ逃がす。1タスクの途中で力尽きると手戻りが大きい。
- **テストは触らない。** 新規作成は専用の Issue が担当する。ただし仕様変更で既存
  テストが落ちた場合の追従は行い、**何をなぜ変えたかを Issue に書く**。

### 3. 終わったら

1. `.claude/skills/verify`（Claude）／`AGENTS.md` の完了条件（Codex）を通す。
   `clean build javadoc` 警告ゼロ、`tsc --noEmit` 型エラーゼロ、巡回と CLI が動く。
2. **ビルドしたら必ず `bin/service.sh restart` まで行う。**
3. コミットしてプッシュする。コミット文に Issue 番号を書く（`#4` のように）。
4. Status を `Done`、ラベルを外す、Issue をクローズする。

### 4. 中断するとき

**ラベルは付けたまま**、Issue に「どこまで終わったか」「次に何をすべきか」を
コメントする。ラベルを外すと、相手が壊れかけの状態を引き継いでしまう。

### コマンド

```bash
# 誰が何を持っているか
gh issue list --repo ryusei-git/youtubeLiveMonitor --label claude
gh issue list --repo ryusei-git/youtubeLiveMonitor --label codex

# Status の変更（Project: PVT_kwHOBB07r84BkZNB / Status: PVTSSF_lAHOBB07r84BkZNBzhjJ6nM）
gh project item-list 4 --owner ryusei-git --format json        # item id を引く
gh project item-edit --id <item-id> --project-id PVT_kwHOBB07r84BkZNB \
  --field-id PVTSSF_lAHOBB07r84BkZNBzhjJ6nM --single-select-option-id <option>
#   Backlog f75ad846 / Ready 61e4505c / In progress 47fc9ee4 / In review df73e18b / Done 98236657

# サブイシューの作成と親子付け
gh issue create --repo ryusei-git/youtubeLiveMonitor --title "..." --body "..."
gh api --method POST repos/ryusei-git/youtubeLiveMonitor/issues/<親>/sub_issues \
  -F sub_issue_id=<子の issue id（数値。番号ではなく id）>
```

## 踏み抜きやすい落とし穴

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
配信が終わると（NOT_LIVE を検知すると）回数は 0 に戻る。

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

### Twitch の `/helix/streams?id=` は一致しないと「人気配信トップ20」を返す（実際に発生した）

Twitch の API は、**指定した `id` に一致する配信が無いと、その絞り込みを黙って無視して
既定の一覧（人気配信トップ20）を返す**。`?id=1` でも `?id=abc` でも、パラメータ無しと
同じ応答になることを実機で確認した。エラーにならないのが厄介で、
応答の先頭をそのまま採用すると、配信が終わった直後の通知で
**まったく無関係な配信者のタイトル・サムネイル・URL を使って通知してしまう**。

`TwitchApiClient.findStreamById()` は応答の配信 ID を必ず照合し、
一致しなければ「配信終了済み」として空を返す。この照合を外さないこと。

なお同じ罠は `fetchLiveStreams()` の `user_id` には**無い**（一致しなければきちんと空が返る）。
`id` だけの挙動なので、片方だけ見て安心しないこと。

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
`twitch.tv/{ログイン名}` の形で、配信 ID からは作れない。ログイン名は検知時の API 応答には
含まれているが DB には保存していないため、**検知した者がその場で URL も組み立てる**しかない。

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

### サービス稼働中に `./gradlew build` すると動いているプロセスが壊れる（実際に発生した）

実行中の JVM は jar から**必要になった時点でクラスを読み込む**。稼働中に jar を差し替えると、
まだ読み込んでいなかったクラスが見つからなくなり、それまで正常だったリクエストが突然
`ClassNotFoundException`（例: `ch.qos.logback.classic.spi.ThrowableProxy`）で失敗し始める。
API が応答しなくなって原因を探すことになった。

ビルドしたら**必ず `bin/service.sh restart` まで行う**こと。ビルドだけして動作確認を続けない。
（画面ファイルを直したときに `restart` だけでは反映されないのと対になる注意点。）

### ログ設定は `logback-spring.xml` のみ

`application.yml` にも書くと二重管理になる。
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
有無で完了・失敗に補正する。`LiveStreamPollingScheduler` の巡回サイクル（定期実行・
「今すぐチェック」の両方が通る共通経路）から毎回呼ばれる。

補正の対象外とする判定は **2 段階**。`StreamRecorder.isRecording()`（このアプリが追跡中か）
だけでなく、`ProcessLauncher.isRunningWithCommandLineContaining()`（OS 上に yt-dlp が
まだ生きているか）も見る。**後者が欠かせない**——録画プロセスは JVM を止めても生き残るため、
前者だけだと再起動直後に「実際はまだ録画中」のものを失敗と誤判定し、
その後 yt-dlp が完成させても永久に失敗表示のままになる。

**`StreamRecorder` とは別クラスにしている理由**: 判定には
`StreamRecorder.isRecording(videoId)`（今まさに追跡中かどうか）が要るが、
`StreamRecorder` は既に `RecordingHistoryService` に依存しているため、逆方向の依存を
足すと循環参照になる。どちらにも依存しない `RecordingReconciler` に切り出すことで解消している。

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
`recordings/` 配下を実際に `Files.walk` で走査する。録画が失敗すると DB 上は完成ファイルの
記録が無いのに、映像・音声の断片ファイル（`{動画ID}.f137.mp4` 等）だけがディスクに残る
（実際に発生した）。DB 集計だとこれを取りこぼし、利用者が「アプリ上は大した使用量じゃないのに
ディスクが減っていく」という不可解な状態に陥る。ディレクトリ名がどの登録チャンネルとも
一致しない場合は削除済みチャンネルの孤児ファイルとして扱い、`(削除済みチャンネル)` と表示する
（チャンネル削除は通知履歴・録画履歴を連鎖削除するが、録画ファイル本体までは消さない設計のため）。

### 削除済みチャンネルの録画を消すときは録画中のプロセスを避ける

チャンネルを監視対象から削除しても、通知履歴・録画履歴は DB の連鎖削除で消えるが
**録画ファイル本体はディスクに残る**（誤って消さないための設計）。これをまとめて片付けるのが
`RecordingFileService.deleteOrphanedRecordings()`（`DELETE /api/recordings/orphaned`）。

対象は「`recordings/` 配下のディレクトリ名が、登録中のどのチャンネル ID とも一致しないもの」。
ただし **`ProcessLauncher.isRunningWithCommandLineContaining(チャンネルID)` で録画中の
チャンネルは除外する**。チャンネルを削除しても yt-dlp は JVM とは独立に動き続けるため、
書き込み中のファイルを消すとプロセス側がエラーになったり中途半端なファイルが残る
（yt-dlp のコマンドラインには出力先パスとしてチャンネル ID が含まれるので判定に使える）。

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

## 起動・停止

```bash
bin/service.sh start
```

`stop` はポートが解放されるまで待ってから完了する。直後に起動しても衝突しない。

CLI はサービス常駐中でも実行できる（H2 を `AUTO_SERVER=TRUE` で開いているため）。

## スキーマ変更時の注意

`ddl-auto: update` はカラムの追加はするが削除・リネームはしない。
カラム名を変えた場合、古い NOT NULL カラムが残って INSERT が失敗する。
開発中は `data/monitor.mv.db` を削除して作り直すのが早い。

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

## テスト

```bash
./gradlew test                 # 実行。build/reports/jacoco/test/html/index.html にカバレッジも生成される
./gradlew test --tests "com.example.monitor.service.*"   # パッケージ単位で実行
```

### 命名・構成のルール

- テストクラス: `<対象クラス名>Test`
- **対象メソッドごとに `@Nested` クラスを分ける**（メソッド名のPascalCase、例: `findLiveVideoId()` → `class FindLiveVideoId`）
- テストメソッド名: `testMethod01`, `testMethod02`, ...（`@Nested` クラス内で 01 から連番）
- `@DisplayName`: `"正常系：〇〇"` / `"異常系：〇〇"` の形式で内容を日本語で説明する

### テスト容易性のためだけに行った本番コードの変更

以下は動作を変えていない、テストのためだけの変更。理由を知らずに「元に戻す」と
またモックできなくなるので注意。

- `LiveStreamDetector` の `HttpClient` はフィールド初期化子で直接 `new` していたのを
  `HttpClientConfig` の Bean としてコンストラクタ注入に変更（モックに差し替えるため）
- `ChannelLogReader` のログ出力先ディレクトリは `private static final Path` の定数だったのを
  `@Value` によるコンストラクタ注入に変更（`@TempDir` を使ったテストで実ファイルシステムと
  分離するため）
- `StreamRecorder` の `yt-dlp` プロセス起動は `ProcessBuilder` を直接呼ばず、
  `ProcessLauncher` インターフェース経由にした（実プロセスを起動せずにモックで
  起動失敗・出力・終了コードをテストするため）

### モック化が難しい箇所への対応

- **discord-webhooks の `WebhookClient`**: `@PostConstruct` で生成されフィールドに保持されるため、
  `ReflectionTestUtils.setField` でモックを直接注入している（`DiscordNotifierTest` 参照）
- **google-api-services-youtube の `YouTube` 系フルーエントAPI**: `youtube.videos().list(...).setId(...).execute()`
  のような多段チェーンは各段を個別にモックし、`setXxx()` が自分自身を返すようスタブする
  （`YouTubeApiClientTest` 参照）。レスポンスの中身（`Video`, `VideoSnippet` 等）は
  `GenericJson` ベースの素の POJO なので、モックせず実インスタンスを組み立てて使う
- **CLI の Picocli 実行フロー全体**（`CliRunnerTest`）: Spring コンテキストを使わず、
  各コマンドをモックサービスで手組みした `CommandLine.IFactory` を渡すことで、
  実際の `new CommandLine(...)` によるサブコマンド解決・終了コード伝播まで検証している
- **`StreamRecorder.startRecording()` が起動する仮想スレッド**: 録画完了待ち（`awaitCompletion`）は
  別の仮想スレッドで非同期に実行されるため、そのスレッドが `process.getInputStream()` を
  消費するタイミングはテストの実行順序と無関係。厳密スタブ（Mockito の strict stubs）のまま
  `when(mockProcess.getInputStream())...` すると、テストスレッドの完了判定に間に合わず
  `UnnecessaryStubbingException` になることがある（実際に発生した）。この呼び出しの
  消費タイミングを検証しないテストでは `lenient().when(...)` を使う
  （`StreamRecorderTest` の `StartRecording` ネストクラス参照）
- **モックを組み立てるヘルパーを `when(...)` の引数の中で呼ばない**（実際に発生した）:
  `when(launcher.launch(any())).thenReturn(mockProcess("5432.1", 0))` のように、
  内部で `when(...)` を使うヘルパーを外側の `when(...)` の引数として直接書くと、
  スタブが入れ子になり `UnfinishedStubbingException` になる。
  先にローカル変数へ受けてから渡すこと（`VideoMetadataExtractorTest` 参照）。
