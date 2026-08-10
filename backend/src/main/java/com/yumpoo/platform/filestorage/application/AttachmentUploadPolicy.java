package com.yumpoo.platform.filestorage.application;

/** M0-14 可复用的流式上传硬限制。 */
public final class AttachmentUploadPolicy {

    public static final long MAX_BYTES = 100L * 1024L * 1024L;
    public static final int BUFFER_BYTES = 64 * 1024;

    private AttachmentUploadPolicy() {
    }
}
