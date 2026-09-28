package com.example.monitor.exception;

/**
 * 一般利用者（ADMIN 以外）が「サービスに保存」を既に 1 件進めているときに投げられる。
 *
 * <p>開始時点の空き容量の判定だけでは、長い動画を同時に何本も始めると、どれも判定を通ったうえで
 * 終わる頃にしきい値を割り、自動録画が始まらなくなる。そのため一般利用者は 1 人同時 1 件にしている
 * （端末に保存の {@link DeviceDownloadInProgressException} と同じ考え方）。
 *
 * <p>端末に保存とは枠が別なので、どちらが進行中かを文言で伝えられるよう別の例外にしている。
 * REST API では 409 Conflict に対応付けている。
 */
public class ServiceDownloadInProgressException extends RuntimeException {

    /** 文言は固定。端末に保存の 409 と見分けられるよう「サービスに保存中」と書く。 */
    public ServiceDownloadInProgressException() {
        super("別の動画をサービスに保存中です。終わってから次の動画を指定してください");
    }
}
