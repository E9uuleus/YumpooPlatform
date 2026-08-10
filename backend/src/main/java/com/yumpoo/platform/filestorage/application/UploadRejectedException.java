package com.yumpoo.platform.filestorage.application;

import com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode;

import java.util.Objects;

/** 不携带文件名、路径或扫描器输出的受控上传拒绝。 */
public final class UploadRejectedException extends RuntimeException {

    private final AttachmentRejectedCode rejectedCode;

    public UploadRejectedException(AttachmentRejectedCode rejectedCode) {
        super("attachment upload rejected: " + Objects.requireNonNull(rejectedCode).name());
        this.rejectedCode = rejectedCode;
    }

    public AttachmentRejectedCode rejectedCode() {
        return rejectedCode;
    }
}
