/**
 * YouTube Data API を手で叩いて試すための実験用タブ（お試し機能）。
 *
 * <h2>このパッケージは既存機能から完全に切り離してある</h2>
 * 監視・通知・録画といった本来の機能に一切影響を与えないよう、次の制約を守っている。
 * <ul>
 *   <li><b>既存クラスを変更しない。</b>{@code YouTube} Bean だけを読み取り専用で借りて使う
 *       （{@code YouTubeApiClient} には手を触れない。あちらへメソッドを足すと監視側の
 *       コードに変更が入ってしまうため）</li>
 *   <li><b>既存クラスから参照されない。</b>依存の向きはこのパッケージ→既存の一方通行</li>
 *   <li><b>DB を使わない。</b>状態はメモリ上のクォータ集計だけ</li>
 * </ul>
 *
 * <h2>不要になったときの消し方</h2>
 * <ol>
 *   <li>このパッケージ（{@code playground/}）をフォルダごと削除</li>
 *   <li>{@code static/playground.html}、{@code static/js/playground.js}、
 *       {@code static/css/playground.css} を削除</li>
 *   <li>各 HTML のナビにある {@code <!-- playground:start -->} 〜
 *       {@code <!-- playground:end -->} の行を削除</li>
 * </ol>
 * これだけで跡形なく消える。既存機能側の修正は発生しない。
 *
 * <h2>API を1つ足す・減らすには</h2>
 * {@link com.example.monitor.playground.PlaygroundApiHandler} を実装したクラスを
 * 1つ追加（{@code @Component} を付ける）するだけで画面に現れる。消すときはそのクラスを
 * 削除するだけでよい。登録一覧のような「両方直す必要がある場所」は作っていない。
 */
package com.example.monitor.playground;
