package com.yumpoo.platform.filestorage.application;

import com.yumpoo.platform.filestorage.domain.AttachmentFileType;

import java.util.Objects;

/** 已净化、只供展示和类型策略使用的文件名。绝不作为 storage key。 */
public record AttachmentFileName(
        String displayName,
        String extension,
        AttachmentFileType expectedType
) {
    public AttachmentFileName {
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(extension, "extension must not be null");
        Objects.requireNonNull(expectedType, "expectedType must not be null");
    }
}
