---
name: verify
description: このプロジェクトの作業を終える前に、ビルド・型検査・実機動作をまとめて確認する。「完了」「終わり」と報告する前、コミットする前、タスクの完了条件を満たしたか確かめたいときに使う。認証が必要な API を叩きたいときにも使う。
---

# 完了前の確認

上から順に。1つでも落ちたら完了ではない。

```bash
# 1. ビルドとJavaDoc（どちらも警告ゼロを維持する）
./gradlew clean build javadoc

# 2. フロントエンドの型検査（JS/HTML を触っていなくても通す）
npx -y -p typescript tsc -p src/main/resources/static/jsconfig.json --noEmit

# 3. 反映（deploy スキル参照）
bin/service.sh restart

# 4. 巡回が動く（再起動直後は起動時の巡回とぶつかる。その時は少し待って再実行）
bin/api.sh POST /api/monitor/check

# 5. CLI が動く
java -jar build/libs/*.jar channel list
```

4 と 5 を両方見るのは、**Web と CLI で起動経路が違う**ため。
CLI モードは Web サーバーを起動しないので、`@Profile("!cli")` の付け忘れは
4 では発覚せず 5 で初めて落ちる（実際に発生した）。

## 認証付きで API を叩く

```bash
bin/api.sh <METHOD> <PATH> [JSON本文]
```

ログインはフォーム形式（JSON ではない）で、CSRF トークンを Cookie から取って
送り返す必要がある。手で組み立てると必ずどこかを間違えるので `bin/api.sh` を使う。
**`.env` のパスワードは出力にも報告にも出さない。**

一般利用者として確かめたいときは `bin/api.sh` を使わず、
そのアカウントで同じ手順を踏む（権限の分離は管理者では検証できない）。

## 判断

- テストが落ちた → 期待値を書き換えて通す前に、**仕様変更による追従なのか本当の不具合なのか**を見分ける。追従なら何をなぜ変えたか報告する
- `ClassNotFoundException` が出た → ビルドと再起動の順序を間違えている（deploy スキル参照）
