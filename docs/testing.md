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
