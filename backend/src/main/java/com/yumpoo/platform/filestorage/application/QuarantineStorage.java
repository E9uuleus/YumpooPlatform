package com.yumpoo.platform.filestorage.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.OptionalLong;
import java.util.UUID;
import java.time.Instant;
import java.util.List;

/** 隔离接收、原子发布与流式读取端口。 */
public interface QuarantineStorage {

    SealedUpload receive(UUID uploadId, InputStream source, OptionalLong contentLength)
            throws IOException;

    default SealedUpload receive(UUID uploadId, InputStream source, OptionalLong contentLength,
            long reservationLimit) throws IOException {
        return receive(uploadId, source, contentLength);
    }

    SealedUpload resume(UUID uploadId, long sizeBytes, String sha256) throws IOException;

    PublishedBlob publish(SealedUpload upload) throws IOException;

    InputStream open(PublishedBlob blob) throws IOException;

    boolean verify(PublishedBlob blob) throws IOException;

    default BlobVerification inspect(PublishedBlob blob) throws IOException {
        return verify(blob) ? BlobVerification.VERIFIED : BlobVerification.HASH_MISMATCH;
    }

    void discard(SealedUpload upload);

    void discard(PublishedBlob blob);

    default List<StorageEntry> listTemporary(String afterKey, int limit) throws IOException {
        return List.of();
    }

    default List<StorageEntry> listPublished(String afterKey, int limit) throws IOException {
        return List.of();
    }

    default boolean deleteTemporary(String key) throws IOException { return false; }

    default boolean deletePublished(String storageKey) throws IOException { return false; }

    record StorageEntry(String key, Instant modifiedAt, long sizeBytes,
            boolean regularFile, boolean unsafeEntry) {}
}
