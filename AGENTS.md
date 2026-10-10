# YouTube Live Monitor - 開発ガイド

## 概要

YouTube のライブ配信を監視し、配信開始時に Discord へ通知する個人用サービス。
Java 21 / Spring Boot 4.1.1 / Gradle / H2（ファイルモード）。

セットアップ手順・API 一覧・トラブルシューティングは [README.md](README.md) を参照。
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
- `./gradlew clean build` と `./gradlew javadoc` は**どちらも警告ゼロを維持する**（javac と javadoc の警告は `build.gradle` の `-Werror` でビルドの失敗になる）。
- **複数クラスで使う可能性のある処理は `util` パッケージに独立クラスとして切り出す**（状態を持たない
  static メソッド）。特定の機能に紐づく private メソッドのままにしておくと、
  同じ処理が別クラスにも必要になったときに複製されやすい。
  既存の例: `FileNameUtils`（拡張子除去）, `EpochTimeConverter`（日時変換）,
  `CaseInsensitiveMatcher`（大文字小文字を無視した一致検索）。
- JavaDoc・コメント・文書に文を足すときは、周りの行と同じ幅（約 100 桁。全角は 2 桁）で折り返す。
  `@param` の説明は、同じ JavaDoc のほかの `@param` と書き出しの桁をそろえる。
- メソッドの引数・戻り値・投げる例外を変えたら、その `@param`・`@return`・`@throws` と、その
  メソッドを `{@link}` で参照する JavaDoc も見直す。JavaDoc に呼び出し元の一覧や個数を書かない
  （ほかの PR が入ると黙って古くなる）。
- 例外を管理者への通知やアプリのログに載せるときは、`e.getMessage()` でなく `e` を載せる
  （`getMessage()` だけでは例外の型が消え、`null` のこともある）。ログでは `{}` を割り当てずに最後の
  引数として渡す。外部 API の例外は `docs/pitfalls.md`「外部 API の例外をそのままログに渡さない」
  に従う。

## タスクの進め方（Claude と Codex の共通ルール）

このリポジトリは **Claude と Codex が同時に触る**。同じファイルを同時に編集すると、
後から書いた方が相手の変更を黙って消す。実際に「ファイルが消えた」と誤診して
1時間を溶かし、通知履歴の検索機能を一度失った。以下は事故を繰り返さないための取り決め。

**状態の正本は GitHub だけ。** 残作業・担当・進捗は [Project](https://github.com/users/ryusei-git/projects/4)
と Issue・PR に置き、会話の中にしか無い決定事項を作らない。どのセッションも
いつ終わってよい（次のセッションが Issue と作業ツリーだけで続きを始められる）状態を保つ。

### 役割

| | Claude（リーダー） | Codex |
|---|---|---|
| Issue | 作成する（Codex が迷わない粒度・精度で書く） | 読むだけ |
| 実装 | 難易度の高いチケットだけ | それ以外すべて |
| PR | レビューし、**指摘は Claude 自身が直して**マージする | 作成まで。レビュー後の修正はしない |

どちらが担当するかは Issue 本文の「担当」欄で決まる。

### 1 チケット ＝ 1 セッション ＝ 1 PR（1 コミット）

- **チケットごとに新しいセッションで始める。** 前のチケットの会話を引きずると、
  不要なファイル内容がコンテキストに残り続けてトークンを浪費する（実際に発生した）。
- 依頼で渡すのは Issue 番号だけ。必要な情報は Issue 本文に書く
  （[省トークン引き継ぎ](docs/codex-token-efficient-handoff.md)）。
- **ついでの修正をしない。** 別の問題を見つけたら、直さずに Issue を作るか PR 本文に書く。
- **共有の作業ディレクトリでブランチを切り替えない。** Codex の作業中に切り替えると、
  相手の変更が別ブランチに混ざる（実際に発生した）。Claude が作業するときは
  `git worktree add` で別ディレクトリを使う。

### 着手する前

1. **`git status` と `git diff` を見る。** 相手の未コミット変更が残っていることがある。
   **見慣れない変更を「壊れている」と決めつけて戻さない**（相手の編集途中であることが多い）。
2. **相手のラベル（`claude` / `codex`）が付いた Issue は触らない。**
3. **宣言する。** 自分のラベルを付け、Project の Status を `In progress` にする。
4. Issue に「関連する落とし穴」が挙がっていれば `docs/pitfalls.md` の該当項目を読む。
5. **Issue が書かれた後に、変更するファイルへ入ったコミットを見る。** `git fetch origin` の後に
   `git log --oneline --since="<Issue の作成日時>" origin/<PR のベースブランチ> -- <変更するファイル>`
   （作成日時は `gh issue view <番号> --json createdAt --jq .createdAt`）。手順の前提（コード・
   呼び出し元・画面の文言）が変わっていたら、何が変わってどう合わせたかを PR 本文に書く。

### 終わったら

1. **完了条件を通す**（`.claude/skills/verify` と同じ内容）。
   - `./gradlew clean build javadoc` が警告ゼロで成功する（javac と javadoc の警告は `-Werror` で失敗になる）
   - `npx -y -p typescript tsc -p src/main/resources/static/jsconfig.json --noEmit` が型エラーゼロ
   - `node --test src/test/js/*.test.cjs` が全件成功（リポジトリ直下で実行する。テストが `common.js` をリポジトリ直下からの相対パスで読むため。`src/test/js/` のようにディレクトリを渡すと `MODULE_NOT_FOUND` で失敗する）
   - **ビルドしたら `bin/sandbox.sh start`** で、作業ツリーのビルドを確認用インスタンス（新しい DB・監視なし・Discord なし・ポート 18180）として起動する。本番（`bin/service.sh restart`）では確かめない（worktree や本番の無い作業端末では動かない。本番への反映はマージ後に本番の端末で行う）。ビルドし直したら `bin/sandbox.sh start` をやり直す
   - `ENV_FILE=.sandbox/.env SERVER_PORT=18180 bin/api.sh POST /api/monitor/check` が応答を返す（監視なしで起動するので 409・終了コード 22 は想定どおり。ログインの失敗・500 は失敗）ことと、`bin/sandbox.sh cli channel list` が動くこと（Issue に `java -jar build/libs/*.jar channel list` とあれば、これで代えてよい）
   - Issue の完了条件に書かれた確認（画面は `http://localhost:18180/adminLogin.html` に `.sandbox/.env` の管理者でログインして見る）
   - 確かめ終わったら `bin/sandbox.sh stop`（止めずに作業ツリーを消すと、java が残ってポート 18180 を掴み続ける）
   - Issue の「割り当てられたポート」は、依頼か環境変数 `SANDBOX_PORT` で指定されたポート。指定が
     無ければ 18180（ほかの作業ツリーの確認用インスタンスが使っていれば、空いている番号を選ぶ）。
     18180 以外なら `SANDBOX_PORT=<ポート> bin/sandbox.sh start` で起動し、`bin/api.sh` の
     `SERVER_PORT` と画面の URL もそのポートにする（上の 18180 もそのポートに読み替える）
   - Issue の完了条件で差分を比べる相手は PR のベースブランチ（`origin/main` とあっても読み替える）

   PR を作ると GitHub Actions（`.github/workflows/ci.yml`）が、上のうちビルド・JavaDoc・型検査・JS のテストを
   Linux でもう一度実行する。API と CLI の確認は CI では行わないので、手元で省略しない。

2. **Codex**: ブランチ `codex/issue-<番号>-<要約>` で 1 コミットにまとめてプッシュし、
   PR を作る（本文に `Closes #<番号>`、実行した確認とその結果）。Status を `In review` にし、
   Claude のターミナルへ `PR #<番号> 準備できました` と送る。**ラベルは付けたまま。**
3. **Claude**: `gh pr checks <PR番号> --watch` で CI の `java` と `js` が両方 pass になったのを確かめてから
   squash マージし、Status を `Done`、ラベルを外し、Issue をクローズする。CI が失敗していれば、直して pass になるまでマージしない。

### 中断するとき

**ラベルは付けたまま**、Issue に「どこまで終わったか」「次の 1 手」をコメントする。
ラベルを外すと、相手が壊れかけの状態を引き継いでしまう。

### ターミナルでの連絡

ハンドルは古くなるため、送る直前に取り直す（古いと `terminal_handle_stale` になる）。

```bash
orca-ide terminal list --json
orca-ide terminal send --terminal <handle> --text "PR #12 準備できました" --enter --json
```

**既知の制限（2026-09-24 時点、未解決）**: 上記コマンドが `agent_prompt_blocked` で失敗することがある。
`--retry-request <ID> --wait-submit <秒>` で再送してもエラーが再現し、CLI 側だけでは解消できない
（Orca 側のエージェント間ターミナル送信に対するゲートと見られる。Claude→Codex、Codex→Claude の
どちらの向きでも発生することを確認済み。人間が同じ端末へ直接入力した場合は通る）。
失敗したら、その場で利用者に「この文面を相手のターミナルに入力してほしい」と頼み、中継してもらう。
Orca の設定に解消手段が見つかれば、ここを更新すること。

### コマンド

Project を読み書きするもの（`bin/lead-status.sh` の「次の候補」、`bin/create-issue.sh`、下の `gh project` と
`projectItems` を引く `gh api graphql`）は、gh のトークンに `project` スコープが要る。`repo` だけでは
`missing required scopes [read:project]` で失敗する。`gh auth status` の `Token scopes` に `'project'` が無ければ
（`'read:project'` だけでは Project へ書き込めず、`bin/create-issue.sh` の登録が失敗する）、
その端末で `gh auth refresh -s project` を 1 回実行する（ブラウザでの承認が要る）。

```bash
bin/lead-status.sh          # 今の状態（担当中の Issue・未マージの PR・作業ツリー）をまとめて表示
bin/create-issue.sh <title> <body-file> [親issue番号] [P1|P2]   # Issue 作成〜Project 登録まで

gh issue list --repo ryusei-git/youtubeLiveMonitor --label codex

# Status の変更（Project: PVT_kwHOBB07r84BkZNB / Status: PVTSSF_lAHOBB07r84BkZNBzhjJ6nM）
gh project item-edit --id <item-id> --project-id PVT_kwHOBB07r84BkZNB \
  --field-id PVTSSF_lAHOBB07r84BkZNBzhjJ6nM --single-select-option-id <option>
#   Backlog f75ad846 / Ready 61e4505c / In progress 47fc9ee4 / In review df73e18b / Done 98236657
# item-id は Issue 側から引く（Project の一覧は書き込み直後に古い内容を返すことがある）
gh api graphql -f query='{repository(owner:"ryusei-git",name:"youtubeLiveMonitor"){issue(number:<番号>){projectItems(first:5){nodes{id project{number}}}}}}' \
  --jq '.data.repository.issue.projectItems.nodes[]|select(.project.number==4).id'
```

## 踏み抜きやすい落とし穴

実際に壊れた事例と再発防止の決まりは **[docs/pitfalls.md](docs/pitfalls.md)** にある。
毎回全文を読む必要はないが、**触る領域に該当するものは着手前に読むこと。**
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
- Twitch の `/helix/streams` は `id` に対応しておらず、`?id=` は常に無視されて人気配信の上位が返る（実際に発生した）
- Twitch はログイン名ではなくユーザーIDで監視する
- 視聴 URL は検知結果（`LiveStreamDetection.watchUrl`）が運ぶ
- チャンネル登録は必ず `StreamPlatform.normalizeChannelInput()` を通す

**録画（yt-dlp・ファイル・ディスクを扱うとき）**

- 録画の成否は終了コードではなく「完成ファイルの有無」で決める（実際に発生した）
- 途中で終わった録画は拡張子が `.mp4` でも再生できない（実際に発生した）
- 途中で終わった録画は、コンテナの長さと映像の長さが食い違う（実際に発生した）
- 録画中にアプリを再起動すると「録画中」のまま更新されなくなる（実際に発生した）
- 外部プロセスの出力を JVM へのパイプにすると、再起動で yt-dlp が止まる（実際に発生した）
- Docker ではコンテナを止めると録画も止まる（`KillMode=process` の代わりは無い）
- 録画中かの判定は、動画 ID を含むだけの `grep`・`tail` で誤検知する（実際に発生した）
- 録画ファイルの配信は自前のストリーミング処理を書かない
- ディスク使用量は DB ではなく実ファイルを走査して求める
- 削除済みチャンネルの録画を消すときは録画中のプロセスを避ける
- yt-dlp に yt-dlp-ejs が無いと、YouTube の形式が欠け、ログインした状態では取得できない（実際に発生した）

**画面（HTML / CSS / JS を触るとき）**

- 一覧のセルは折り返さない
- ログ画面の選択肢は決め打ちにしない
- フロントエンドの共通処理は `common.js` に集約する
- 画面（HTML/CSS/JS）を直しても `bin/service.sh restart` だけでは反映されない（実際に発生した）

**ビルド・起動・ログ**

- `build/libs` の jar を直接動かすと、稼働中の `./gradlew build` でプロセスが壊れる（実際に発生した）
- `cli` プロファイルで作られない Bean に依存するコントローラーには `@Profile("!cli")` を付ける（実際に発生した）
- ログ設定は `logback-spring.xml` のみ
- ログ書式を変えるならパーサーも直す
- 外部 API の例外をそのままログに渡さない（実際に発生した）
- ファイルの権限を POSIX の属性で指定すると、Windows では起動もテストもできない（実際に発生した）

**API・DB**

- エンティティを API に直接返さない
- DB管理画面の論理名は対応表に無いテーブル・カラムでも壊れないようにする
- `ddl-auto: update` はカラムの削除・リネームをしない
- enum の列挙子を増やすと既存 DB で全更新が失敗する（実際に発生した）
- 既存データがある状態で NOT NULL の boolean カラムを追加すると失敗する（実際に発生した）

新しく事故を踏んだら `docs/pitfalls.md` に追記し、この索引にも 1 行足す。

## 起動・停止

**本番は Docker で動く**（運用リポジトリ `ryusei-git/server-stacks` の `stacks/youtube-live-monitor`。#856）。main に入ると CI が
イメージを作り、運用側の `deploy.sh` が時間帯と「録画中は見送る」guard を見て反映する。本番を手で再起動・反映しない。
本番の CLI は `docker exec` で同じコンテナの中から動かす（README「Docker で動かす（本番）」）。
下の `bin/service.sh` は、開発の端末でホストに直接動かすとき（旧方式）のもの。

```bash
bin/service.sh start
```

`stop` はポートが解放されるまで待ってから完了する。直後に起動しても衝突しない。

CLI はサービス常駐中でも実行できる（H2 を `AUTO_SERVER=TRUE` で開いているため）。

録画中で本番をビルド・再起動できないときは、`bin/preview.sh start` で最新の main を別の場所・
別ポート（18080）・DB の複製・監視なしで起動して画面を確かめる（`bin/preview.sh stop` で止める）。

作業ツリー（worktree を含む）の変更を確かめるときは、`bin/sandbox.sh start` でその作業ツリーのビルドを
`.sandbox/` に新しい DB・監視なし・Discord なし・ポート 18180 で起動する（`bin/sandbox.sh stop` で止める）。
API は `ENV_FILE=.sandbox/.env SERVER_PORT=18180 bin/api.sh ...`、CLI は `bin/sandbox.sh cli ...` で使う。
DB・ログ・録画フォルダは start のたびに作り直す。`.sandbox/.env`（管理者のパスワードなど）は残るので、
確認のために値を書き足したら `bin/sandbox.sh stop` → `start` で起動し直す。本番の DB で main を見る
`bin/preview.sh` とは別物（詳しくは `bin/sandbox.sh` の冒頭）。

## テスト

```bash
./gradlew test --tests "com.example.monitor.service.*"   # 変更箇所に近いものから実行
```

- テストクラスは `<対象クラス名>Test`、対象メソッドごとに `@Nested`、メソッド名は `testMethod01` からの連番、
  `@DisplayName` は `"正常系：〇〇"` / `"異常系：〇〇"`
- **テストの新規作成は専用の Issue が担当する。** 仕様変更で既存テストが落ちた場合の追従だけは行い、
  何をなぜ変えたかを PR 本文に書く
- モックの組み方・テストのためだけに変えた本番コードは [docs/testing.md](docs/testing.md)
