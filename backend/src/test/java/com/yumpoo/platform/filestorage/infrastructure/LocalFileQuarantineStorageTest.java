package com.yumpoo.platform.filestorage.infrastructure;

import com.yumpoo.platform.filestorage.application.AttachmentUploadPolicy;
import com.yumpoo.platform.filestorage.application.PublishedBlob;
import com.yumpoo.platform.filestorage.application.SealedUpload;
import com.yumpoo.platform.filestorage.application.UploadIncompleteException;
import com.yumpoo.platform.filestorage.application.UploadRejectedException;
import com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileQuarantineStorageTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void receivesExactLimitWithFixedReadRequestsAndPublishesAtomically() throws Exception {
        LocalFileQuarantineStorage storage = storage();
        LazyInputStream source = new LazyInputStream(AttachmentUploadPolicy.MAX_BYTES, -1);

        SealedUpload sealed = storage.receive(
                UUID.randomUUID(),
                source,
                OptionalLong.of(AttachmentUploadPolicy.MAX_BYTES)
        );
        PublishedBlob published = storage.publish(sealed);

        assertThat(sealed.sizeBytes()).isEqualTo(AttachmentUploadPolicy.MAX_BYTES);
        assertThat(source.maxRequestedBytes()).isEqualTo(AttachmentUploadPolicy.BUFFER_BYTES);
        assertThat(Files.exists(sealed.quarantinedPath())).isFalse();
        assertThat(storage.verify(published)).isTrue();
        try (InputStream input = storage.open(published)) {
            assertThat(input.read()).isEqualTo('A');
        }
    }

    @Test
    void rejectsDeclaredAndStreamedOverflowAndLeavesNoPartialFile() throws Exception {
        LocalFileQuarantineStorage storage = storage();

        assertTooLarge(() -> storage.receive(
                UUID.randomUUID(),
                new LazyInputStream(1, -1),
                OptionalLong.of(AttachmentUploadPolicy.MAX_BYTES + 1)
        ));
        assertTooLarge(() -> storage.receive(
                UUID.randomUUID(),
                new LazyInputStream(AttachmentUploadPolicy.MAX_BYTES + 1, -1),
                OptionalLong.empty()
        ));

        assertThat(filesUnder(tempDirectory.resolve("quarantine"))).isZero();
    }

    @Test
    void interruptedUploadDeletesPartAndCanRetryTheSameIntent() throws Exception {
        LocalFileQuarantineStorage storage = storage();
        UUID uploadId = UUID.randomUUID();

        assertThatThrownBy(() -> storage.receive(
                uploadId,
                new LazyInputStream(1024 * 1024, 128 * 1024),
                OptionalLong.of(1024 * 1024)
        )).isInstanceOf(UploadIncompleteException.class);
        assertThat(filesUnder(tempDirectory.resolve("quarantine"))).isZero();

        SealedUpload retried = storage.receive(
                uploadId,
                new LazyInputStream(1024, -1),
                OptionalLong.empty()
        );
        assertThat(retried.sizeBytes()).isEqualTo(1024);
        assertThat(Files.isRegularFile(retried.quarantinedPath())).isTrue();
    }

    @Test
    void rejectsEmptyBody() throws Exception {
        LocalFileQuarantineStorage storage = storage();

        assertThatThrownBy(() -> storage.receive(
                UUID.randomUUID(),
                InputStream.nullInputStream(),
                OptionalLong.of(0)
        )).isInstanceOf(UploadRejectedException.class)
                .extracting(exception -> ((UploadRejectedException) exception).rejectedCode())
                .isEqualTo(AttachmentRejectedCode.FILE_TYPE_NOT_ALLOWED);
    }

    private LocalFileQuarantineStorage storage() throws IOException {
        return new LocalFileQuarantineStorage(
                tempDirectory.resolve("quarantine"),
                tempDirectory.resolve("published")
        );
    }

    private static long filesUnder(Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0;
        }
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static void assertTooLarge(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(UploadRejectedException.class)
                .extracting(exception -> ((UploadRejectedException) exception).rejectedCode())
                .isEqualTo(AttachmentRejectedCode.FILE_TOO_LARGE);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static final class LazyInputStream extends InputStream {

        private final long totalBytes;
        private final long failAfterBytes;
        private long emitted;
        private int maxRequestedBytes;

        private LazyInputStream(long totalBytes, long failAfterBytes) {
            this.totalBytes = totalBytes;
            this.failAfterBytes = failAfterBytes;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read == -1 ? -1 : single[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            maxRequestedBytes = Math.max(maxRequestedBytes, length);
            if (failAfterBytes >= 0 && emitted >= failAfterBytes) {
                throw new IOException("synthetic interrupted stream");
            }
            if (emitted >= totalBytes) {
                return -1;
            }
            int allowed = (int) Math.min(length, totalBytes - emitted);
            if (failAfterBytes >= 0) {
                allowed = (int) Math.min(allowed, failAfterBytes - emitted);
                if (allowed <= 0) {
                    throw new IOException("synthetic interrupted stream");
                }
            }
            java.util.Arrays.fill(buffer, offset, offset + allowed, (byte) 'A');
            emitted += allowed;
            return allowed;
        }

        private int maxRequestedBytes() {
            return maxRequestedBytes;
        }
    }
}
