---
name: deploy
description: このプロジェクトの変更を動いているサービスへ反映する。Java・HTML・CSS・JS のいずれかを編集した後、画面や API で動作を確かめる前に必ず使う。「再起動して」「反映して」「動かして」と言われたときにも使う。
---

# 変更をサービスへ反映する

```bash
./gradlew clean build -x test && bin/service.sh restart
```

これだけ。**片方だけ実行しない。**

`clean` を付けるのは、前の名前の jar を `build/libs` に残さないため。jar の名前は
`settings.gradle` の `rootProject.name` と `build.gradle` の `version` で決まり、
`./gradlew build` は前の名前の jar を消さない。2 個残っていると `bin/service.sh restart` は
動いているサービスを止めずに「build/libs に jar が 2 個あります」で終わり、下の「確認」の
`cmp` も引数が 3 つになって使えない。稼働中のプロセスは `run/youtubeLiveMonitor.jar` の
コピーで動いているので、`clean` で `build/libs` を消しても壊れない。

## 片方だけでは反映されない（どちらも実際に発生した）

**ビルドだけして再起動しない** → 稼働中のプロセスは起動時に `run/youtubeLiveMonitor.jar` へ
コピーした jar で動いているので、ビルドしても壊れないが、変更は反映されない。
（以前は `build/libs` の jar を直接動かしていたため、ビルドで未読込のクラスが消え、
リクエストや終了処理が `ClassNotFoundException` / `NoClassDefFoundError` で失敗していた。）

**再起動だけしてビルドしない** → `src/main/resources/static/` は jar に**同梱される**。
CSS/JS/HTML を直しても再起動だけでは古い内容のまま。ブラウザのキャッシュを疑って
無駄に調査することになる。

## 確認

`bin/service.sh restart` は「起動完了 (PID: ...)」まで出て終わる。
動いているのが最新のビルドかは、起動時のコピーとビルドの jar を比べれば分かる
（違うなら、ビルドの後に再起動していない）。

```bash
cmp build/libs/*.jar run/youtubeLiveMonitor.jar && echo "最新のビルドで動いている"
```

## テストも通したいとき

`-x test` を外す。ただし完了報告の前なら `verify` スキルを使うこと。

## 止まったとき

- `bin/service.sh restart` が「build/libs に jar が 2 個あります」で止まる → 名前の違う
  古い jar が残っている（サービスは止まっていない）。
  `./gradlew clean build -x test && bin/service.sh restart` でやり直す
