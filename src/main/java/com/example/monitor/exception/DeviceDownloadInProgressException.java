package com.example.monitor.exception;

/**
 * 同じ利用者が「端末に保存」の一時取得を既に 1 件進めているときに投げられる。
 *
 * <p>利用者 1 人につき同時に 1 件までにしているのは、一時ファイルがサービスの録画と同じ
 * ボリュームを使うため（#449 の決定）。REST API では 409 Conflict に対応付けている。
 */
public class DeviceDownloadInProgressException extends RuntimeException {

    /** 文言は固定（どの動画を取得中かは画面側が知っている）。 */
    public DeviceDownloadInProgressException() {
        super("別の動画を取得中です。終わってから次の動画を指定してください");
    }
}
