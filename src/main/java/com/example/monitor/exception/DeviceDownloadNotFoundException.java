package com.example.monitor.exception;

/**
 * 「端末に保存」の一時取得の仕事が見つからないときに投げられる。
 *
 * <p><b>他人の仕事を指定された場合もこれにする。</b>403 で返すと、その仕事 ID が
 * 実在することが相手に分かるため、存在しない場合と区別しない。
 * 期限（24 時間）を過ぎて消えた場合や、再起動でメモリから消えた場合もここに来る。
 * REST API では 404 Not Found に対応付けている。
 */
public class DeviceDownloadNotFoundException extends RuntimeException {

    /** 文言は固定。存在しない・他人のもの・期限切れを見分けられないようにするため。 */
    public DeviceDownloadNotFoundException() {
        super("指定された取得は見つかりません（24 時間を過ぎたものは消えています）");
    }
}
