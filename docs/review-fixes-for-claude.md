# Claude 向け：コードレビューの修正メモ

レビュー日：2026-09-21

## 現在の状態

- **以下は未修正の指摘。レビューでは本番コード・既存テストを変更していない。**
- 利用者からの依頼は、プロジェクトのレビューと、Claude が修正内容を把握するためのメモ作成。
- Git 管理情報が見つからなかったため、差分ではなく現在のソースを確認した。
- `./gradlew test javadoc --offline --rerun-tasks` は成功。テスト572件、失敗・エラー・スキップ0件。
- テスト実行時に JVM のクラス共有警告（`Sharing is only supported for boot loader classes...`）が出た。JavaDoc の警告は出ていない。
- 実行可能jarを更新するビルド、サービス再起動、外部サービスへの通知は行っていない。

修正時はルートの `AGENTS.md` を読むこと。以下の行番号はレビュー時点の目安で、メソッド名を基準に探すこと。

## 1. [P1] 外部コマンドのタイムアウトが出力読み取りに効かない

対象：`src/main/java/com/example/monitor/service/ExternalCommandRunner.java` の `run()`、78～86行付近。

### 原因と影響

`readLine()` で標準出力を EOF まで読んだ後で、初めて `waitFor(timeoutSeconds, ...)` を呼んでいる。
標準出力を開いたまま停止したプロセスでは読み取りが無期限に待機し、タイムアウト処理に到達しない。
`RecordingReconciler` からの ffprobe / ffmpeg 実行で起きると、その後の監視・通知まで止まる。
`VideoSourceProbe` のメタデータ取得にも同じ問題がある。

### 再現確認済み

実際の `ExternalCommandRunner(new DefaultProcessLauncher())` に対して以下を実行した。

```java
runner.run(List.of("sleep", "3"), "review-only", 1);
```

結果：タイムアウト1秒なのに **3.01秒待ち、`Optional` は値あり（成功扱い）**。
検証用Javaファイルは一時ディレクトリに作成し、検証後に削除済み。

### 修正方針とテスト

- 出力読み取りと期限付き終了待ちを並行させ、実行全体に期限を適用する。
- タイムアウト・割り込み・読み取り失敗時のプロセスと読み取りタスクの後始末を設計する。
- 大量出力でもパイプ詰まりを起こさないこと。
- 無出力で待ち続けるプロセスが期限内に打ち切られるテストと、通常終了・大量出力のテストを追加する。

### 対応済み（タスク 4-1）

`run()` を、標準出力の読み取りを別スレッド（仮想スレッド）に切り出して完了待ちと並行させ、
1 つの期限（`System.nanoTime()` 基準）を「終了待ち」と「読み取りの合流待ち」の合計に
適用する形に修正した。タイムアウト・割り込み・読み取り失敗のいずれの経路でも
`terminateAndAwait()` で `destroyForcibly()` → 実際の終了確認（`process.waitFor()`）→
読み取りスレッドの合流（それでも生きていれば `interrupt()`）まで行うようにしている。
戻り値の型・失敗時の扱い（すべて `Optional.empty()`）は変更していないため、
`VideoMetadataExtractor` / `RecordingSalvager` / `VideoSourceProbe` 側の変更は不要だった。

`ExternalCommandRunnerTest` を新規追加し、レビューが指摘した3点を実プロセス
（`DefaultProcessLauncher`）で検証している。

- 無出力で応答しないプロセス（`sleep 3`、タイムアウト1秒）が3秒待たずに打ち切られること。
  **このテストを修正前のコードに戻して実行し、実際に失敗する（3秒待って値ありの `Optional` が
  返る）ことを確認済み。**
- 通常終了するプロセス（`echo hello`）の標準出力を正しく取得できること。
- 大量出力（`yes | head -c 5000000` で500万バイト）でもパイプが詰まらず完走すること。

`./gradlew clean build javadoc` は警告ゼロで成功（587件のテストすべて成功）。
`npx tsc --noEmit`（jsconfig.json）も型エラーゼロ（このタスクではJS/HTMLを変更していない）。
ビルド後に `bin/service.sh restart` を実施し、`POST /api/monitor/check` で19チャンネルの巡回が
従来どおり完了すること、`channel list` でCLIが動くことを実機で確認した。

**検証していない範囲**: 実機での巡回サイクル中に、実際に `ffprobe`/`ffmpeg` が
タイムアウトするケース（＝この修正が直接効く経路）は再現できていない
（対象の録画が存在しなかったため、そのサイクルでは `ExternalCommandRunner` 経由の
呼び出し自体が発生しなかった）。`ExternalCommandRunnerTest` の実プロセステストで
このクラス単体の並行処理・タイムアウト・パイプ詰まり耐性は確認済みだが、
`RecordingReconciler`/`VideoMetadataExtractor`/`RecordingSalvager` 側の統合経路を
実際にタイムアウトさせて確認したわけではない。

## 2. [P1] 手動ダウンロードと自動録画の排他が別々

対象：`StreamRecorder.startRecording()`（114～119行付近）、`VideoDownloadService.startDownload()`。
いずれも `src/main/java/com/example/monitor/service/` 配下。

### 発生条件と影響

1. 監視登録済みYouTubeチャンネルの配信中URLを手動ダウンロードする。
2. 次の巡回で、その同じ配信の自動録画が始まる。

手動ダウンロード側はライブURLを拒否していない。
`activeDownloads` と `activeRecordings` は独立した集合であり、自動録画側は手動取得や既存履歴を確認しない。
同じチャンネルID・動画IDから同じ保存先が作られ、2つの yt-dlp が同じファイルへ書き込める。
逆順では手動側の `existsByVideoId()` が防ぐ場合があるが、両経路の同時開始までは防げない。

これはソース上の経路確認による指摘。実際の配信を使った破壊的な再現は行っていない。

### 修正方針とテスト

- 両経路で共通の原子的な予約・排他を使う。相手の状態を別々に読むだけでは同時開始の競合が残る。
- 既存履歴との整合性、アプリ再起動後に残ったプロセスも考慮する。
- ライブURLを手動取得の対象外にする方針なら、明示的な判定と利用者への説明が必要。
- 手動開始→自動開始、逆順、同時開始で、同じ出力先に起動されるプロセスが最大1つであることを検証する。

### 対応済み（タスク 4-2）

`docs/review-fix-tasks.md` の「4-2 の設計」で決めた方針どおり、`StreamRecorder.activeRecordings` と
`VideoDownloadService.activeDownloads` という2つの集合を廃止し、新設した
`ActiveVideoJobs`（`service` パッケージの `@Component`。キーは動画ID）に1つに統合した。
両サービスは `ActiveVideoJobs.reserve(videoId)` の1回の操作で「確認」と「登録」を行う
（`ConcurrentHashMap.newKeySet().add()` が排他そのものになる、という従来の考え方を維持したまま
集合を1つにまとめた）。`RecordingReconciler` の1段目の判定（進行中かどうか）も
`ActiveVideoJobs.isActive(videoId)` への問い合わせ1つに統合し、`StreamRecorder`/`VideoDownloadService`
への直接依存を無くした（OS上のプロセス確認という2段階目はそのまま残している）。

ライブ配信URLの手動ダウンロードは明示的に拒否する方針にした。`VideoSource` に
`yt-dlp` の `live_status` を追加（`PRINT_TEMPLATE`/`parse()` を同時に変更、自由入力のタイトルは
末尾のまま）し、`VideoSource.isLiveOrUpcoming()` が `is_live`/`is_upcoming` を判定する。
`VideoDownloadService.startDownload()` はこの判定を、動画IDの予約・`yt-dlp` プロセスの起動より前に行い、
該当すれば新設の `LiveStreamDownloadRejectedException`（`GlobalExceptionHandler` で400、
「配信中の動画はダウンロードできません。自動録画をご利用ください」）を投げる。
`post_live`/`was_live`/`not_live`（および取得できず `null` の場合）は拒否しない
——これらは自動録画と重複する可能性はあるが、その排除は `ActiveVideoJobs` の役割とした。

実機で確認済み：実際に配信中（`is_live`）だった動画のURLを `POST /api/downloads` に渡したところ
400 で拒否され、`yt-dlp` プロセスは起動されなかった（拒否直前まで稼働していたのは、
その配信の自動録画としてスケジューラが別途起動していたプロセスで、手動ダウンロードの拒否とは
無関係に動き続けていることも確認した＝排他機構が正しく機能している）。
別途、配信終了直後（`was_live`）の動画URLは通常どおり202で受け付けられることも確認した
（受け付け後すぐにプロセスを停止し、作成された録画履歴とファイルは削除して片付けた）。

**追加していないこと（正直に明記）**: 当初の作業計画では `ActiveVideoJobs` 単体のテスト、
および「自動録画中は手動DLを開始できない」「手動DL中は自動録画を開始しない」
「`recordStart()` 失敗時にプロセスを停止・終了確認してから予約を解放する」
「解放後に同じ動画IDで再開できる」を検証する新規テストを `StreamRecorderTest`/
`VideoDownloadServiceTest` に追加する予定だった。**利用者からの指示により、
この一連のテスト追加は着手後に取り消し・破棄した**（`src/test` 配下のトークン消費を
抑えるため）。そのため上記の排他・拒否・プロセス停止の各挙動は
**本番コードの実装と今回の実機確認でのみ検証されており、自動テストによる回帰検知は無い**。
既存テスト（`StreamRecorderTest`/`VideoDownloadServiceTest`/`RecordingReconcilerTest`/
`VideoSourceTest`/`VideoSourceProbeTest`/`YouTubeStreamPlatformTest`/`TwitchStreamPlatformTest`）は
新しいコンストラクタ引数・`VideoSource` の項目数増加に追随させる機械的な修正のみを行い、
検証内容（アサーションの意図）は変えていない。

## 3. [P2] 通知失敗回数が次の配信へ持ち越される

対象：`src/main/java/com/example/monitor/scheduler/LiveStreamPollingScheduler.java` の
`checkChannelAndNotify()`、277～280行付近。
関連：`MonitoredChannelRepository.updateObservedLiveState()` / `resetNotificationFailureCount()`。

### 発生条件と影響

1. 配信Aの通知が3回失敗して上限に達する。
2. 巡回間隔内の枠変更、またはアプリ停止中の切り替えで、NOT_LIVE を挟まず配信Bを検知する。
3. 配信BにもAの失敗回数が適用され、通知が一度も試行されない。

現在のリセット条件は NOT_LIVE 検知時と通知成功時のみ。
`updateObservedLiveState()` は動画IDを更新するが、通知失敗回数はリセットしない。
ソースと既存テストを確認した指摘であり、追加の再現テストは未作成。

### 修正方針とテスト

- 観測状態を上書きする前に、直前の配信IDと検知結果を比較し、新しい配信では失敗回数をリセットする。
- DBの値だけでなく、そのサイクルで上限判定に使う値もリセット後の値にする。
- 全カラムを書き戻す `save(entity)` は使わない。
- Aで上限到達→Bを直接検知した場合は通知を試み、同じAのままなら再送しないことを検証する。
- DETECTION_FAILED で配信状態を変更しない既存仕様を維持する。

### 対応済み（タスク 4-3）

`checkChannelAndNotify()` で、`updateObservedLiveState()` が `currentLiveVideoId` を
上書きする**前に**前回の配信 ID を控え、検知した配信 ID と異なる場合は
`resetNotificationFailureCount()` で 0 に戻すようにした。

上限判定に使う値は**ローカル変数**に持たせ、リセット時はその変数も 0 にしている。
DB だけ戻しても、読み込み済みのエンティティは古い値のままで、そのサイクルの上限判定が
「上限到達」のままになるため。エンティティ自体は書き換えていない（巡回ループが扱う
エンティティを変更しないという方針、および `save(entity)` を使わない方針に合わせた）。

**前回の配信 ID が `null`（＝分からない）ときは戻さない。** 分からないものを
「別の配信だ」と断定すると、上限を設けた意味が消えるため
（「配信していない」と「判定できなかった」を区別するのと同じ考え方）。
失敗回数が 1 以上なら、その配信を検知した時点で `currentLiveVideoId` も
記録されているはずなので、実運用でこの条件が効く場面は無い。

DETECTION_FAILED で配信状態を変更しない既存仕様は維持している（判定失敗は
`updateObservedLiveState()` に到達する前に return するため、前回の配信 ID も保たれる）。

`./gradlew clean build javadoc` は警告ゼロで成功（596件すべて成功）。
ビルド後に `bin/service.sh restart` を実施し、`POST /api/monitor/check` で19チャンネルの
巡回が完了すること、`channel list` で CLI が動くことを確認した。

**検証していない範囲**: 利用者の指示により、**この修正に対するテストは追加していない**
（`src/test` 配下を変更しない方針のため）。したがって
「Aで上限到達 → NOT_LIVE を挟まず B を検知 → 通知を試みる」「同じ A のままなら再送しない」
という肝心の分岐は**自動テストで検証されていない**。実機でもこの状況（巡回間隔内での
枠の差し替え）は再現していないため、根拠はコードレビューのみ。

なお既存テスト「正常系：失敗回数が上限に達した配信へは再送信しない」は、
前回の配信 ID が `null` の状態で別の動画 ID を検知する形になっており、
上記の `null` を除外する条件によって従来どおり通っている。

## 4. [P2] 履歴保存失敗後に起動済みプロセスが追跡されず残る

対象：`StreamRecorder.startRecording()`（148～153行付近）、`VideoDownloadService.launch()`。

### 発生条件と影響

プロセス起動後の `recordingHistoryService.recordStart()` が、DB障害やチャンネルの同時削除に伴う
外部キー制約違反などで例外になる場合。

完了待ちスレッドはまだ作られておらず、`finally` は追跡用集合から動画IDを外すだけ。
起動済みプロセスは停止されない。次の巡回・手動再試行で同じ保存先に別プロセスを起動できる。
履歴が保存されていなければ `RecordingReconciler` も対象を見つけられない。
両サービスに同じ起動順序がある。ソース上の例外経路確認による指摘。

### 修正方針とテスト

- 起動後の初期化に失敗した場合、起動済みプロセスを停止して終了を確認する。
- 排他の解除は、プロセスが出力先に書き込まなくなってから行う。
- 履歴登録とプロセス起動の順序を変更する場合は、起動失敗時に履歴が RECORDING のまま残らないようにする。
- `recordStart()` が例外を投げるテストを両サービスに追加し、停止・終了確認・排他解除の順序を検証する。

### 対応済み（タスク 4-2）

`StreamRecorder.startRecording()` と `VideoDownloadService`（`launch()`）の両方で、
`processLauncher.launch()` の後・`recordingHistoryService.recordStart()` の前後に
`try-catch(RuntimeException)` を追加した。`recordStart()` が例外を投げた場合、
新設した `util.ProcessTermination.destroyForciblyAndAwait(Process)` で起動済みプロセスを
`destroyForcibly()` した後 `process.waitFor()` で実際の終了を確認してから、元の例外を
そのまま投げ直す。予約（`ActiveVideoJobs`）の解放はどちらも呼び出し元の `finally` 節で
「起動が完了扱いにならなかった場合」にのみ行われるため、プロセスの終了確認が終わった
（＝もう出力先に書き込んでいない）後に解放される順序になる。

`ProcessTermination` は `ExternalCommandRunner.terminateAndAwait()` にあった
「`destroyForcibly()` を呼ぶだけで終わりにしない」ロジックと同じものを `util` パッケージに
stateless な独立クラスとして切り出したもの。**`ExternalCommandRunner` 自体の挙動・
既存テスト（`ExternalCommandRunnerTest`）は変えていない**——`terminateAndAwait()` の
中身をこのユーティリティ呼び出しに置き換えただけで、割り込み状態の復元タイミング
（読み取りスレッドの合流を終えるまで復元を遅らせる）を含めて同じ順序を維持している。

**追加していないこと**: 「`recordStart()` が例外を投げた場合にプロセスが停止・終了確認され、
予約が解放されること」「解放後に同じ動画IDで再度開始できること」を検証する自動テストは、
項目2と同じ理由（利用者の指示によるテスト追加の取り消し）で追加していない。
本番コードの実装レビューと目視でのロジック確認のみが根拠であり、実機でこの異常系
（DB障害時の挙動）そのものを再現して確認したわけではない
（正常系の起動・拒否・排他は実機確認済み。項目2の「対応済み」記載を参照）。

## 修正後の確認

- テストの命名・`@Nested` 構成・日本語コメントなどは `AGENTS.md` に従う。
- 既存テストに加え、上記の異常系・競合条件を検証する。
- `./gradlew clean build` と `./gradlew javadoc` の結果と警告を確認する。
- **稼働中サービスのjarをビルドで差し替えた場合は、`AGENTS.md` の指定どおり `bin/service.sh restart` まで行う。**
- このメモの「未修正」表示と検証結果を実際の対応に合わせて更新し、未検証の項目を明示する。
