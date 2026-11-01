package com.example.monitor.exception;

/**
 * アップロードする動画が 1 本の上限（{@code monitor.upload.max-file-gb}）を超えているときに投げられる。
 *
 * <p>消しても待っても通らないので、{@link UploadRejectedException}（409）と分けて 413 Content Too Large に
 * 対応付けている。
 */
public class UploadTooLargeException extends RuntimeException {

    /**
     * @param maxFileGb 1 本の上限（GB）
     */
    public UploadTooLargeException(long maxFileGb) {
        super("1 本 " + maxFileGb + "GB までです");
    }
}
