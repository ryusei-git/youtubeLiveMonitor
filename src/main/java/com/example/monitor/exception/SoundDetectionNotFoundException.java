package com.example.monitor.exception;

/**
 * 指定された検出の候補（{@link com.example.monitor.entity.SoundCandidate}）や、
 * 今の版の実行記録（{@link com.example.monitor.entity.SoundDetectionRun}）が無いときに投げられる。
 *
 * <p><b>別の録画の候補・今の版でない候補を指定された場合もこれにする。</b>画面が持っている候補の番号は、
 * 付け直し（答えの無い候補は消して作り直す）や版の上げで無くなりうる。どの理由でも「その候補にはもう答えられず、
 * 一覧を読み直せばよい」ことは同じなので、原因を分けて返さない（{@link SoundMarkNotFoundException} と同じ考え方）。
 * REST API では 404 Not Found に対応付けている。
 */
public class SoundDetectionNotFoundException extends RuntimeException {

    /**
     * @param message 何が見つからなかったか（利用者にそのまま返す）
     */
    public SoundDetectionNotFoundException(String message) {
        super(message);
    }
}
