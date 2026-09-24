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

### 5. Codex に作業を依頼する場合（PRベースの運用）

Claude が Codex へ Issue を割り当てるときは、直接 main へコミットさせず**PRを経由**する。

```
Codex: ブランチを切って作業 → PR作成（本文に "Closes #<Issue番号>"）
        → ターミナルで Claude に「PR #X 準備できました」と伝える
  ↓
Claude: PR をレビューし、指摘があれば PR にコメントする
        → ターミナルで Codex に「PR #X にレビューコメントを付けました」と伝える
  ↓
Codex: 指摘を理解して修正し、同じブランチに push
        → ターミナルで Claude に「PR #X 対応完了しました」と伝える
  ↓
Claude: 対応を確認し、問題なければ PR をマージする（squash。1 Issue = 1 コミットの方針に合わせる）
        → Issue の Status を Done にし、クローズする
        → ターミナルで Codex に次の Issue を割り当てる
```

**ターミナルでの連絡は `orca terminal send` を使う。** ハンドルは古くなるため、
送る直前に `orca terminal list` で取り直すこと（古いハンドルで送ると
`terminal_handle_stale` になる）。

```bash
orca-ide terminal list --json                 # ハンドルを取り直す
orca-ide terminal send --terminal <handle> --text "PR #12 にレビューコメントを付けました" --enter --json
```

**レビューは PR 単位で行う。** 指摘はチケット（Issue）にではなく、
差分の文脈があるPR自体にコメントする。`Issue` はマージ後の状態更新（クローズ・
Status 変更）のために使う。

**この運用が要るのは Codex へ依頼する場合だけ。** Claude 自身の作業は、
これまでどおり検証してから main へ直接コミットしてよい（毎回 PR を経由すると
1人作業では確認の二度手間になるだけで、事故の防止効果もない）。

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

実際に壊れた事例と再発防止の決まりは **[docs/pitfalls.md](docs/pitfalls.md)** にある
（31 件・約 28KB）。毎回全文を読む必要はないが、**触る領域に該当するものは着手前に読むこと。**
ここに載っているのは、読まずに踏んで時間を溶かした実例そのもの。

**検知・通知（配信の状態を扱うとき）**

- 「配信していない」と「判定できなかった」を必ず区別する
- 通知の再試行には上限がある
- 「待機所」（配信開始前の予約枠）を配信中と誤検知する（実際に発生した）
- 配信タイトルは `/live` ページの HTML から取得できる（API を叩かなくてよい）
- フィルターは通知と録画の両方に効き、タイトルとカテゴリの両方を見る
- クォータを消費する API を監視ループに入れない
- 巡回を起動する経路を増やすなら排他を通す
- 監視ループから `save(entity)` を呼ばない

**プラットフォーム（YouTube / Twitch を扱うとき）**

- ID が 2 種類ある
- YouTube の「ハンドル」（`@foo`）は本来のチャンネルIDと別物
- Twitch の `/helix/streams?id=` は一致しないと「人気配信トップ20」を返す（実際に発生した）
- Twitch はログイン名ではなくユーザーIDで監視する
- 視聴 URL は検知結果（`LiveStreamDetection.watchUrl`）が運ぶ
- チャンネル登録は必ず `StreamPlatform.normalizeChannelInput()` を通す

**録画（yt-dlp・ファイル・ディスクを扱うとき）**

- 録画の成否は終了コードではなく「完成ファイルの有無」で決める（実際に発生した）
- 途中で終わった録画は拡張子が `.mp4` でも再生できない（実際に発生した）
- 途中で終わった録画は、コンテナの長さと映像の長さが食い違う（実際に発生した）
- 録画中にアプリを再起動すると「録画中」のまま更新されなくなる（実際に発生した）
- 録画ファイルの配信は自前のストリーミング処理を書かない
- ディスク使用量は DB ではなく実ファイルを走査して求める
- 削除済みチャンネルの録画を消すときは録画中のプロセスを避ける

**画面（HTML / CSS / JS を触るとき）**

- 一覧のセルは折り返さない
- ログ画面の選択肢は決め打ちにしない
- フロントエンドの共通処理は `common.js` に集約する
- 画面（HTML/CSS/JS）を直しても `bin/service.sh restart` だけでは反映されない（実際に発生した）

**ビルド・起動・ログ**

- サービス稼働中に `./gradlew build` すると動いているプロセスが壊れる（実際に発生した）
- `cli` プロファイルで作られない Bean に依存するコントローラーには `@Profile("!cli")` を付ける（実際に発生した）
- ログ設定は `logback-spring.xml` のみ
- ログ書式を変えるならパーサーも直す

**API・DB**

- エンティティを API に直接返さない
- DB管理画面の論理名は対応表に無いテーブル・カラムでも壊れないようにする

新しく事故を踏んだら `docs/pitfalls.md` に追記し、この索引にも 1 行足す。

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
