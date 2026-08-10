package com.yumpoo.platform.filestorage.testing;

import com.yumpoo.platform.filestorage.api.AttachmentStatus;
import com.yumpoo.platform.filestorage.application.PublishedBlob;
import com.yumpoo.platform.filestorage.application.SealedUpload;
import com.yumpoo.platform.filestorage.domain.AttachmentProcessingStage;
import com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 仅供 M0-14 真实 PostgreSQL 隔离验收使用，不进入生产制品或 Flyway。
 */
public class M014AttachmentProbeRepository {

    private final JdbcClient jdbcClient;
    private final Clock clock;
    private final AtomicBoolean failNextFinalize = new AtomicBoolean();

    public M014AttachmentProbeRepository(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public UUID createIntent(
            UUID ownerId,
            UUID actorId,
            String fileName,
            String declaredMime
    ) {
        UUID attachmentId = UUID.randomUUID();
        OffsetDateTime now = now();
        jdbcClient.sql("""
                        INSERT INTO yumpoo.m014_attachment_probe (
                            id, owner_id, uploader_actor, original_file_name, declared_mime,
                            status, created_at, updated_at
                        ) VALUES (
                            :id, :ownerId, :actorId, :fileName, :declaredMime,
                            'UPLOADING', :now, :now
                        )
                        """)
                .param("id", attachmentId)
                .param("ownerId", ownerId)
                .param("actorId", actorId)
                .param("fileName", fileName)
                .param("declaredMime", declaredMime)
                .param("now", now)
                .update();
        return attachmentId;
    }

    @Transactional
    public boolean claimReceive(UUID attachmentId, UUID actorId) {
        return jdbcClient.sql("""
                        UPDATE yumpoo.m014_attachment_probe
                        SET processing_stage = 'RECEIVING',
                            last_failure_code = NULL,
                            row_version = row_version + 1,
                            updated_at = :now
                        WHERE id = :id
                          AND uploader_actor = :actorId
                          AND status = 'UPLOADING'
                          AND processing_stage IS NULL
                        """)
                .param("now", now())
                .param("id", attachmentId)
                .param("actorId", actorId)
                .update() == 1;
    }

    @Transactional
    public void resetIncomplete(UUID attachmentId) {
        int updated = jdbcClient.sql("""
                        UPDATE yumpoo.m014_attachment_probe
                        SET processing_stage = NULL,
                            size_bytes = NULL,
                            sha256 = NULL,
                            quarantine_path = NULL,
                            last_failure_code = 'UPLOAD_INCOMPLETE',
                            row_version = row_version + 1,
                            updated_at = :now
                        WHERE id = :id
                          AND status = 'UPLOADING'
                          AND processing_stage = 'RECEIVING'
                        """)
                .param("now", now())
                .param("id", attachmentId)
                .update();
        requireTransition(updated, "reset incomplete upload");
    }

    @Transactional
    public void rejectReceive(UUID attachmentId, AttachmentRejectedCode code) {
        int updated = jdbcClient.sql("""
                        UPDATE yumpoo.m014_attachment_probe
                        SET status = 'REJECTED',
                            processing_stage = NULL,
                            quarantine_path = NULL,
                            rejected_code = :code,
                            last_failure_code = :code,
                            row_version = row_version + 1,
                            updated_at = :now
                        WHERE id = :id
                          AND status = 'UPLOADING'
                          AND processing_stage = 'RECEIVING'
                        """)
                .param("code", code.name())
                .param("now", now())
                .param("id", attachmentId)
                .update();
        requireTransition(updated, "reject received upload");
    }

    @Transactional
    public void queueForScan(UUID attachmentId, SealedUpload upload) {
        int updated = jdbcClient.sql("""
                        UPDATE yumpoo.m014_attachment_probe
                        SET processing_stage = 'QUEUED_SCAN',
                            size_bytes = :sizeBytes,
                            sha256 = :sha256,
                            quarantine_path = :quarantinePath,
                            row_version = row_version + 1,
                            updated_at = :now
                        WHERE id = :id
                          AND status = 'UPLOADING'
                          AND processing_stage = 'RECEIVING'
                        """)
                .param("sizeBytes", upload.sizeBytes())
                .param("sha256", upload.sha256())
                .param("quarantinePath", upload.quarantinedPath().toString())
                .param("now", now())
                .param("id", attachmentId)
                .update();
        requireTransition(updated, "queue upload for scanning");
    }

    @Transactional
    public void markScanning(UUID attachmentId) {
        transitionStage(attachmentId, "QUEUED_SCAN", "SCANNING");
    }

    @Transactional
    public void markFinalizing(UUID attachmentId) {
        transitionStage(attachmentId, "SCANNING", "FINALIZING");
    }

    @Transactional
    public void finalizeAvailable(
            UUID attachmentId,
            PublishedBlob blob,
            String detectedMime
    ) {
        int updated = jdbcClient.sql("""
                        UPDATE yumpoo.m014_attachment_probe
                        SET status = 'AVAILABLE',
                            processing_stage = NULL,
                            size_bytes = :sizeBytes,
                            sha256 = :sha256,
                            detected_mime = :detectedMime,
                            quarantine_path = NULL,
                            storage_key = :storageKey,
                            rejected_code = NULL,
                            last_failure_code = NULL,
                            row_version = row_version + 1,
                            updated_at = :now
                        WHERE id = :id
                          AND status = 'UPLOADING'
                          AND processing_stage = 'FINALIZING'
                        """)
                .param("sizeBytes", blob.sizeBytes())
                .param("sha256", blob.sha256())
                .param("detectedMime", detectedMime)
                .param("storageKey", blob.storageKey())
                .param("now", now())
                .param("id", attachmentId)
                .update();
        requireTransition(updated, "finalize available attachment");
        if (failNextFinalize.compareAndSet(true, false)) {
            throw new ForcedFinalizeRollback();
        }
    }

    @Transactional
    public void finalizeRejected(
            UUID attachmentId,
            AttachmentRejectedCode code,
            boolean retainQuarantined
    ) {
        int updated = jdbcClient.sql("""
                        UPDATE yumpoo.m014_attachment_probe
                        SET status = 'REJECTED',
                            processing_stage = NULL,
                            quarantine_path = CASE
                                WHEN :retainQuarantined THEN quarantine_path
                                ELSE NULL
                            END,
                            storage_key = NULL,
                            detected_mime = NULL,
                            rejected_code = :code,
                            last_failure_code = :code,
                            row_version = row_version + 1,
                            updated_at = :now
                        WHERE id = :id
                          AND status = 'UPLOADING'
                          AND processing_stage IN ('QUEUED_SCAN', 'SCANNING', 'FINALIZING')
                        """)
                .param("retainQuarantined", retainQuarantined)
                .param("code", code.name())
                .param("now", now())
                .param("id", attachmentId)
                .update();
        requireTransition(updated, "finalize rejected attachment");
    }

    @Transactional
    public void incrementObservation(UUID attachmentId) {
        int updated = jdbcClient.sql("""
                        UPDATE yumpoo.m014_attachment_probe
                        SET probe_observation = probe_observation + 1,
                            updated_at = :now
                        WHERE id = :id
                        """)
                .param("now", now())
                .param("id", attachmentId)
                .update();
        requireTransition(updated, "record concurrent observation");
    }

    public Optional<ProbeRow> find(UUID attachmentId) {
        return jdbcClient.sql("""
                        SELECT id, owner_id, uploader_actor, original_file_name, declared_mime,
                               status, processing_stage, size_bytes, sha256, detected_mime,
                               quarantine_path, storage_key, rejected_code, last_failure_code,
                               row_version, probe_observation
                        FROM yumpoo.m014_attachment_probe
                        WHERE id = :id
                        """)
                .param("id", attachmentId)
                .query((resultSet, rowNumber) -> new ProbeRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("owner_id", UUID.class),
                        resultSet.getObject("uploader_actor", UUID.class),
                        resultSet.getString("original_file_name"),
                        resultSet.getString("declared_mime"),
                        AttachmentStatus.valueOf(resultSet.getString("status")),
                        enumOrNull(
                                AttachmentProcessingStage.class,
                                resultSet.getString("processing_stage")
                        ),
                        resultSet.getObject("size_bytes", Long.class),
                        resultSet.getString("sha256"),
                        resultSet.getString("detected_mime"),
                        pathOrNull(resultSet.getString("quarantine_path")),
                        resultSet.getString("storage_key"),
                        enumOrNull(
                                AttachmentRejectedCode.class,
                                resultSet.getString("rejected_code")
                        ),
                        enumOrNull(
                                AttachmentRejectedCode.class,
                                resultSet.getString("last_failure_code")
                        ),
                        resultSet.getLong("row_version"),
                        resultSet.getLong("probe_observation")
                ))
                .optional();
    }

    public void forceNextFinalizeRollback() {
        failNextFinalize.set(true);
    }

    public void clearFaults() {
        failNextFinalize.set(false);
    }

    private void transitionStage(UUID attachmentId, String expected, String target) {
        int updated = jdbcClient.sql("""
                        UPDATE yumpoo.m014_attachment_probe
                        SET processing_stage = :target,
                            row_version = row_version + 1,
                            updated_at = :now
                        WHERE id = :id
                          AND status = 'UPLOADING'
                          AND processing_stage = :expected
                        """)
                .param("target", target)
                .param("now", now())
                .param("id", attachmentId)
                .param("expected", expected)
                .update();
        requireTransition(updated, "move processing stage to " + target);
    }

    private static void requireTransition(int updated, String action) {
        if (updated != 1) {
            throw new IllegalStateException("M0-14 probe could not " + action);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static Path pathOrNull(String value) {
        return value == null ? null : Path.of(value);
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    public record ProbeRow(
            UUID id,
            UUID ownerId,
            UUID uploaderActor,
            String originalFileName,
            String declaredMime,
            AttachmentStatus status,
            AttachmentProcessingStage processingStage,
            Long sizeBytes,
            String sha256,
            String detectedMime,
            Path quarantinePath,
            String storageKey,
            AttachmentRejectedCode rejectedCode,
            AttachmentRejectedCode lastFailureCode,
            long rowVersion,
            long probeObservation
    ) {
        public SealedUpload sealedUpload() {
            if (quarantinePath == null || sizeBytes == null || sha256 == null) {
                throw new IllegalStateException("M0-14 probe row has no sealed upload");
            }
            return new SealedUpload(id, quarantinePath, sizeBytes, sha256);
        }

        public PublishedBlob publishedBlob() {
            if (storageKey == null || sizeBytes == null || sha256 == null) {
                throw new IllegalStateException("M0-14 probe row has no published blob");
            }
            return new PublishedBlob(storageKey, sizeBytes, sha256);
        }
    }

    public static final class ForcedFinalizeRollback extends RuntimeException {
        public ForcedFinalizeRollback() {
            super("forced M0-14 finalization rollback");
        }
    }
}
