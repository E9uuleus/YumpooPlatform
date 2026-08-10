package com.yumpoo.platform.filestorage.consistency;

import com.yumpoo.platform.filestorage.application.AttachmentUploadPolicy;
import com.yumpoo.platform.filestorage.application.PublishedBlob;
import com.yumpoo.platform.filestorage.application.SealedUpload;
import com.yumpoo.platform.filestorage.infrastructure.LocalFileQuarantineStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仅由 verify:m0-14 在受限堆 JVM 中点名执行；类名故意不以 Test/IT 结尾。
 */
class M014BoundedHeapVerification {

    @TempDir
    private Path tempDirectory;

    @Test
    void exactLimitStreamsThroughFixedBuffersUnderAHeapSmallerThanTheFile() throws Exception {
        Path quarantine = Files.createDirectory(tempDirectory.resolve("quarantine"));
        Path blobs = Files.createDirectory(tempDirectory.resolve("blobs"));
        LocalFileQuarantineStorage storage = new LocalFileQuarantineStorage(
                quarantine,
                blobs
        );
        LazyPatternInputStream input = new LazyPatternInputStream(
                AttachmentUploadPolicy.MAX_BYTES
        );

        SealedUpload upload = storage.receive(
                UUID.fromString("14000000-0000-0000-0000-000000000014"),
                input,
                OptionalLong.of(AttachmentUploadPolicy.MAX_BYTES)
        );
        PublishedBlob published = storage.publish(upload);

        assertThat(upload.sizeBytes()).isEqualTo(104_857_600L);
        assertThat(input.maximumRequestedBytes())
                .isLessThanOrEqualTo(AttachmentUploadPolicy.BUFFER_BYTES);
        assertThat(storage.verify(published)).isTrue();
        assertThat(Runtime.getRuntime().maxMemory())
                .as("verify:m0-14 must launch this probe with a heap below 100 MiB")
                .isLessThan(AttachmentUploadPolicy.MAX_BYTES);
        System.out.printf(
                "M0-14 bounded-heap PASS: bytes=%d, maxRead=%d, maxHeap=%d%n",
                upload.sizeBytes(),
                input.maximumRequestedBytes(),
                Runtime.getRuntime().maxMemory()
        );
    }

    private static final class LazyPatternInputStream extends InputStream {

        private long remaining;
        private int maximumRequestedBytes;
        private int nextByte;

        private LazyPatternInputStream(long size) {
            remaining = size;
        }

        @Override
        public int read() {
            if (remaining == 0) {
                return -1;
            }
            remaining--;
            return nextByte++ & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            if (remaining == 0) {
                return -1;
            }
            maximumRequestedBytes = Math.max(maximumRequestedBytes, length);
            int count = (int) Math.min(remaining, length);
            Arrays.fill(bytes, offset, offset + count, (byte) (nextByte++ & 0xff));
            remaining -= count;
            return count;
        }

        int maximumRequestedBytes() {
            return maximumRequestedBytes;
        }
    }
}
