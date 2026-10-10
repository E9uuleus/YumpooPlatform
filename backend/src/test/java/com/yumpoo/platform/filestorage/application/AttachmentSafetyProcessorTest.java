package com.yumpoo.platform.filestorage.application;

import com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode;
import com.yumpoo.platform.filestorage.infrastructure.LocalFileQuarantineStorage;
import com.yumpoo.platform.filestorage.infrastructure.TikaAttachmentContentDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentSafetyProcessorTest {

    @TempDir
    private Path tempDirectory;

    private LocalFileQuarantineStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        storage = new LocalFileQuarantineStorage(
                tempDirectory.resolve("quarantine"),
                tempDirectory.resolve("published")
        );
    }

    @Test
    void cleanMatchingContentPublishesOnlyAfterParentReauthorization() throws Exception {
        SealedUpload upload = textUpload();
        AtomicInteger authorizations = new AtomicInteger();
        AttachmentSafetyProcessor processor = processor();

        AttachmentProcessingOutcome outcome = processor.process(
                upload,
                AttachmentFileNamePolicy.normalize("notes.txt"),
                "text/plain",
                () -> authorizations.incrementAndGet() == 1
        );

        assertThat(outcome).isInstanceOf(AttachmentProcessingOutcome.Available.class);
        AttachmentProcessingOutcome.Available available =
                (AttachmentProcessingOutcome.Available) outcome;
        assertThat(authorizations).hasValue(1);
        assertThat(storage.verify(available.blob())).isTrue();
    }

    @Test
    void declaredMimeMismatchIsRejectedAndRemoved() throws Exception {
        AttachmentProcessingOutcome mismatch = processor().process(
                textUpload(),
                AttachmentFileNamePolicy.normalize("notes.txt"),
                "application/pdf",
                () -> true
        );
        assertRejected(mismatch, AttachmentRejectedCode.FILE_TYPE_NOT_ALLOWED, false, false);
        assertThat(regularFileCount(tempDirectory.resolve("quarantine"))).isZero();
        assertThat(regularFileCount(tempDirectory.resolve("published"))).isZero();
    }

    @Test
    void contentDetectionFailureRejectsWithoutPublishing() throws Exception {
        AttachmentProcessingOutcome outcome = new AttachmentSafetyProcessor(storage, (path, name) -> {
            throw new IOException("controlled detector failure");
        }).process(
                textUpload(),
                AttachmentFileNamePolicy.normalize("notes.txt"),
                "text/plain",
                () -> true
        );

        assertRejected(outcome, AttachmentRejectedCode.INTEGRITY_CHECK_FAILED, false, false);
        assertThat(regularFileCount(tempDirectory.resolve("quarantine"))).isZero();
        assertThat(regularFileCount(tempDirectory.resolve("published"))).isZero();
    }

    @Test
    void revokedParentLeavesOnlyAnInaccessiblePublishedOrphan() throws Exception {
        AttachmentProcessingOutcome outcome = processor().process(
                textUpload(),
                AttachmentFileNamePolicy.normalize("notes.txt"),
                "text/plain",
                () -> false
        );

        assertRejected(outcome, AttachmentRejectedCode.PARENT_NOT_WRITABLE, false, true);
        assertThat(regularFileCount(tempDirectory.resolve("quarantine"))).isZero();
        assertThat(regularFileCount(tempDirectory.resolve("published"))).isOne();
    }

    private AttachmentSafetyProcessor processor() {
        return new AttachmentSafetyProcessor(
                storage,
                new TikaAttachmentContentDetector()
        );
    }

    private SealedUpload textUpload() throws Exception {
        byte[] content = "safe attachment text".getBytes(StandardCharsets.UTF_8);
        return storage.receive(
                UUID.randomUUID(),
                new ByteArrayInputStream(content),
                OptionalLong.of(content.length)
        );
    }

    private static void assertRejected(
            AttachmentProcessingOutcome outcome,
            AttachmentRejectedCode code,
            boolean quarantineRetained,
            boolean orphanRetained
    ) {
        assertThat(outcome).isEqualTo(new AttachmentProcessingOutcome.Rejected(
                code,
                quarantineRetained,
                orphanRetained
        ));
    }

    private static long regularFileCount(Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0;
        }
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }
}
