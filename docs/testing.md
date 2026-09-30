# テストの書き方

```bash
./gradlew test                 # 実行（カバレッジのレポートは作らない）
./gradlew test jacocoTestReport   # カバレッジのレポートも作る（build/reports/jacoco/test/html/index.html）
./gradlew test --tests "com.example.monitor.service.*"   # パッケージ単位で実行
```

### 命名・構成のルール

- テストクラス: `<対象クラス名>Test`
- **対象メソッドごとに `@Nested` クラスを分ける**（メソッド名のPascalCase、例: `findLiveVideoId()` → `class FindLiveVideoId`）
- テストメソッド名: `testMethod01`, `testMethod02`, ...（`@Nested` クラス内で 01 から連番）
- `@DisplayName`: `"正常系：〇〇"` / `"異常系：〇〇"` の形式で内容を日本語で説明する
- `@Nested` クラスにも `@DisplayName` を付け、対象のメソッド名を `"findLiveVideoId()"` の形で書く（テストの結果で、どのメソッドのテストかが分かるように）
- 1 つの `@Nested` には、そのクラスの 1 つのメソッドのテストだけを入れる。別のクラス（コントローラーなど）のテストは、そのクラスのテストクラスに書く
- テストを足すときは、`@Nested` の末尾に、今ある一番大きい番号の次の番号で足す（途中に挟んで番号を乱さない）
- 例外：確かめる相手がメソッドでないテストは、場面・決まりごとに `@Nested` を分け、`@DisplayName` にその場面・決まりを書く。`SecurityConfigTest`（設定したフィルターチェーンを `"未認証アクセス"`・`"権限による制御"` などの場面ごとに確かめる）と、下の「`docs/pitfalls.md` の決まりを確かめるテスト」

### リポジトリのクエリは `@DataJpaTest` で確かめる

JPQL・メソッド名から作るクエリ（`findByUserOrderBySubscribedAtDesc` など）の書き間違い・並び順・結合の条件は、
リポジトリをモックにしたテストでは確かめられない。`@DataJpaTest` を付けると、インメモリの H2 にエンティティから
テーブルが作られ、実際の SQL が流れる。テストごとにロールバックするので、テストどうしでデータは混ざらない。

- 例：`UserSubscriptionRepositoryTest`・`AuditLogRepositoryTest`・`UserNotificationRepositoryTest`
- 行は `TestEntityManager` の `persistAndFlush()` か、リポジトリの `save()` で入れる
- UPDATE・DELETE のクエリの結果を確かめるときは、`TestEntityManager` の `flush()`・`clear()` の後に読み直す
  （手元に残ったエンティティの古い値を読まないように。`UserNotificationRepositoryTest` の `reload()`・`UserSubscriptionRepositoryTest`）
- サービスとリポジトリを合わせて確かめたいときは、`@Import` で必要な Bean だけを足す（`OnlineVideoServiceTest`）

### Spring を起動する結合テスト（`@SpringBootTest`）は DB を共有する

`SecurityConfigTest` と `AppUserManagementControllerTest` は、`@SpringBootTest` と `@AutoConfigureMockMvc` で、
実際のフィルターチェーン（認可・CSRF・セッション）を通して確かめる。

- 2 つのクラスは設定が同じなので、Spring が 1 つのコンテキストを使い回し、`src/test/resources/application.yml` の
  インメモリ H2（`jdbc:h2:mem:testdb`）も共有する。
- `@Transactional` を付けていないので、**テストで入れた行は消えずに残り、実行の順番しだいで他のテストからも見える。**
  利用者名などはテストごとに違う値にする（`AppUserManagementControllerTest` の `"issue25-list"` など）。
  何度も使う利用者は「無ければ作る」にする（`SecurityConfigTest` の `ensureNormalUserExists()`）。
  件数を確かめるときは、自分で入れた行に絞ってから数える。
- 初期管理者（`testadmin`）は、同じ `application.yml` の `monitor.admin.*` から `AdminUserInitializer` が作る。
- 配信の巡回・動画の収集は、同じ `application.yml` の `monitor.scheduling.enabled: false` で止めてある（#187）。
- 設定の違う `@SpringBootTest`（`@MockitoBean` を足す・`properties` や `@ActiveProfiles` を変える）は、コンテキストが別になり、
  Spring の起動が 1 回増える。**そのうえ同じ `testdb` を使うと、後から起動したコンテキストの `ddl-auto: create-drop` が
  表を作り直し、先に起動したコンテキストが入れた行（初期管理者を含む）が消える**（テストは 1 つの JVM で動き、
  `DB_CLOSE_DELAY=-1` の名前付きのインメモリ DB は JVM の中で共有されるため）。設定を変えるときは、
  `@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:<testdb 以外の名前>;DB_CLOSE_DELAY=-1")` で DB の名前を変える
  （例：`cli` プロファイルで起動する `YouTubeLiveMonitorApplicationTest` の `jdbc:h2:mem:clitest`）。

### JS のテスト

`src/test/js/*.test.cjs` は Node の標準のテスト（`node:test`）で、画面の JS の関数を確かめる。
`./gradlew build` には含まれないので、別に実行する。

```bash
node --test src/test/js/*.test.cjs    # リポジトリの直下で実行する
```

- リポジトリの直下で実行する。テストが `src/main/resources/static/js/common.js` などをリポジトリの直下からの
  相対パスで読むので、`src/test/js` に移って実行すると失敗する。
- `node --test src/test/js/` とフォルダーを渡すと、フォルダーを 1 つのファイルとして読もうとして
  `MODULE_NOT_FOUND` になる。ファイルを `*.test.cjs` で渡す。

### `docs/pitfalls.md` の決まりを確かめるテスト

特定のクラスの振る舞いではなく、「実際に発生した」事故の再発を防ぐ決まりが守られているかを、コードと設定を読んで確かめるテスト。
決まりから外れた変更を入れた時点で `./gradlew build` が落ちる。
クラス名は `<対象>ConventionTest`、`@Nested` は（メソッドごとではなく）確かめることごとに分ける。

| テスト | 確かめる決まり（`docs/pitfalls.md`） |
|---|---|
| `EntityColumnConventionTest` | 「enum の列挙子を増やすと既存 DB で全更新が失敗する」「既存データがある状態で NOT NULL の boolean カラムを追加すると失敗する」 |
| `LogbackPatternConventionTest` | 「ログ書式を変えるならパーサーも直す」、「外部 API の例外をそのままログに渡さない」のうち `logback-spring.xml` の伏せ字 |

- 落ちたら、テストを緩めずに、本番のコード・設定を決まりに合わせる。
- `EntityColumnConventionTest` の `BOOLEAN_COLUMNS_WITHOUT_DEFAULT` には、新しいフィールドを足さない（新しいテーブルのフィールドでも、
  `columnDefinition` に `default` を書く）。一覧にあるのは、本番の DB に最初からある `MonitoredChannel.currentlyLive` だけ。
- `LogbackPatternConventionTest` は `logback-spring.xml` を読み込んだ `LoggerContext` を作らない。
  作ると作業ディレクトリの `logs/` にファイルが書かれる（#187）。`<pattern>` の文字列だけを取り出して整形する。
- `logback-spring.xml` の `%nopex` を外しても、logback 1.5.38 ではスタックが 2 回出ないので、`%nopex` の有無はこのテストでは見分けられない。

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
- `LoginAttemptLimiter` に package-private の `LoginAttemptLimiter(Clock)` がある（本番は `Clock.systemUTC()` を使う
  public のコンストラクタ）。15 分の窓が過ぎた後の振る舞いを、実際に待たずに確かめるため（`LoginAttemptLimiterTest`・`LoginAttemptFilterTest`）
- `@Value` で受けるフィールドの初期値は、Spring を通さずに `new` で組み立てたテストで今までどおり動く値にしてある
  （`new` では `@Value` が効かず、初期値のまま動くため）
  - `StreamRecorder` などの `minFreeGb` は `0`（空き容量を確かめない）。テストが、実行した機械の空き容量に左右されないように。
    本番の既定は `monitor.recording.min-free-gb` の 20
  - `LiveStreamPollingScheduler` などの `schedulingEnabled` は `true`（巡回する）。Spring を起動するテストでは
    `src/test/resources/application.yml` の `monitor.scheduling.enabled: false` で止まる
- `StreamRecorder.awaitCompletion(...)` は package-private。録画プロセスが終わった後の処理（録り直し・録画履歴の記録）を、
  仮想スレッドを通さず、テストのスレッドで直接呼んで確かめるため（`StreamRecorderTest` の `AwaitCompletion`）
- `YouTubeSearchBudget` に package-private の `YouTubeSearchBudget(…, Clock)` がある（本番は `Clock.systemUTC()` を渡す、`@Autowired` を付けた
  public のコンストラクタ）。回数が戻る太平洋時間の 0 時の前後・夏時間の切り替えを、固定の時刻で確かめるため（`YouTubeSearchBudgetTest`）
- `DefaultProcessLauncher` の `isWorkerProcess(ProcessHandle.Info)`・`isYtDlp(ProcessHandle.Info)` は package-private（元は private）。
  呼び出し元が使う `ProcessHandle.allProcesses()` は差し替えられないので、判定の部品だけを直接呼んで確かめるため（`DefaultProcessLauncherTest`）
- `ResourceMonitorService` の `evaluateWarnings()`・`helperUsages()`・`serviceUsage()`・`recordingVideoId()`・`helperPurpose()`・`add()` と、
  record の `Baseline`・`CpuMeter` は package-private（元は private）。`helperUsages()`・`serviceUsage()` は `measure()` から、
  `evaluateWarnings()` は `warnings()` から切り出した（動きは変えていない）。OSHI で実際に測らずに、注意の判定と集計を
  決めた値で確かめるため（`ResourceMonitorServiceTest`）。どれも JavaDoc に「`private` に戻さない」と書いてある

### モック化が難しい箇所への対応

- **Discord への送信（`DiscordNotifier`）**: 全体向けも利用者向けも共有の `HttpClient` で POST するので、
  `HttpClient.send` をモックする。送った `HttpRequest` の本文は `bodyPublisher()` を読んで確かめる
  （`DiscordNotifierTest` の `requestBody()` 参照）
- **google-api-services-youtube の `YouTube` 系フルーエントAPI**: `youtube.videos().list(...).setId(...).execute()`
  のような多段チェーンは各段を個別にモックし、`setXxx()` が自分自身を返すようスタブする
  （`YouTubeApiClientTest` 参照）。レスポンスの中身（`Video`, `VideoSnippet` 等）は
  `GenericJson` ベースの素の POJO なので、モックせず実インスタンスを組み立てて使う
- **CLI の Picocli 実行フロー全体**（`CliRunnerTest`）: Spring コンテキストを使わず、
  各コマンドをモックサービスで手組みした `CommandLine.IFactory` を渡すことで、
  実際の `new CommandLine(...)` によるサブコマンド解決・終了コード伝播まで検証している
- **起動したプロセスの完了を別の仮想スレッドで待つ処理**: 完了待ちは別の仮想スレッドで非同期に
  実行されるため、そのスレッドが呼ぶスタブ（`recordingSalvager.ensurePlayable()` など）が消費される
  タイミングはテストの実行順序と無関係。厳密スタブ（Mockito の strict stubs）のままだと、
  テストスレッドの完了判定に間に合わず `UnnecessaryStubbingException` になることがある
  （実際に発生した。当時は出力を読む `process.getInputStream()` のスタブで、今は出力をファイルへ
  向けたため読まない）。消費タイミングを検証しないスタブは `lenient().when(...)` にする
  （`VideoDownloadServiceTest` の `stubAsyncCompletionPath()` 参照）
- **モックを組み立てるヘルパーを `when(...)` の引数の中で呼ばない**（実際に発生した）:
  `when(launcher.launch(any())).thenReturn(mockProcess("5432.1", 0))` のように、
  内部で `when(...)` を使うヘルパーを外側の `when(...)` の引数として直接書くと、
  スタブが入れ子になり `UnfinishedStubbingException` になる。
  先にローカル変数へ受けてから渡すこと（`VideoMetadataExtractorTest` 参照）。
- **完了待ちの仮想スレッドが呼ぶスタブは、録り直しに進まずに終わる値にする**（実際に発生した）:
  `StreamRecorder.startRecording()` は起動に成功すると、裏の仮想スレッドで `awaitCompletion()` を動かす。
  そこで呼ばれる `recordingSalvager.ensurePlayable()` をスタブしないと `null` が返り、NPE でスレッドが落ちる。
  `StreamRecorderTest` の起動系のテストは、長いあいだこの NPE で止まることに頼って、`launch` の回数の検証を安定させていた。
  「再生できない」とスタブすると、裏で録り直しの `launch` が走り、検証がタイミングしだいで落ちる。
  起動系のテストでは「最初から再生できた」を `lenient()` で返す（`StreamRecorderTest.StartRecording` の `@BeforeEach`）。
- **録画スレッドが巡回の合図を待つ処理は、待ち時間を決め打ちせずに進める**: `StreamRecorder` の録り直しは、
  巡回の間隔の 3 倍まで `confirmStillLive()` の合図を待つ。合図が来ない場合は、間隔 0 の `MonitorProperties` で作れば待たずに進む。
  合図が来る場合は、完了待ちを別スレッドで動かし、終わるまで `confirmStillLive()` を送り続ける
  （待つスレッドが無ければ何もしないので、送り続けてよい。`StreamRecorderTest` の `awaitCompletionWhileConfirming`）。
  `Thread.sleep` で待ち始めを見計らわない。
- **`ProcessHandle` のモックの `onExit()` はスタブしなくてよい**: Mockito 5 の既定の応答では、`onExit()` は完了済みの
  `CompletableFuture`（値は `null`）を、`descendants()` は空の `Stream` を返す。そのため `mock(ProcessHandle.class)` のままでも
  `ProcessTermination.terminateTreeAndAwait` の `onExit().get(...)` はすぐに返る（`StreamRecorderTest` の `processNotExiting()`）。
  終わらないプロセスを模すときだけ、`new CompletableFuture<>()` を返すようにスタブする（`ProcessTerminationTest`）。
- **OSHI の `OSProcess` のモック**（`ResourceMonitorServiceTest`）: プロセスのモックを作るヘルパー `process()` が、分岐によって
  呼ばれない getter までまとめてスタブする。厳密スタブの検査を受けると `UnnecessaryStubbingException` になるので、
  このクラスには `@ExtendWith(MockitoExtension.class)` を付けない。
- **`mockStatic`・`mockConstruction` は、それを作ったスレッドにしか効かない**（`SoundDetectionServiceTest`）: ほかのスレッドでは
  本物が動く（`PcmDecoder.open()` なら本物の `nice ffmpeg`）。仮想スレッドへ逃がす入口（`SoundDetectionService` の
  `startPending()`・`startDetection()`）は呼ばず、テストのスレッドから直接呼べる入口（`detect()`・`processPending()`）で確かめる。
  - `@DataJpaTest` のテストのトランザクションはコミットされないので、ほかのスレッドからはテストが入れた行が見えない
    （外部キーの待ちで止まりうる）。これもスレッドを立てない理由になる。
  - 処理の途中でアプリの終了が始まる場合は、`mockConstruction` の中で `stop()` を呼んでから例外を投げると、スレッドを立てずに作れる
    （`SoundDetectionServiceTest` の `detectStoppedMidway()`）。
  - `final` のクラス（`EarKissDetector`・`PcmDecoder`）も、Mockito 5 の既定（inline）で `mockConstruction`・`mockStatic` できる。
    テストのために `final` を外さない。
