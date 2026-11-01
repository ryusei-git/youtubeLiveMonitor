package com.example.monitor.exception;

/**
 * 動画のアップロードを今は受け付けられないときに投げられる（空き容量・利用者の容量・同時に上げられる本数）。
 *
 * <p>どれも待てば、または消せば通るので、REST API では 409 Conflict に対応付けている。
 * 空き容量の不足でも、ほかの保存の口が使う {@link InsufficientDiskSpaceException}（503）にしないのは、
 * アップロードの断り方を 1 つにそろえ、画面が理由の文言だけで見分けられるようにするため。
 */
public class UploadRejectedException extends RuntimeException {

    /**
     * @param message 利用者へそのまま見せる文言
     */
    public UploadRejectedException(String message) {
        super(message);
    }
}
