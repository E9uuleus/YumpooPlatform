package com.yumpoo.platform.filestorage.application;

import java.util.Objects;
import java.util.regex.Pattern;

/** 正式目录中的不可变 blob 引用。storageKey 仍不是授权凭据。 */
public record PublishedBlob(
        String storageKey,
        long sizeBytes,
        String sha256
) {
    private static final Pattern STORAGE_KEY = Pattern.compile(
            "^sha256/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{64}$"
    );
    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    public PublishedBlob {
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        Objects.requireNonNull(sha256, "sha256 must not be null");
        if (!STORAGE_KEY.matcher(storageKey).matches()) {
            throw new IllegalArgumentException("storageKey format is invalid");
        }
        if (!SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be lowercase hexadecimal");
        }
        if (!storageKey.endsWith("/" + sha256)
                || !storageKey.startsWith("sha256/" + sha256.substring(0, 2)
                + "/" + sha256.substring(2, 4) + "/")) {
            throw new IllegalArgumentException("storageKey does not match sha256");
        }
        if (sizeBytes <= 0 || sizeBytes > AttachmentUploadPolicy.MAX_BYTES) {
            throw new IllegalArgumentException("sizeBytes is outside the allowed range");
        }
    }

    @Override
    public String toString() {
        return "PublishedBlob[sizeBytes=" + sizeBytes + ", REDACTED]";
    }
}
