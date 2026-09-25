package com.example.monitor.exception;

/**
 * 保存先のボリュームの空き容量がしきい値（{@code monitor.recording.min-free-gb}）を下回っているため、
 * ダウンロードを始めないときに投げられる。
 *
 * <p>「サービスに保存」と「端末に保存」の両方で同じ応答にするため、専用の例外にしている。
 * 以前は {@link IllegalStateException} で 500 になっていたが、これはサーバーの異常ではなく
 * 「今は受け付けられない」状態なので、REST API では 503 Service Unavailable に対応付けている
 * （容量が空けば同じ要求が通る。409 にしないのは、要求そのものが何かと競合しているわけではないため）。
 */
public class InsufficientDiskSpaceException extends RuntimeException {

    /** 利用者に見せる文言は固定。空き容量の数値は外に出さない（ログには呼び出し側で残る）。 */
    public InsufficientDiskSpaceException() {
        super("空き容量が少ないため、ダウンロードを始められません");
    }
}
