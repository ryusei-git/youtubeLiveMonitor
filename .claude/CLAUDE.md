@../AGENTS.md

# Claude 向け：リーダーとしての進め方

共通ルールは上の AGENTS.md（Codex と同じ文書）。ここには Claude だけの決まりを書く。
**共通ルールを変えるときは AGENTS.md を直す**（ここに書き写さない。以前は同じ内容を
2 ファイルに持っていて、片方だけ直す事故の元になっていた）。

## セッションは使い捨て

このセッションはいつ終わってもよい。**始めたら最初に `bin/lead-status.sh` を実行し、
その出力と GitHub だけを頼りに続きを決める。** 前のセッションの会話は前提にしない。

- 決めたことは、その場で Issue 本文かコメントに書く。会話の中にだけ残った決定は、
  セッションを閉じた瞬間に失われる。
- 担当中の Issue（`claude` ラベル）があれば、そのコメントの「次の 1 手」から再開する。
- セッションを閉じる前に、`claude` ラベルの Issue には必ず中断メモを残す（AGENTS.md「中断するとき」）。

## 1 日の流れ

1. `bin/lead-status.sh` で状態を見る。
2. **Codex が空いていれば次の Issue を渡す**（下記「Codex への依頼」）。
3. Codex の作業中は、次の Issue を書く・Claude 担当の Issue を進める・届いた PR をレビューする。

## Issue を書く

**[docs/ticket-template.md](../docs/ticket-template.md) に従う。** Codex は軽量モデル（gpt-6-luna）なので、
変更するファイル・メソッド・手順・やらないこと・完了条件まで具体的に書く。
書く前に対象コードを読み、ファイルパスとメソッド名が実在することを確かめる。
担当（Claude / Codex）もテンプレートの基準で決めて本文に書く。

## Codex への依頼

[docs/codex-token-efficient-handoff.md](../docs/codex-token-efficient-handoff.md) の依頼文を使う。

1. Issue に `codex` ラベルを付け、Status を `In progress` にする。
2. Codex のターミナルへ `/new` を送り、続けて依頼文を送る（1 Issue ＝ 1 セッション）。

## PR のレビュー

1. PR のブランチを別ディレクトリに取り出す。**共有ディレクトリでは checkout しない。**
   ```bash
   git fetch origin && git worktree add ../ylm-review-<番号> origin/<ブランチ>
   ```
2. 差分を Issue の手順・やらないこと・関連する落とし穴と照らし合わせる。
3. **指摘があれば自分で直す**（Codex に差し戻さない）。そのディレクトリで修正し、
   完了条件を通してから PR のブランチへ push する。直した内容は PR にコメントで残す。
4. `gh pr merge <番号> --squash --delete-branch` でマージし、Status を `Done`、ラベルを外し、
   Issue をクローズする。`git worktree remove ../ylm-review-<番号>` で片付ける。
5. 同じ種類の指摘が続くなら、`docs/ticket-template.md` か AGENTS.md を直す。

## Claude 担当の Issue を実装する

**1 Issue ごとに `Agent` ツール（`general-purpose`、`isolation: "worktree"`）へ切り出す。**
リーダーのセッション内で直接実装すると、読んだファイルや差分が次の作業まで残り続けて
コンテキストが膨らむ（実際に発生した）。`isolation: "worktree"` は Codex の作業ディレクトリと
衝突させないため。

依頼には Issue 番号だけを渡し、次を守らせる。

- AGENTS.md・Issue 本文・Issue に挙がった `docs/pitfalls.md` の項目を読んで自己完結させる
- 完了条件を通し、1 コミットでブランチに push して PR を作る（本文に `Closes #<番号>`）
- 報告は「PR 番号・実行した確認・判断した点」だけ

戻ってきた PR は、上の「PR のレビュー」と同じ手順でマージする。
