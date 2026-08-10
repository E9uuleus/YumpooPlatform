package com.yumpoo.platform.filestorage.application;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** 已完整接收并封存、尚未发布的隔离对象。 */
public record SealedUpload(
        UUID uploadId,
        Path quarantinedPath,
        long sizeBytes,
        String sha256
) {
    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    public SealedUpload {
        Objects.requireNonNull(uploadId, "uploadId must not be null");
        Objects.requireNonNull(quarantinedPath, "quarantinedPath must not be null");
        if (sizeBytes <= 0 || sizeBytes > AttachmentUploadPolicy.MAX_BYTES) {
            throw new IllegalArgumentException("sizeBytes is outside the allowed range");
        }
        if (sha256 == null || !SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be lowercase hexadecimal");
        }
    }

    @Override
    public String toString() {
        return "SealedUpload[uploadId=" + uploadId + ", sizeBytes=" + sizeBytes + ", REDACTED]";
    }
}
