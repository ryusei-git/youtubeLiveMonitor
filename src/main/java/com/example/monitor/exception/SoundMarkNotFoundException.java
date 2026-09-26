package com.example.monitor.exception;

/**
 * 指定された音の印（{@link com.example.monitor.entity.SoundMark}）を消せないときに投げられる。
 *
 * <p><b>別の録画の印・他人の印を指定された場合もこれにする。</b>削除は「その録画の、本人の印」を
 * 1 回で探して、見つからなければ消せない、とまとめて扱うため。画面は自分の印にしか
 * 削除を出さないので、原因を分けて返しても使い道がない。
 * REST API では 404 Not Found に対応付けている。
 */
public class SoundMarkNotFoundException extends RuntimeException {

    /**
     * @param markId 見つからなかった印の主キー
     */
    public SoundMarkNotFoundException(Long markId) {
        super("印が見つかりません: id=" + markId);
    }
}
