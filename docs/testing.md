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

- **discord-webhooks の `WebhookClient`**: `@PostConstruct` で生成されフィールドに保持されるため、
  `ReflectionTestUtils.setField` でモックを直接注入している（`DiscordNotifierTest` 参照）
- **google-api-services-youtube の `YouTube` 系フルーエントAPI**: `youtube.videos().list(...).setId(...).execute()`
  のような多段チェーンは各段を個別にモックし、`setXxx()` が自分自身を返すようスタブする
  （`YouTubeApiClientTest` 参照）。レスポンスの中身（`Video`, `VideoSnippet` 等）は
  `GenericJson` ベースの素の POJO なので、モックせず実インスタンスを組み立てて使う
- **CLI の Picocli 実行フロー全体**（`CliRunnerTest`）: Spring コンテキストを使わず、
  各コマンドをモックサービスで手組みした `CommandLine.IFactory` を渡すことで、
  実際の `new CommandLine(...)` によるサブコマンド解決・終了コード伝播まで検証している
- **起動したプロセスの出力を別の仮想スレッドで読む処理**: 完了待ちは別の仮想スレッドで非同期に
  実行されるため、そのスレッドが `process.getInputStream()` を消費するタイミングはテストの
  実行順序と無関係。厳密スタブ（Mockito の strict stubs）のまま
  `when(mockProcess.getInputStream())...` すると、テストスレッドの完了判定に間に合わず
  `UnnecessaryStubbingException` になることがある（実際に発生した）。この呼び出しの
  消費タイミングを検証しないテストでは `lenient().when(...)` を使う
  （`VideoDownloadServiceTest` 参照。`StreamRecorder` は #152 で出力をファイルへ向けたため、もう読まない）
- **モックを組み立てるヘルパーを `when(...)` の引数の中で呼ばない**（実際に発生した）:
  `when(launcher.launch(any())).thenReturn(mockProcess("5432.1", 0))` のように、
  内部で `when(...)` を使うヘルパーを外側の `when(...)` の引数として直接書くと、
  スタブが入れ子になり `UnfinishedStubbingException` になる。
  先にローカル変数へ受けてから渡すこと（`VideoMetadataExtractorTest` 参照）。
