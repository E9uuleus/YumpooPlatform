package com.yumpoo.platform.filestorage.application;

import com.yumpoo.platform.filestorage.domain.AttachmentFileType;

import java.util.Objects;

public record DetectedAttachmentContent(
        AttachmentFileType fileType,
        String detectedMime
) {
    public DetectedAttachmentContent {
        Objects.requireNonNull(fileType, "fileType must not be null");
        Objects.requireNonNull(detectedMime, "detectedMime must not be null");
    }
}
