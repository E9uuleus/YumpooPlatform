package com.yumpoo.platform.filestorage.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.OptionalLong;
import java.util.UUID;

/** 隔离接收、原子发布与流式读取端口。 */
public interface QuarantineStorage {

    SealedUpload receive(UUID uploadId, InputStream source, OptionalLong contentLength)
            throws IOException;

    PublishedBlob publish(SealedUpload upload) throws IOException;

    InputStream open(PublishedBlob blob) throws IOException;

    boolean verify(PublishedBlob blob) throws IOException;

    void discard(SealedUpload upload);

    void discard(PublishedBlob blob);
}
