package com.yumpoo.platform.filestorage.infrastructure;

import com.yumpoo.platform.filestorage.application.AttachmentData.CreateIntent;
import com.yumpoo.platform.filestorage.application.AttachmentData.Finalization;
import com.yumpoo.platform.filestorage.application.AttachmentData.RescanResult;
import com.yumpoo.platform.filestorage.application.AttachmentData.ScanClaim;
import com.yumpoo.platform.filestorage.domain.AttachmentOwnerType;
import com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode;
import com.yumpoo.platform.filestorage.domain.AttachmentState;
import com.yumpoo.platform.filestorage.application.AttachmentFileName;
import com.yumpoo.platform.filestorage.application.AttachmentRecord;
import com.yumpoo.platform.filestorage.application.AttachmentRepository;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAttachmentRepository implements AttachmentRepository {
    private final JdbcClient jdbc;

    public JdbcAttachmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public AttachmentRecord insertIntent(CreateIntent command, AttachmentFileName fileName,
            long reservedBytes, long companyLimit, long projectLimit, Instant expiresAt) {
        ensureQuotaRows(command.companyId(), command.projectId(), command.now());
        List<Quota> quotas = jdbc.sql("""
                SELECT scope_type, reserved_bytes, available_bytes
                  FROM yumpoo.attachment_quota_usage
                 WHERE company_id = :companyId
                   AND ((scope_type = 'COMPANY' AND scope_id = :companyId)
                     OR (scope_type = 'PROJECT' AND scope_id = :projectId))
                 ORDER BY scope_type
                 FOR UPDATE
                """).param("companyId", command.companyId()).param("projectId", command.projectId())
                .query((rs, row) -> new Quota(rs.getString(1), rs.getLong(2), rs.getLong(3))).list();
        for (Quota quota : quotas) {
            long limit = quota.scopeType().equals("COMPANY") ? companyLimit : projectLimit;
            if (quota.reserved() + quota.available() + reservedBytes > limit) {
                throw ApplicationException.validation(new FieldViolation("sizeBytes",
                        "QUOTA_EXCEEDED", "附件配额不足"));
            }
        }
        adjustQuota(command.companyId(), command.projectId(), reservedBytes, 0, command.now());
        jdbc.sql("""
                INSERT INTO yumpoo.attachment (
                    id, company_id, quota_project_id, owner_type, owner_id,
                    original_file_name, file_extension, declared_mime, status,
                    reserved_bytes, uploaded_by_user_id, intent_expires_at,
                    created_at, updated_at
                ) VALUES (
                    :id, :companyId, :projectId, :ownerType, :ownerId,
                    :fileName, :extension, :declaredMime, 'UPLOADING',
                    :reservedBytes, :uploadedBy, :expiresAt, :now, :now
                )
                """).param("id", command.id()).param("companyId", command.companyId())
                .param("projectId", command.projectId()).param("ownerType", command.ownerType().name())
                .param("ownerId", command.ownerId()).param("fileName", fileName.displayName())
                .param("extension", fileName.extension()).param("declaredMime", command.declaredMime())
                .param("reservedBytes", reservedBytes).param("uploadedBy", command.uploadedByUserId())
                .param("expiresAt", utc(expiresAt)).param("now", utc(command.now())).update();
        return required(command.companyId(), command.id());
    }

    @Override
    public Optional<AttachmentRecord> find(UUID companyId, UUID attachmentId) {
        return jdbc.sql("SELECT * FROM yumpoo.attachment WHERE company_id=:companyId AND id=:id")
                .param("companyId", companyId).param("id", attachmentId)
                .query(this::mapAttachment).optional();
    }

    @Override
    public List<AttachmentRecord> list(UUID companyId, AttachmentOwnerType ownerType, UUID ownerId,
            Instant beforeCreatedAt, UUID beforeId, int limit) {
        String cursor = beforeCreatedAt == null ? "" :
                " AND (created_at, id) < (:beforeCreatedAt, :beforeId) ";
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT * FROM yumpoo.attachment WHERE company_id=:companyId "
                + "AND owner_type=:ownerType AND owner_id=:ownerId AND status <> 'DELETED' " + cursor
                + "ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("companyId", companyId).param("ownerType", ownerType.name())
                .param("ownerId", ownerId).param("limit", limit);
        if (beforeCreatedAt != null) {
            spec = spec.param("beforeCreatedAt", utc(beforeCreatedAt)).param("beforeId", beforeId);
        }
        return spec.query(this::mapAttachment).list();
    }

    @Override
    @Transactional
    public Optional<AttachmentRecord> beginUpload(UUID companyId, UUID attachmentId, UUID leaseToken,
            Instant now, Instant leaseUntil) {
        int updated = jdbc.sql("""
                UPDATE yumpoo.attachment
                   SET processing_stage='RECEIVING', upload_lease_token=:leaseToken,
                       upload_lease_until=:leaseUntil, row_version=row_version+1, updated_at=:now
                 WHERE company_id=:companyId AND id=:id AND status='UPLOADING'
                   AND sealed_at IS NULL AND intent_expires_at > :now
                   AND (upload_lease_token IS NULL OR upload_lease_until < :now)
                """).param("leaseToken", leaseToken).param("leaseUntil", utc(leaseUntil))
                .param("now", utc(now)).param("companyId", companyId).param("id", attachmentId).update();
        return updated == 1 ? find(companyId, attachmentId) : Optional.empty();
    }

    @Override
    @Transactional
    public AttachmentRecord seal(UUID companyId, UUID attachmentId, UUID leaseToken,
            long sizeBytes, String sha256, Instant now) {
        AttachmentRecord current = locked(companyId, attachmentId);
        requireLease(current, leaseToken);
        long released = current.reservedBytes() - sizeBytes;
        if (released < 0) throw invalid("QUOTA_EXCEEDED");
        adjustQuota(companyId, current.projectId(), -released, 0, now);
        int generation = current.scanGeneration() + 1;
        jdbc.sql("""
                UPDATE yumpoo.attachment SET size_bytes=:sizeBytes, sha256=:sha256,
                    reserved_bytes=:sizeBytes, sealed_at=:now, processing_stage='QUEUED_SCAN',
                    upload_lease_token=NULL, upload_lease_until=NULL,
                    scan_generation=:generation, row_version=row_version+1, updated_at=:now
                 WHERE company_id=:companyId AND id=:id AND upload_lease_token=:leaseToken
                """).param("sizeBytes", sizeBytes).param("sha256", sha256)
                .param("generation", generation).param("now", utc(now))
                .param("companyId", companyId).param("id", attachmentId)
                .param("leaseToken", leaseToken).update();
        jdbc.sql("""
                INSERT INTO yumpoo.attachment_scan_task (
                    id, attachment_id, company_id, generation, status,
                    next_attempt_at, created_at, updated_at
                ) VALUES (:taskId,:attachmentId,:companyId,:generation,'READY',:now,:now,:now)
                """).param("taskId", UUID.randomUUID()).param("attachmentId", attachmentId)
                .param("companyId", companyId).param("generation", generation)
                .param("now", utc(now)).update();
        return required(companyId, attachmentId);
    }

    @Override
    @Transactional
    public void cancelUpload(UUID companyId, UUID attachmentId, UUID leaseToken, Instant now) {
        jdbc.sql("""
                UPDATE yumpoo.attachment SET processing_stage=NULL, upload_lease_token=NULL,
                    upload_lease_until=NULL, row_version=row_version+1, updated_at=:now
                 WHERE company_id=:companyId AND id=:id AND status='UPLOADING'
                   AND upload_lease_token=:leaseToken AND sealed_at IS NULL
                """).param("now", utc(now)).param("companyId", companyId)
                .param("id", attachmentId).param("leaseToken", leaseToken).update();
    }

    @Override
    @Transactional
    public AttachmentRecord rejectUpload(UUID companyId, UUID attachmentId, UUID leaseToken,
            AttachmentRejectedCode code, Instant now) {
        AttachmentRecord current = locked(companyId, attachmentId);
        requireLease(current, leaseToken);
        adjustQuota(companyId, current.projectId(), -current.reservedBytes(), 0, now);
        jdbc.sql("""
                UPDATE yumpoo.attachment SET status='REJECTED', rejected_code=:code,
                    rejected_at=:now, reserved_bytes=0, processing_stage=NULL,
                    upload_lease_token=NULL, upload_lease_until=NULL,
                    row_version=row_version+1, updated_at=:now
                 WHERE company_id=:companyId AND id=:id AND upload_lease_token=:leaseToken
                """).param("code", code.name()).param("now", utc(now))
                .param("companyId", companyId).param("id", attachmentId)
                .param("leaseToken", leaseToken).update();
        return required(companyId, attachmentId);
    }

    @Override
    @Transactional
    public Optional<ScanClaim> claimDue(String workerId, UUID leaseToken, Instant now,
            Instant leaseUntil) {
        Optional<ScanClaim> claim = jdbc.sql("""
                WITH candidate AS (
                    SELECT id FROM yumpoo.attachment_scan_task
                     WHERE (status='READY' AND next_attempt_at <= :now)
                        OR (status='RUNNING' AND lease_until < :now)
                     ORDER BY next_attempt_at, created_at, id
                     FOR UPDATE SKIP LOCKED LIMIT 1
                ), claimed AS (
                    UPDATE yumpoo.attachment_scan_task t
                       SET status='RUNNING', attempt_count=attempt_count+1,
                           lease_owner=:workerId, lease_token=:leaseToken,
                           lease_until=:leaseUntil, updated_at=:now
                      FROM candidate c WHERE t.id=c.id
                    RETURNING t.*
                )
                SELECT c.id task_id, c.lease_token, a.*, c.generation task_generation,
                       c.attempt_count
                  FROM claimed c JOIN yumpoo.attachment a ON a.id=c.attachment_id
                """).param("now", utc(now)).param("workerId", workerId)
                .param("leaseToken", leaseToken).param("leaseUntil", utc(leaseUntil))
                .query(this::mapClaim).optional();
        claim.ifPresent(value -> jdbc.sql("""
                UPDATE yumpoo.attachment SET processing_stage='SCANNING',
                    row_version=row_version+1, updated_at=:now
                 WHERE id=:id AND company_id=:companyId AND status='UPLOADING'
                   AND scan_generation=:generation
                """).param("now", utc(now)).param("id", value.attachmentId())
                .param("companyId", value.companyId()).param("generation", value.generation()).update());
        return claim;
    }

    @Override
    @Transactional
    public void recordDetected(ScanClaim claim, String detectedMime, Instant now) {
        requireTaskLease(claim);
        jdbc.sql("UPDATE yumpoo.attachment SET detected_mime=:mime, updated_at=:now "
                + "WHERE id=:id AND company_id=:companyId AND scan_generation=:generation")
                .param("mime", detectedMime).param("now", utc(now)).param("id", claim.attachmentId())
                .param("companyId", claim.companyId()).param("generation", claim.generation()).update();
    }

    @Override
    @Transactional
    public void recordPublished(ScanClaim claim, String storageKey, Instant now) {
        requireTaskLease(claim);
        jdbc.sql("""
                INSERT INTO yumpoo.attachment_blob (
                    storage_key,sha256,size_bytes,presence_status,last_verified_at,created_at,updated_at
                ) VALUES (:key,:sha256,:size,'PRESENT',:now,:now,:now)
                ON CONFLICT (storage_key) DO UPDATE SET
                    presence_status='PRESENT',last_verified_at=:now,updated_at=:now,
                    row_version=yumpoo.attachment_blob.row_version+1
                 WHERE yumpoo.attachment_blob.sha256=:sha256
                   AND yumpoo.attachment_blob.size_bytes=:size
                """).param("key",storageKey).param("sha256",claim.sha256())
                .param("size",claim.sizeBytes()).param("now",utc(now)).update();
        jdbc.sql("UPDATE yumpoo.attachment SET storage_key=:key, processing_stage='FINALIZING', "
                + "updated_at=:now WHERE id=:id AND company_id=:companyId AND scan_generation=:generation")
                .param("key", storageKey).param("now", utc(now)).param("id", claim.attachmentId())
                .param("companyId", claim.companyId()).param("generation", claim.generation()).update();
    }

    @Override
    @Transactional
    public Boolean claimPublish(ScanClaim claim,String storageKey,String owner,UUID operationToken,
            Instant now,Instant leaseUntil) {
        jdbc.sql("""
                INSERT INTO yumpoo.attachment_blob (
                    storage_key,sha256,size_bytes,presence_status,created_at,updated_at
                ) VALUES (:key,:sha256,:size,'MISSING',:now,:now)
                ON CONFLICT (storage_key) DO NOTHING
                """).param("key",storageKey).param("sha256",claim.sha256())
                .param("size",claim.sizeBytes()).param("now",utc(now)).update();
        return jdbc.sql("""
                UPDATE yumpoo.attachment_blob SET operation_type='PUBLISH',operation_owner=:owner,
                    operation_token=:token,operation_lease_until=:until,updated_at=:now
                 WHERE storage_key=:key AND sha256=:sha256 AND size_bytes=:size
                   AND (operation_lease_until IS NULL OR operation_lease_until<=:now)
                """).param("owner",owner).param("token",operationToken).param("until",utc(leaseUntil))
                .param("now",utc(now)).param("key",storageKey).param("sha256",claim.sha256())
                .param("size",claim.sizeBytes()).update()==1;
    }

    @Override
    @Transactional
    public void completePublish(String storageKey,UUID operationToken,Instant now) {
        jdbc.sql("""
                UPDATE yumpoo.attachment_blob SET presence_status='PRESENT',last_verified_at=:now,
                    operation_type=NULL,operation_owner=NULL,operation_token=NULL,operation_lease_until=NULL,
                    updated_at=:now,row_version=row_version+1
                 WHERE storage_key=:key AND operation_type='PUBLISH' AND operation_token=:token
                """).param("now",utc(now)).param("key",storageKey).param("token",operationToken).update();
    }

    @Override
    @Transactional
    public void releasePublish(String storageKey,UUID operationToken,Instant now) {
        jdbc.sql("""
                UPDATE yumpoo.attachment_blob SET operation_type=NULL,operation_owner=NULL,
                    operation_token=NULL,operation_lease_until=NULL,updated_at=:now
                 WHERE storage_key=:key AND operation_type='PUBLISH' AND operation_token=:token
                """).param("now",utc(now)).param("key",storageKey).param("token",operationToken).update();
    }

    @Override
    @Transactional
    public Optional<Finalization> prepareFinalization(ScanClaim claim, String detectedMime,
            String storageKey, Instant now) {
        requireTaskLease(claim);
        AttachmentRecord current = locked(claim.companyId(), claim.attachmentId());
        if (current.status() != AttachmentState.UPLOADING
                || current.scanGeneration() != claim.generation()) return Optional.empty();
        return Optional.of(new Finalization(current.id(), current.companyId(), current.projectId(),
                current.ownerType(), current.ownerId(), current.uploadedByUserId(),
                current.originalFileName(), detectedMime, current.sizeBytes(), storageKey,
                claim.generation(), claim.taskId(), claim.leaseToken()));
    }

    @Override
    @Transactional
    public AttachmentRecord completeAvailable(Finalization finalization, Instant now) {
        AttachmentRecord current = locked(finalization.companyId(), finalization.attachmentId());
        if (current.status() == AttachmentState.AVAILABLE) return current;
        if (current.status() != AttachmentState.UPLOADING
                || current.scanGeneration() != finalization.generation()) throw invalid("STALE_SCAN_TASK");
        adjustQuota(current.companyId(), current.projectId(), -current.reservedBytes(),
                current.sizeBytes(), now);
        int changed = jdbc.sql("""
                UPDATE yumpoo.attachment SET status='AVAILABLE', processing_stage=NULL,
                    detected_mime=:mime, storage_key=:key, available_at=:now,
                    reserved_bytes=0, row_version=row_version+1, updated_at=:now
                 WHERE id=:id AND company_id=:companyId AND status='UPLOADING'
                   AND scan_generation=:generation
                """).param("mime", finalization.detectedMime()).param("key", finalization.storageKey())
                .param("now", utc(now)).param("id", current.id()).param("companyId", current.companyId())
                .param("generation", finalization.generation()).update();
        if (changed != 1) throw invalid("STALE_SCAN_TASK");
        completeTask(finalization.taskId(), finalization.leaseToken(), "AVAILABLE", now);
        return required(current.companyId(), current.id());
    }

    @Override
    @Transactional
    public void completeRejected(ScanClaim claim, AttachmentRejectedCode code, Instant now,
            Instant retainUntil) {
        requireTaskLease(claim);
        AttachmentRecord current = locked(claim.companyId(), claim.attachmentId());
        if (current.status() != AttachmentState.UPLOADING) return;
        adjustQuota(current.companyId(), current.projectId(), -current.reservedBytes(), 0, now);
        jdbc.sql("""
                UPDATE yumpoo.attachment SET status='REJECTED', processing_stage=NULL,
                    rejected_code=:code, rejected_at=:now, quarantine_retain_until=:retainUntil,
                    reserved_bytes=0, row_version=row_version+1, updated_at=:now
                 WHERE id=:id AND company_id=:companyId AND status='UPLOADING'
                   AND scan_generation=:generation
                """).param("code", code.name()).param("now", utc(now))
                .param("retainUntil", retainUntil == null ? null : utc(retainUntil))
                .param("id", claim.attachmentId()).param("companyId", claim.companyId())
                .param("generation", claim.generation()).update();
        completeTask(claim.taskId(), claim.leaseToken(), code.name(), now);
    }

    @Override
    @Transactional
    public void retry(ScanClaim claim, Instant nextAttemptAt, Instant now) {
        requireTaskLease(claim);
        jdbc.sql("""
                UPDATE yumpoo.attachment_scan_task SET status='READY', next_attempt_at=:next,
                    lease_owner=NULL, lease_token=NULL, lease_until=NULL, updated_at=:now
                 WHERE id=:id AND lease_token=:leaseToken AND status='RUNNING'
                """).param("next", utc(nextAttemptAt)).param("now", utc(now))
                .param("id", claim.taskId()).param("leaseToken", claim.leaseToken()).update();
        jdbc.sql("UPDATE yumpoo.attachment SET processing_stage='QUEUED_SCAN', updated_at=:now "
                + "WHERE id=:id AND company_id=:companyId AND status='UPLOADING'")
                .param("now", utc(now)).param("id", claim.attachmentId())
                .param("companyId", claim.companyId()).update();
    }

    @Override
    @Transactional
    public RescanResult rescan(UUID companyId, UUID attachmentId, long expectedVersion,
            long companyLimit, long projectLimit, Instant now) {
        AttachmentRecord current = locked(companyId, attachmentId);
        if (current.rowVersion() != expectedVersion) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        if (current.status() != AttachmentState.REJECTED
                || current.rejectedCode() != AttachmentRejectedCode.SCAN_UNAVAILABLE
                || current.quarantineRetainUntil() == null
                || !current.quarantineRetainUntil().isAfter(now)) throw invalid("RESCAN_NOT_ALLOWED");
        ensureQuotaRows(companyId, current.projectId(), now);
        lockAndRequireQuota(companyId, current.projectId(), current.sizeBytes(), companyLimit, projectLimit);
        adjustQuota(companyId, current.projectId(), current.sizeBytes(), 0, now);
        int generation = current.scanGeneration() + 1;
        jdbc.sql("""
                UPDATE yumpoo.attachment SET status='UPLOADING', processing_stage='QUEUED_SCAN',
                    rejected_code=NULL, rejected_at=NULL, reserved_bytes=size_bytes,
                    scan_generation=:generation, row_version=row_version+1, updated_at=:now
                 WHERE id=:id AND company_id=:companyId AND row_version=:version
                """).param("generation", generation).param("now", utc(now)).param("id", attachmentId)
                .param("companyId", companyId).param("version", expectedVersion).update();
        jdbc.sql("""
                INSERT INTO yumpoo.attachment_scan_task (
                    id,attachment_id,company_id,generation,status,next_attempt_at,created_at,updated_at
                ) VALUES (:taskId,:id,:companyId,:generation,'READY',:now,:now,:now)
                """).param("taskId", UUID.randomUUID()).param("id", attachmentId)
                .param("companyId", companyId).param("generation", generation).param("now", utc(now)).update();
        AttachmentRecord after = required(companyId, attachmentId);
        return new RescanResult(after.id(), after.status().name(), after.scanGeneration(),
                after.rowVersion(), StrongEtag.format(after.rowVersion()));
    }

    @Override
    @Transactional
    public AttachmentRecord delete(UUID companyId, UUID attachmentId, UUID deletedByUserId,
            String reason, long expectedVersion, Instant now) {
        AttachmentRecord current = locked(companyId, attachmentId);
        if (current.rowVersion() != expectedVersion) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
        if (current.status() != AttachmentState.AVAILABLE) {
            throw invalid("ATTACHMENT_NOT_DELETABLE");
        }
        ensureQuotaRows(companyId, current.projectId(), now);
        List<Quota> quotas = jdbc.sql("""
                SELECT scope_type,reserved_bytes,available_bytes
                  FROM yumpoo.attachment_quota_usage
                 WHERE company_id=:companyId
                   AND ((scope_type='COMPANY' AND scope_id=:companyId)
                     OR (scope_type='PROJECT' AND scope_id=:projectId))
                 ORDER BY scope_type FOR UPDATE
                """).param("companyId",companyId).param("projectId",current.projectId())
                .query((rs,row)->new Quota(rs.getString(1),rs.getLong(2),rs.getLong(3))).list();
        if (quotas.size()!=2 || quotas.stream().anyMatch(value->value.available()<current.sizeBytes())) {
            throw new ApplicationException(StandardErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        adjustQuota(companyId,current.projectId(),0,-current.sizeBytes(),now);
        int changed=jdbc.sql("""
                UPDATE yumpoo.attachment
                   SET status='DELETED',deleted_by_user_id=:deletedBy,deleted_at=:now,
                       delete_reason=:reason,row_version=row_version+1,updated_at=:now
                 WHERE company_id=:companyId AND id=:id AND status='AVAILABLE'
                   AND row_version=:version
                """).param("deletedBy",deletedByUserId).param("now",utc(now)).param("reason",reason)
                .param("companyId",companyId).param("id",attachmentId)
                .param("version",expectedVersion).update();
        if(changed!=1) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        return required(companyId,attachmentId);
    }

    @Override
    @Transactional
    public void recordReconciliationIssue(String issueCode,String subjectType,String subjectKey,
            UUID attachmentId,UUID companyId,Instant now) {
        int updated=jdbc.sql("""
                UPDATE yumpoo.attachment_reconciliation_issue
                   SET last_detected_at=:now,detection_count=detection_count+1,
                       row_version=row_version+1
                 WHERE issue_code=:code AND subject_type=:subjectType
                   AND subject_key=:subjectKey AND resolved_at IS NULL
                """).param("now",utc(now)).param("code",issueCode).param("subjectType",subjectType)
                .param("subjectKey",subjectKey).update();
        if(updated==0) {
            jdbc.sql("""
                    INSERT INTO yumpoo.attachment_reconciliation_issue (
                        id,issue_code,subject_type,subject_key,attachment_id,company_id,
                        first_detected_at,last_detected_at
                    ) VALUES (:id,:code,:subjectType,:subjectKey,:attachmentId,:companyId,:now,:now)
                    """).param("id",UUID.randomUUID()).param("code",issueCode)
                    .param("subjectType",subjectType).param("subjectKey",subjectKey)
                    .param("attachmentId",attachmentId).param("companyId",companyId)
                    .param("now",utc(now)).update();
        }
    }

    @Override
    @Transactional
    public void resolveReconciliationIssues(String subjectType,String subjectKey,Instant now) {
        jdbc.sql("""
                UPDATE yumpoo.attachment_reconciliation_issue
                   SET resolved_at=:now,row_version=row_version+1
                 WHERE subject_type=:subjectType AND subject_key=:subjectKey
                   AND resolved_at IS NULL
                """).param("now",utc(now)).param("subjectType",subjectType)
                .param("subjectKey",subjectKey).update();
    }

    private void lockAndRequireQuota(UUID companyId, UUID projectId, long bytes,
            long companyLimit, long projectLimit) {
        List<Quota> rows = jdbc.sql("""
                SELECT scope_type,reserved_bytes,available_bytes FROM yumpoo.attachment_quota_usage
                 WHERE company_id=:companyId AND ((scope_type='COMPANY' AND scope_id=:companyId)
                    OR (scope_type='PROJECT' AND scope_id=:projectId)) ORDER BY scope_type FOR UPDATE
                """).param("companyId", companyId).param("projectId", projectId)
                .query((rs,row)->new Quota(rs.getString(1),rs.getLong(2),rs.getLong(3))).list();
        for (Quota row : rows) {
            long limit = row.scopeType().equals("COMPANY") ? companyLimit : projectLimit;
            if (row.reserved() + row.available() + bytes > limit) throw invalid("QUOTA_EXCEEDED");
        }
    }

    private void ensureQuotaRows(UUID companyId, UUID projectId, Instant now) {
        jdbc.sql("""
                INSERT INTO yumpoo.attachment_quota_usage
                    (company_id,scope_type,scope_id,updated_at)
                VALUES (:companyId,'COMPANY',:companyId,:now),(:companyId,'PROJECT',:projectId,:now)
                ON CONFLICT DO NOTHING
                """).param("companyId", companyId).param("projectId", projectId)
                .param("now", utc(now)).update();
    }

    private void adjustQuota(UUID companyId, UUID projectId, long reservedDelta,
            long availableDelta, Instant now) {
        jdbc.sql("""
                UPDATE yumpoo.attachment_quota_usage SET
                    reserved_bytes=reserved_bytes+:reservedDelta,
                    available_bytes=available_bytes+:availableDelta,
                    row_version=row_version+1, updated_at=:now
                 WHERE company_id=:companyId AND ((scope_type='COMPANY' AND scope_id=:companyId)
                    OR (scope_type='PROJECT' AND scope_id=:projectId))
                """).param("reservedDelta", reservedDelta).param("availableDelta", availableDelta)
                .param("now", utc(now)).param("companyId", companyId).param("projectId", projectId).update();
    }

    private AttachmentRecord locked(UUID companyId, UUID attachmentId) {
        return jdbc.sql("SELECT * FROM yumpoo.attachment WHERE company_id=:companyId AND id=:id FOR UPDATE")
                .param("companyId", companyId).param("id", attachmentId)
                .query(this::mapAttachment).optional()
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private AttachmentRecord required(UUID companyId, UUID attachmentId) {
        return find(companyId, attachmentId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireLease(AttachmentRecord current, UUID leaseToken) {
        if (!leaseToken.equals(current.uploadLeaseToken())) throw invalid("UPLOAD_NOT_ACTIVE");
    }

    private void requireTaskLease(ScanClaim claim) {
        Integer count = jdbc.sql("SELECT count(*) FROM yumpoo.attachment_scan_task "
                + "WHERE id=:id AND lease_token=:token AND status='RUNNING'")
                .param("id", claim.taskId()).param("token", claim.leaseToken())
                .query(Integer.class).single();
        if (count != 1) throw invalid("STALE_SCAN_TASK");
    }

    private void completeTask(UUID taskId, UUID leaseToken, String result, Instant now) {
        jdbc.sql("""
                UPDATE yumpoo.attachment_scan_task SET status='COMPLETED', final_result=:result,
                    lease_owner=NULL,lease_token=NULL,lease_until=NULL,updated_at=:now
                 WHERE id=:id AND lease_token=:token AND status='RUNNING'
                """).param("result", result).param("now", utc(now))
                .param("id", taskId).param("token", leaseToken).update();
    }

    private AttachmentRecord mapAttachment(ResultSet rs, int row) throws SQLException {
        return new AttachmentRecord(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("quota_project_id", UUID.class), AttachmentOwnerType.valueOf(rs.getString("owner_type")),
                rs.getObject("owner_id", UUID.class), rs.getString("original_file_name"),
                rs.getString("file_extension"), rs.getString("declared_mime"), rs.getString("detected_mime"),
                nullableLong(rs, "size_bytes"), rs.getString("sha256"), rs.getString("storage_key"),
                AttachmentState.valueOf(rs.getString("status")), rs.getString("processing_stage"),
                enumOrNull(AttachmentRejectedCode.class, rs.getString("rejected_code")),
                rs.getLong("reserved_bytes"), rs.getObject("uploaded_by_user_id", UUID.class),
                instant(rs, "intent_expires_at"), instantOrNull(rs, "quarantine_retain_until"),
                instantOrNull(rs, "available_at"), rs.getObject("upload_lease_token", UUID.class),
                instantOrNull(rs, "upload_lease_until"),rs.getObject("deleted_by_user_id",UUID.class),
                instantOrNull(rs,"deleted_at"),rs.getString("delete_reason"),rs.getInt("scan_generation"),
                rs.getLong("row_version"), instant(rs, "created_at"));
    }

    private ScanClaim mapClaim(ResultSet rs, int row) throws SQLException {
        return new ScanClaim(rs.getObject("task_id", UUID.class), rs.getObject("lease_token", UUID.class),
                rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("quota_project_id", UUID.class), AttachmentOwnerType.valueOf(rs.getString("owner_type")),
                rs.getObject("owner_id", UUID.class), rs.getObject("uploaded_by_user_id", UUID.class),
                rs.getString("original_file_name"), rs.getString("declared_mime"), rs.getLong("size_bytes"),
                rs.getString("sha256"), rs.getString("detected_mime"), rs.getString("storage_key"),
                rs.getInt("task_generation"), rs.getInt("attempt_count"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }
    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
    private static OffsetDateTime utc(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static ApplicationException invalid(String reason) {
        return ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION, reason);
    }
    private record Quota(String scopeType, long reserved, long available) {}
}
