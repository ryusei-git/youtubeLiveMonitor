---
name: deploy
description: このプロジェクトの変更を動いているサービスへ反映する。Java・HTML・CSS・JS のいずれかを編集した後、画面や API で動作を確かめる前に必ず使う。「再起動して」「反映して」「動かして」と言われたときにも使う。
---

# 変更をサービスへ反映する

```bash
./gradlew build -x test && bin/service.sh restart
```

これだけ。**片方だけ実行しない。**

## 片方だけだと壊れる（どちらも実際に発生した）

**ビルドだけして再起動しない** → 稼働中の JVM は必要になった時点でクラスを読むため、
jar を差し替えると未読込のクラスが消え、それまで正常だったリクエストが突然
`ClassNotFoundException` で失敗し始める。原因を探す羽目になる。

**再起動だけしてビルドしない** → `src/main/resources/static/` は jar に**同梱される**。
CSS/JS/HTML を直しても再起動だけでは古い内容のまま。ブラウザのキャッシュを疑って
無駄に調査することになる。

## 確認

`bin/service.sh restart` は「起動完了 (PID: ...)」まで出て終わる。
jar のタイムスタンプが再起動時刻より**前**なら順序を間違えている。

```bash
ls -l --time-style=+%H:%M:%S build/libs/*.jar
```

## テストも通したいとき

`-x test` を外す。ただし完了報告の前なら `verify` スキルを使うこと。
