package com.example.monitor.exception;

/**
 * 録画の検出を今すぐ始められないときに投げられる（検出が走っている・今の版で検出済み・検出できない録画）。
 *
 * <p>入力の誤りでもサーバーの異常でもなく、検出や録画の今の状態とぶつかっている
 * （待つ・{@code force=true} を付ける、で解ける）ので、{@link MonitoringInProgressException} と同じく
 * 409 Conflict に対応付けている。
 */
public class SoundDetectionConflictException extends RuntimeException {

    /**
     * @param message 始められない理由（利用者にそのまま返す）
     */
    public SoundDetectionConflictException(String message) {
        super(message);
    }
}
