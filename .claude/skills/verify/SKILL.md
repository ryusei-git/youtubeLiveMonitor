---
name: verify
description: このプロジェクトの作業を終える前に、ビルド・型検査・実機動作をまとめて確認する。「完了」「終わり」と報告する前、コミットする前、タスクの完了条件を満たしたか確かめたいときに使う。認証が必要な API を叩きたいときにも使う。
---

# 完了前の確認

上から順に。1つでも落ちたら完了ではない。作業ツリーの直下（worktree ならその直下）で行う。

```bash
# 1. ビルドとJavaDoc（どちらも警告ゼロを維持する）
./gradlew clean build javadoc

# 2. フロントエンドの型検査（JS/HTML を触っていなくても通す）
npx -y -p typescript tsc -p src/main/resources/static/jsconfig.json --noEmit

# 3. 作業ツリーのビルドを確認用インスタンスとして起動する（新しい DB・監視なし・Discord なし・ポート 18180）
bin/sandbox.sh start

# 4. API が動く（監視なしで起動するので 409・終了コード 22 は想定どおり。ログインの失敗・500 は失敗）
ENV_FILE=.sandbox/.env SERVER_PORT=18180 bin/api.sh POST /api/monitor/check

# 5. CLI が動く（確認用インスタンスの DB を使う）
bin/sandbox.sh cli channel list

# 6. Issue の完了条件に書かれた確認をしてから止める
bin/sandbox.sh stop
```

4 と 5 を両方見るのは、**Web と CLI で起動経路が違う**ため。
CLI モードは Web サーバーを起動しないので、`@Profile("!cli")` の付け忘れは
4 では発覚せず 5 で初めて落ちる（実際に発生した）。

**本番（`bin/service.sh restart`）では確かめない。** worktree では restart が 8080 の本番とぶつかるか、
DB の無い作業ツリーで起動してしまい、`bin/api.sh` は `.env` が無くて止まる（本番の無い作業端末でも同じ）。
本番への反映は、マージした後に本番の端末で deploy スキルの手順で行う。
ビルドし直したら `bin/sandbox.sh start` をやり直す（起動中でも start が止めてから起動し直す。画面ファイルも jar に
入っているので、ビルドせずに起動し直しても変わらない）。DB・ログ・録画フォルダは start のたびに作り直し、
`.sandbox/.env` は残る。

## 認証付きで API を叩く

```bash
ENV_FILE=.sandbox/.env SERVER_PORT=18180 bin/api.sh <METHOD> <PATH> [JSON本文]   # 確認用インスタンス
bin/api.sh <METHOD> <PATH> [JSON本文]                                            # 本番（本番の端末の共有ディレクトリだけ）
```

ログインはフォーム形式（JSON ではない）で、CSRF トークンを Cookie から取って
送り返す必要がある。手で組み立てると必ずどこかを間違えるので `bin/api.sh` を使う。
**`.env`（`.sandbox/.env` を含む）のパスワードは出力にも報告にも出さない。**

画面は `http://localhost:18180/adminLogin.html` を開き、`.sandbox/.env` の `ADMIN_USERNAME` / `ADMIN_PASSWORD` でログインして見る。

一般利用者として確かめたいときは `bin/api.sh` を使わず、
そのアカウントで同じ手順を踏む（権限の分離は管理者では検証できない）。

## 判断

- テストが落ちた → 期待値を書き換えて通す前に、**仕様変更による追従なのか本当の不具合なのか**を見分ける。追従なら何をなぜ変えたか報告する
- `ClassNotFoundException` が出た → ビルドと再起動の順序を間違えている（deploy スキル参照）
- `bin/sandbox.sh start` が「ポート 18180 を別のプロセスが使っています」で止まる → 別の作業ツリーの確認用インスタンスが残っている。そのディレクトリで `bin/sandbox.sh stop` するか、`SANDBOX_PORT=18181 bin/sandbox.sh start` にして `bin/api.sh` にも `SERVER_PORT=18181` を渡す
- `bin/sandbox.sh start` が「build/libs に jar が 2 個あります」で止まる → 名前の違う古い jar が残っている。`./gradlew clean build` でビルドし直す
