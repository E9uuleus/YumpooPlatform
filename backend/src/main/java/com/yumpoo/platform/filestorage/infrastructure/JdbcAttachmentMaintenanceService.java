package com.yumpoo.platform.filestorage.infrastructure;

import com.yumpoo.platform.filestorage.application.BlobVerification;
import com.yumpoo.platform.filestorage.application.PublishedBlob;
import com.yumpoo.platform.filestorage.application.QuarantineStorage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public final class JdbcAttachmentMaintenanceService {
    private static final Duration STALE_AFTER=Duration.ofHours(24);
    private static final Duration LEASE=Duration.ofSeconds(45);
    private final JdbcClient jdbc;
    private final QuarantineStorage storage;
    private final AttachmentProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public JdbcAttachmentMaintenanceService(JdbcClient jdbc,QuarantineStorage storage,
            AttachmentProperties properties,Clock clock,PlatformTransactionManager transactionManager) {
        this.jdbc=jdbc; this.storage=storage; this.properties=properties; this.clock=clock;
        this.transactions=new TransactionTemplate(transactionManager);
    }

    Optional<MaintenanceBatchResult> resumeOneBatch(String workerId) {
        Instant now=clock.instant(); UUID token=UUID.randomUUID();
        Optional<Run> claimed=claim(workerId,token,now);
        if(claimed.isEmpty()) return Optional.empty();
        Run run=claimed.orElseThrow(); Batch batch;
        try {
            batch=switch(run.phase()) {
                case "EXPIRE_INTENTS"->expireIntents(run,now);
                case "TEMPORARY_FILES"->temporaryFiles(run,now);
                case "VERIFY_BLOBS"->verifyBlobs(run,now);
                case "RECONCILE_QUOTAS"->reconcileQuotas(run,now);
                case "RECONCILE_SCANS"->reconcileScans(run,now);
                case "ORPHANS"->orphans(run,now);
                default->new Batch(0,0,0,null,true);
            };
            persist(run,batch,now);
            return Optional.of(new MaintenanceBatchResult(run.id(),run.phase(),batch.processed(),
                    batch.issues(),batch.deleted(),run.dryRun()));
        } catch(RuntimeException failure) {
            jdbc.sql("""
                    UPDATE yumpoo.attachment_maintenance_run SET status='FAILED',
                        lease_owner=NULL,lease_token=NULL,lease_until=NULL,completed_at=:now,updated_at=:now
                     WHERE id=:id AND lease_token=:token
                    """).param("now",utc(now)).param("id",run.id()).param("token",run.token()).update();
            throw failure;
        }
    }

    private Optional<Run> claim(String workerId,UUID token,Instant now) {
        Optional<Run> existing=jdbc.sql("""
                WITH candidate AS (
                    SELECT id FROM yumpoo.attachment_maintenance_run
                     WHERE status='RUNNING' AND lease_until <= :now
                     ORDER BY started_at,id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE yumpoo.attachment_maintenance_run r
                   SET lease_owner=:worker,lease_token=:token,lease_until=:leaseUntil,updated_at=:now
                  FROM candidate c WHERE r.id=c.id RETURNING r.*
                """).param("now",utc(now)).param("worker",workerId).param("token",token)
                .param("leaseUntil",utc(now.plus(LEASE))).query(this::mapRun).optional();
        if(existing.isPresent()) return existing;
        Long recent=jdbc.sql("""
                SELECT count(*) FROM yumpoo.attachment_maintenance_run
                 WHERE status='COMPLETED' AND completed_at > :cutoff
                """).param("cutoff",utc(now.minus(properties.getMaintenanceInterval())))
                .query(Long.class).single();
        Long running=jdbc.sql("SELECT count(*) FROM yumpoo.attachment_maintenance_run WHERE status='RUNNING'")
                .query(Long.class).single();
        if(recent>0||running>0) return Optional.empty();
        UUID runId=UUID.randomUUID();
        try {
            return jdbc.sql("""
                    INSERT INTO yumpoo.attachment_maintenance_run (
                        id,status,phase,dry_run,approval_reference,lease_owner,lease_token,lease_until,
                        started_at,updated_at
                    ) VALUES (:id,'RUNNING','EXPIRE_INTENTS',:dryRun,:approval,:worker,:token,
                        :leaseUntil,:now,:now) RETURNING *
                    """).param("id",runId).param("dryRun",!properties.isCleanupDeleteEnabled())
                    .param("approval",blankToNull(properties.getCleanupApprovalReference()))
                    .param("worker",workerId).param("token",token).param("leaseUntil",utc(now.plus(LEASE)))
                    .param("now",utc(now)).query(this::mapRun).optional();
        } catch(DataIntegrityViolationException race) { return Optional.empty(); }
    }

    private Batch expireIntents(Run run,Instant now) {
        List<Expired> rows=jdbc.sql("""
                SELECT a.id,a.company_id,a.quota_project_id,a.reserved_bytes
                  FROM yumpoo.attachment a
                 WHERE a.status='UPLOADING' AND a.intent_expires_at <= :now
                   AND (:cursor IS NULL OR a.id::text > :cursor)
                   AND (a.upload_lease_until IS NULL OR a.upload_lease_until <= :now)
                   AND NOT EXISTS (
                       SELECT 1 FROM yumpoo.attachment_scan_task t
                        WHERE t.attachment_id=a.id AND t.generation=a.scan_generation
                          AND t.status='RUNNING' AND t.lease_until > :now)
                 ORDER BY a.id::text LIMIT :limit
                """).param("cursor",run.cursor()).param("now",utc(now))
                .param("limit",properties.getMaintenanceBatchSize())
                .query((rs,row)->new Expired(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                        rs.getObject(3,UUID.class),rs.getLong(4))).list();
        long changed=0;
        for(Expired row:rows) {
            Boolean done=transactions.execute(status->expireOne(row,now));
            if(Boolean.TRUE.equals(done)) changed++;
        }
        return batch(rows,changed,0,0);
    }

    private boolean expireOne(Expired row,Instant now) {
        int changed=jdbc.sql("""
                UPDATE yumpoo.attachment SET status='REJECTED',rejected_code='UPLOAD_INCOMPLETE',
                    rejected_at=:now,reserved_bytes=0,processing_stage=NULL,upload_lease_token=NULL,
                    upload_lease_until=NULL,row_version=row_version+1,updated_at=:now
                 WHERE id=:id AND company_id=:companyId AND status='UPLOADING'
                   AND intent_expires_at <= :now
                   AND (upload_lease_until IS NULL OR upload_lease_until<=:now)
                   AND NOT EXISTS (SELECT 1 FROM yumpoo.attachment_scan_task t
                       WHERE t.attachment_id=:id AND t.generation=yumpoo.attachment.scan_generation
                         AND t.status='RUNNING' AND t.lease_until>:now)
                """).param("now",utc(now)).param("id",row.id()).param("companyId",row.companyId())
                .update();
        if(changed==0) return false;
        jdbc.sql("""
                UPDATE yumpoo.attachment_quota_usage SET reserved_bytes=reserved_bytes-:bytes,
                    row_version=row_version+1,updated_at=:now
                 WHERE company_id=:companyId AND ((scope_type='COMPANY' AND scope_id=:companyId)
                    OR (scope_type='PROJECT' AND scope_id=:projectId))
                """).param("bytes",row.reserved()).param("now",utc(now))
                .param("companyId",row.companyId()).param("projectId",row.projectId()).update();
        jdbc.sql("""
                UPDATE yumpoo.attachment_scan_task SET status='COMPLETED',final_result='UPLOAD_INCOMPLETE',
                    lease_owner=NULL,lease_token=NULL,lease_until=NULL,updated_at=:now
                 WHERE attachment_id=:id AND status IN ('READY','RUNNING')
                """).param("now",utc(now)).param("id",row.id()).update();
        return true;
    }

    private Batch temporaryFiles(Run run,Instant now) {
        List<QuarantineStorage.StorageEntry> rows;
        try { rows=storage.listTemporary(run.cursor(),properties.getMaintenanceBatchSize()); }
        catch(IOException failure) { throw new IllegalStateException("attachment quarantine enumeration failed",failure); }
        long issues=0,deleted=0;
        for(var row:rows) {
            if(row.unsafeEntry()||!row.regularFile()||!row.key().matches("^[0-9a-fA-F-]{36}\\.(part|sealed)$")) {
                observe("UNEXPECTED_ENTRY","QUARANTINE",row.key(),null,null,now,null); issues++; continue;
            }
            UUID id;
            try { id=UUID.fromString(row.key().substring(0,36)); }
            catch(IllegalArgumentException invalid) { observe("UNEXPECTED_ENTRY","QUARANTINE",row.key(),null,null,now,null); issues++; continue; }
            if(row.modifiedAt().isAfter(now.minus(STALE_AFTER))||temporaryProtected(id,now)) {
                resolveIssue("QUARANTINE_ORPHAN","QUARANTINE",row.key(),now); continue;
            }
            Instant first=observe("QUARANTINE_ORPHAN","QUARANTINE",row.key(),null,null,now,now.plus(STALE_AFTER));
            issues++;
            if(!run.dryRun()&&!first.isAfter(now.minus(STALE_AFTER))) {
                try { if(storage.deleteTemporary(row.key())) { deleted++; resolveIssue("QUARANTINE_ORPHAN","QUARANTINE",row.key(),now); } }
                catch(IOException ignored) { }
            }
        }
        return batch(rows,rows.size(),issues,deleted);
    }

    private boolean temporaryProtected(UUID id,Instant now) {
        Long count=jdbc.sql("""
                SELECT count(*) FROM yumpoo.attachment a WHERE a.id=:id AND (
                    (a.status='UPLOADING' AND (a.intent_expires_at>:now OR a.upload_lease_until>:now
                        OR EXISTS (SELECT 1 FROM yumpoo.attachment_scan_task t WHERE t.attachment_id=a.id
                            AND t.status IN ('READY','RUNNING') AND (t.lease_until IS NULL OR t.lease_until>:now))))
                    OR (a.status='REJECTED' AND a.quarantine_retain_until>:now))
                """).param("id",id).param("now",utc(now)).query(Long.class).single();
        return count>0;
    }

    private Batch verifyBlobs(Run run,Instant now) {
        List<Blob> rows=jdbc.sql("""
                SELECT storage_key,sha256,size_bytes FROM yumpoo.attachment_blob
                 WHERE (:cursor IS NULL OR storage_key>:cursor)
                   AND presence_status<>'DELETED' ORDER BY storage_key LIMIT :limit
                """).param("cursor",run.cursor()).param("limit",properties.getMaintenanceBatchSize())
                .query((rs,row)->new Blob(rs.getString(1),rs.getString(2),rs.getLong(3))).list();
        long issues=0;
        for(Blob row:rows) {
            try {
                BlobVerification result=storage.inspect(new PublishedBlob(row.key(),row.size(),row.sha256()));
                if(result==BlobVerification.VERIFIED) {
                    jdbc.sql("UPDATE yumpoo.attachment_blob SET presence_status='PRESENT',last_verified_at=:now,updated_at=:now WHERE storage_key=:key")
                            .param("now",utc(now)).param("key",row.key()).update();
                    resolveIssue("MISSING_BLOB","BLOB",row.key(),now);
                    resolveIssue("SIZE_MISMATCH","BLOB",row.key(),now);
                    resolveIssue("HASH_MISMATCH","BLOB",row.key(),now);
                } else {
                    observe(issueCode(result),"BLOB",row.key(),null,null,now,null); issues++;
                    if(result==BlobVerification.MISSING) jdbc.sql("UPDATE yumpoo.attachment_blob SET presence_status='MISSING',updated_at=:now WHERE storage_key=:key")
                            .param("now",utc(now)).param("key",row.key()).update();
                }
            } catch(IOException failure) { observe("MISSING_BLOB","BLOB",row.key(),null,null,now,null); issues++; }
        }
        return batch(rows,rows.size(),issues,0);
    }

    private Batch reconcileQuotas(Run run,Instant now) {
        if(run.cursor()==null) resolveCode("QUOTA_MISMATCH",now);
        List<String> mismatches=jdbc.sql("""
                WITH expected AS (
                    SELECT company_id,'COMPANY' scope_type,company_id scope_id,
                           coalesce(sum(reserved_bytes) FILTER (WHERE status='UPLOADING'),0) reserved,
                           coalesce(sum(size_bytes) FILTER (WHERE status='AVAILABLE'),0) available
                      FROM yumpoo.attachment GROUP BY company_id
                    UNION ALL
                    SELECT company_id,'PROJECT',quota_project_id,
                           coalesce(sum(reserved_bytes) FILTER (WHERE status='UPLOADING'),0),
                           coalesce(sum(size_bytes) FILTER (WHERE status='AVAILABLE'),0)
                      FROM yumpoo.attachment GROUP BY company_id,quota_project_id
                )
                SELECT q.company_id||':'||q.scope_type||':'||q.scope_id
                  FROM yumpoo.attachment_quota_usage q
                  LEFT JOIN expected e ON e.company_id=q.company_id AND e.scope_type=q.scope_type AND e.scope_id=q.scope_id
                 WHERE (q.reserved_bytes<>coalesce(e.reserved,0) OR q.available_bytes<>coalesce(e.available,0))
                   AND (:cursor IS NULL OR q.company_id||':'||q.scope_type||':'||q.scope_id>:cursor)
                 ORDER BY 1 LIMIT :limit
                """).param("cursor",run.cursor()).param("limit",properties.getMaintenanceBatchSize())
                .query(String.class).list();
        mismatches.forEach(key->observe("QUOTA_MISMATCH","QUOTA",key,null,null,now,null));
        return batch(mismatches,mismatches.size(),mismatches.size(),0);
    }

    private Batch reconcileScans(Run run,Instant now) {
        if(run.cursor()==null) resolveCode("STALE_SCAN_TASK",now);
        List<String> rows=jdbc.sql("""
                SELECT t.id::text FROM yumpoo.attachment_scan_task t
                  JOIN yumpoo.attachment a ON a.id=t.attachment_id
                 WHERE t.status IN ('READY','RUNNING')
                   AND (a.status<>'UPLOADING' OR a.scan_generation<>t.generation
                        OR (t.status='RUNNING' AND t.lease_until<:cutoff))
                   AND (:cursor IS NULL OR t.id::text>:cursor)
                 ORDER BY t.id::text LIMIT :limit
                """).param("cutoff",utc(now.minus(STALE_AFTER))).param("cursor",run.cursor())
                .param("limit",properties.getMaintenanceBatchSize()).query(String.class).list();
        rows.forEach(key->observe("STALE_SCAN_TASK","SCAN_TASK",key,null,null,now,null));
        return batch(rows,rows.size(),rows.size(),0);
    }

    private Batch orphans(Run run,Instant now) {
        List<QuarantineStorage.StorageEntry> rows;
        try { rows=storage.listPublished(run.cursor(),properties.getMaintenanceBatchSize()); }
        catch(IOException failure) { throw new IllegalStateException("attachment blob enumeration failed",failure); }
        long issues=0,deleted=0;
        for(var row:rows) {
            if(row.unsafeEntry()||!row.regularFile()||!row.key().matches("^sha256/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{64}$")) {
                observe("UNEXPECTED_ENTRY","BLOB",row.key(),null,null,now,null); issues++; continue;
            }
            String sha=row.key().substring(row.key().length()-64);
            Long refs=jdbc.sql("""
                    SELECT count(*) FROM yumpoo.attachment
                     WHERE (storage_key=:key AND status IN ('UPLOADING','AVAILABLE','DELETED'))
                        OR (sha256=:sha AND status='UPLOADING')
                    """).param("key",row.key()).param("sha",sha).query(Long.class).single();
            if(refs>0||row.modifiedAt().isAfter(now.minus(STALE_AFTER))) {
                resolveIssue("PUBLISHED_ORPHAN","BLOB",row.key(),now); continue;
            }
            Instant first=observe("PUBLISHED_ORPHAN","BLOB",row.key(),null,null,now,now.plus(STALE_AFTER));
            issues++;
            if(!run.dryRun()&&!first.isAfter(now.minus(STALE_AFTER))&&!activeBlobLease(row.key(),now)) {
                try {
                    if(claimCleanup(row.key(),row.sizeBytes(),run,now)&&storage.deletePublished(row.key())) {
                        deleted++;
                        jdbc.sql("UPDATE yumpoo.attachment_blob SET presence_status='DELETED',operation_type=NULL,operation_owner=NULL,operation_token=NULL,operation_lease_until=NULL,updated_at=:now WHERE storage_key=:key")
                                .param("now",utc(now)).param("key",row.key()).update();
                        resolveIssue("PUBLISHED_ORPHAN","BLOB",row.key(),now);
                    }
                } catch(IOException ignored) { }
            }
        }
        return batch(rows,rows.size(),issues,deleted);
    }

    private boolean activeBlobLease(String key,Instant now) {
        Long count=jdbc.sql("SELECT count(*) FROM yumpoo.attachment_blob WHERE storage_key=:key AND operation_lease_until>:now")
                .param("key",key).param("now",utc(now)).query(Long.class).single();
        return count>0;
    }

    private boolean claimCleanup(String key,long size,Run run,Instant now) {
        String sha=key.substring(key.length()-64);
        jdbc.sql("""
                INSERT INTO yumpoo.attachment_blob (storage_key,sha256,size_bytes,presence_status,created_at,updated_at)
                VALUES (:key,:sha,:size,'PRESENT',:now,:now) ON CONFLICT (storage_key) DO NOTHING
                """).param("key",key).param("sha",sha).param("size",size).param("now",utc(now)).update();
        return jdbc.sql("""
                UPDATE yumpoo.attachment_blob SET operation_type='CLEANUP',operation_owner=:owner,
                    operation_token=:token,operation_lease_until=:until,updated_at=:now
                 WHERE storage_key=:key AND (operation_lease_until IS NULL OR operation_lease_until<=:now)
                   AND NOT EXISTS (SELECT 1 FROM yumpoo.attachment a WHERE
                       (a.storage_key=:key AND a.status IN ('UPLOADING','AVAILABLE','DELETED'))
                       OR (a.sha256=yumpoo.attachment_blob.sha256 AND a.status='UPLOADING'))
                """).param("owner","maintenance:"+run.id()).param("token",UUID.randomUUID())
                .param("until",utc(now.plus(LEASE))).param("now",utc(now)).param("key",key).update()==1;
    }

    private Instant observe(String code,String subjectType,String subjectKey,UUID attachmentId,
            UUID companyId,Instant now,Instant eligibleAt) {
        return jdbc.sql("""
                INSERT INTO yumpoo.attachment_reconciliation_issue (
                    id,issue_code,subject_type,subject_key,attachment_id,company_id,
                    first_detected_at,last_detected_at,cleanup_eligible_at
                ) VALUES (:id,:code,:type,:key,:attachmentId,:companyId,:now,:now,:eligible)
                ON CONFLICT (issue_code,subject_type,subject_key) WHERE resolved_at IS NULL
                DO UPDATE SET last_detected_at=:now,detection_count=yumpoo.attachment_reconciliation_issue.detection_count+1,
                    row_version=yumpoo.attachment_reconciliation_issue.row_version+1
                RETURNING first_detected_at
                """).param("id",UUID.randomUUID()).param("code",code).param("type",subjectType)
                .param("key",subjectKey).param("attachmentId",attachmentId).param("companyId",companyId)
                .param("now",utc(now)).param("eligible",eligibleAt==null?null:utc(eligibleAt))
                .query(OffsetDateTime.class).single().toInstant();
    }

    private void resolveCode(String code,Instant now) {
        jdbc.sql("UPDATE yumpoo.attachment_reconciliation_issue SET resolved_at=:now,row_version=row_version+1 WHERE issue_code=:code AND resolved_at IS NULL")
                .param("now",utc(now)).param("code",code).update();
    }

    private void resolveIssue(String code,String type,String key,Instant now) {
        jdbc.sql("UPDATE yumpoo.attachment_reconciliation_issue SET resolved_at=:now,row_version=row_version+1 WHERE issue_code=:code AND subject_type=:type AND subject_key=:key AND resolved_at IS NULL")
                .param("now",utc(now)).param("code",code).param("type",type).param("key",key).update();
    }

    private static String issueCode(BlobVerification value) {
        return value==BlobVerification.MISSING?"MISSING_BLOB":value.name();
    }

    private void persist(Run run,Batch batch,Instant now) {
        boolean complete=run.phase().equals("ORPHANS")&&batch.finished();
        String next=complete?"COMPLETED":batch.finished()?nextPhase(run.phase()):run.phase();
        String cursor=batch.finished()?null:batch.cursor();
        jdbc.sql("""
                UPDATE yumpoo.attachment_maintenance_run SET
                    status=:status,phase=:phase,cursor_value=:cursor,
                    expired_intents=expired_intents+CASE WHEN :oldPhase='EXPIRE_INTENTS' THEN :processed ELSE 0 END,
                    temporary_candidates=temporary_candidates+CASE WHEN :oldPhase='TEMPORARY_FILES' THEN :processed ELSE 0 END,
                    verified_blobs=verified_blobs+CASE WHEN :oldPhase='VERIFY_BLOBS' THEN :processed ELSE 0 END,
                    orphan_candidates=orphan_candidates+CASE WHEN :oldPhase='ORPHANS' THEN :processed ELSE 0 END,
                    deleted_files=deleted_files+:deleted,issue_count=issue_count+:issues,
                    lease_owner=CASE WHEN :complete THEN NULL ELSE lease_owner END,
                    lease_token=CASE WHEN :complete THEN NULL ELSE lease_token END,
                    lease_until=CASE WHEN :complete THEN NULL ELSE :now END,
                    completed_at=CASE WHEN :complete THEN :now ELSE completed_at END,updated_at=:now
                 WHERE id=:id AND lease_token=:token
                """).param("status",complete?"COMPLETED":"RUNNING").param("phase",next)
                .param("cursor",cursor).param("oldPhase",run.phase()).param("processed",batch.processed())
                .param("deleted",batch.deleted()).param("issues",batch.issues()).param("complete",complete)
                .param("now",utc(now)).param("id",run.id()).param("token",run.token()).update();
    }

    private Batch batch(List<?> rows,long processed,long issues,long deleted) {
        boolean finished=rows.size()<properties.getMaintenanceBatchSize();
        String cursor=rows.isEmpty()?null:key(rows.getLast());
        return new Batch(processed,issues,deleted,cursor,finished);
    }
    private static String key(Object value) {
        if(value instanceof Expired row)return row.id().toString();
        if(value instanceof Blob row)return row.key();
        if(value instanceof QuarantineStorage.StorageEntry row)return row.key();
        return value.toString();
    }
    private static String nextPhase(String phase) { return switch(phase) {
        case "EXPIRE_INTENTS"->"TEMPORARY_FILES"; case "TEMPORARY_FILES"->"VERIFY_BLOBS";
        case "VERIFY_BLOBS"->"RECONCILE_QUOTAS"; case "RECONCILE_QUOTAS"->"RECONCILE_SCANS";
        case "RECONCILE_SCANS"->"ORPHANS"; default->"COMPLETED"; }; }
    private Run mapRun(ResultSet rs,int row)throws SQLException {
        return new Run(rs.getObject("id",UUID.class),rs.getString("phase"),rs.getString("cursor_value"),
                rs.getBoolean("dry_run"),rs.getObject("lease_token",UUID.class));
    }
    private static OffsetDateTime utc(Instant value){return value.atOffset(ZoneOffset.UTC);}
    private static String blankToNull(String value){return value==null||value.isBlank()?null:value.strip();}
    private record Run(UUID id,String phase,String cursor,boolean dryRun,UUID token){}
    private record Batch(long processed,long issues,long deleted,String cursor,boolean finished){}
    private record Expired(UUID id,UUID companyId,UUID projectId,long reserved){}
    private record Blob(String key,String sha256,long size){}
    record MaintenanceBatchResult(UUID runId,String phase,long processed,long issues,long deleted,
            boolean dryRun) {}
}
