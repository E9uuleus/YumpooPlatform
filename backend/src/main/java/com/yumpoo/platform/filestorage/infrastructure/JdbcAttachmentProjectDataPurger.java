package com.yumpoo.platform.filestorage.infrastructure;

import com.yumpoo.platform.filestorage.application.QuarantineStorage;
import com.yumpoo.platform.foundation.application.purge.ProjectDataPurger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Component
public final class JdbcAttachmentProjectDataPurger implements ProjectDataPurger {
    private static final Duration CLEANUP_LEASE = Duration.ofMinutes(5);
    private final JdbcClient jdbc;
    private final QuarantineStorage storage;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public JdbcAttachmentProjectDataPurger(JdbcClient jdbc, QuarantineStorage storage,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.clock = clock;
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public String stage() { return "FILESTORAGE"; }
    @Override public int order() { return 30; }

    @Override
    public boolean purgeBatch(UUID companyId, UUID projectId, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("purge limit must be between 1 and 500");
        transactions.executeWithoutResult(status -> start(companyId, projectId));
        int remaining = limit;
        remaining -= transactions.execute(status -> deleteIssues(companyId, projectId, limit));
        if (remaining == 0) return true;
        int scanBudget = remaining;
        remaining -= transactions.execute(status -> deleteScans(companyId, projectId, scanBudget));
        if (remaining == 0) return true;
        for (Attachment row : candidates(companyId, projectId, remaining)) {
            if (!purgeFiles(companyId, projectId, row)) continue;
            if (Boolean.TRUE.equals(transactions.execute(status -> deleteMetadata(companyId, projectId, row)))) remaining--;
        }
        if (remaining == 0) return true;
        transactions.executeWithoutResult(status -> deleteProjectQuota(companyId, projectId));
        return hasMetadata(companyId, projectId) || hasQuota(companyId, projectId) || hasIssues(companyId, projectId);
    }

    private void start(UUID companyId, UUID projectId) {
        jdbc.sql("""
                INSERT INTO yumpoo.attachment_quota_usage(company_id,scope_type,scope_id,updated_at)
                VALUES (:company,'COMPANY',:company,:now) ON CONFLICT DO NOTHING
                """).param("company", companyId).param("now", utc(clock.instant())).update();
        lockQuotas(companyId, projectId);
        jdbc.sql("""
                INSERT INTO yumpoo.attachment_project_purge(company_id,project_id,started_at)
                VALUES (:company,:project,:now) ON CONFLICT DO NOTHING
                """).param("company", companyId).param("project", projectId).param("now", utc(clock.instant())).update();
    }

    private int deleteIssues(UUID companyId, UUID projectId, int limit) {
        return jdbc.sql("""
                WITH candidates AS (
                    SELECT i.id FROM yumpoo.attachment_reconciliation_issue i
                     WHERE i.subject_type='QUOTA' AND i.subject_key=:quotaKey
                        OR EXISTS (SELECT 1 FROM yumpoo.attachment a
                            WHERE a.company_id=:company AND a.quota_project_id=:project
                              AND (i.attachment_id=a.id
                                OR i.subject_type='ATTACHMENT' AND i.subject_key=a.id::text
                                OR i.subject_type='QUARANTINE' AND i.subject_key IN (a.id||'.part',a.id||'.sealed')
                                OR i.subject_type='SCAN_TASK' AND EXISTS (SELECT 1 FROM yumpoo.attachment_scan_task t
                                    WHERE t.attachment_id=a.id AND t.id::text=i.subject_key)
                                OR i.subject_type='BLOB' AND i.subject_key=coalesce(a.storage_key,
                                    'sha256/'||left(a.sha256,2)||'/'||substring(a.sha256 FROM 3 FOR 2)||'/'||a.sha256)
                                    AND NOT EXISTS (SELECT 1 FROM yumpoo.attachment external
                                        WHERE (external.company_id<>:company OR external.quota_project_id<>:project)
                                          AND (external.storage_key=i.subject_key OR external.sha256=right(i.subject_key,64)))))
                     ORDER BY i.id LIMIT :limit FOR UPDATE OF i SKIP LOCKED
                ) DELETE FROM yumpoo.attachment_reconciliation_issue i USING candidates c WHERE i.id=c.id
                """).param("company", companyId).param("project", projectId).param("limit", limit)
                .param("quotaKey", quotaKey(companyId, projectId)).update();
    }

    private int deleteScans(UUID companyId, UUID projectId, int limit) {
        return jdbc.sql("""
                WITH candidates AS (
                    SELECT t.id FROM yumpoo.attachment_scan_task t JOIN yumpoo.attachment a ON a.id=t.attachment_id
                     WHERE a.company_id=:company AND a.quota_project_id=:project
                       AND (t.status<>'RUNNING' OR t.lease_until<=:now)
                       AND NOT EXISTS (SELECT 1 FROM yumpoo.attachment_reconciliation_issue i
                            WHERE i.subject_type='SCAN_TASK' AND i.subject_key=t.id::text)
                     ORDER BY t.id LIMIT :limit FOR UPDATE OF t SKIP LOCKED
                ) DELETE FROM yumpoo.attachment_scan_task t USING candidates c WHERE t.id=c.id
                """).param("company", companyId).param("project", projectId).param("limit", limit)
                .param("now", utc(clock.instant())).update();
    }

    private List<Attachment> candidates(UUID companyId, UUID projectId, int limit) {
        return jdbc.sql("""
                SELECT a.id,a.storage_key,a.sha256,coalesce(a.size_bytes,0) FROM yumpoo.attachment a
                 WHERE a.company_id=:company AND a.quota_project_id=:project
                   AND (a.upload_lease_until IS NULL OR a.upload_lease_until<=:now)
                   AND NOT EXISTS (SELECT 1 FROM yumpoo.attachment_scan_task t WHERE t.attachment_id=a.id)
                   AND NOT EXISTS (SELECT 1 FROM yumpoo.attachment_reconciliation_issue i WHERE i.attachment_id=a.id)
                 ORDER BY a.id LIMIT :limit
                """).param("company", companyId).param("project", projectId).param("limit", limit)
                .param("now", utc(clock.instant())).query((rs, n) -> new Attachment(rs.getObject(1, UUID.class),
                        rs.getString(2), rs.getString(3), rs.getLong(4))).list();
    }

    private boolean purgeFiles(UUID companyId, UUID projectId, Attachment row) {
        BlobClaim claim = transactions.execute(status -> claimCleanup(companyId, projectId, row));
        if (claim == null) return false;
        try {
            storage.purgeTemporary(row.id());
            if (claim.key() == null) return true;
            return Boolean.TRUE.equals(transactions.execute(status -> unlinkBlob(companyId, projectId, claim)));
        } catch (IOException failure) {
            throw new IllegalStateException("project attachment physical purge failed", failure);
        }
    }

    private BlobClaim claimCleanup(UUID companyId, UUID projectId, Attachment row) {
        String key = row.key();
        if (key == null && row.sha() != null) key = "sha256/" + row.sha().substring(0, 2) + "/" + row.sha().substring(2, 4) + "/" + row.sha();
        if (key == null) return new BlobClaim(null, null);
        String sha = key.substring(key.length() - 64);
        jdbc.sql("""
                INSERT INTO yumpoo.attachment_blob(storage_key,sha256,size_bytes,presence_status,created_at,updated_at)
                SELECT :key,:sha,:size,'MISSING',:now,:now WHERE :size>0 ON CONFLICT DO NOTHING
                """).param("key", key).param("sha", sha).param("size", row.size())
                .param("now", utc(clock.instant())).update();
        if (externalReference(companyId, projectId, key, sha)) return new BlobClaim(null, null);
        UUID token = UUID.randomUUID();
        Instant now = clock.instant();
        int claimed = jdbc.sql("""
                UPDATE yumpoo.attachment_blob SET operation_type='CLEANUP',operation_owner=:owner,
                    operation_token=:token,operation_lease_until=:until,updated_at=:now,row_version=row_version+1
                 WHERE storage_key=:key AND (operation_lease_until IS NULL OR operation_lease_until<=:now)
                """).param("owner", "project-purge:" + projectId).param("token", token)
                .param("until", utc(now.plus(CLEANUP_LEASE))).param("now", utc(now)).param("key", key).update();
        if (claimed == 1) return new BlobClaim(key, token);
        boolean registered = jdbc.sql("SELECT EXISTS (SELECT 1 FROM yumpoo.attachment_blob WHERE storage_key=:key)")
                .param("key", key).query(Boolean.class).single();
        if (!registered) throw new IllegalStateException("project blob checkpoint requires a positive size");
        return null;
    }

    private boolean unlinkBlob(UUID companyId, UUID projectId, BlobClaim claim) {
        String sha = jdbc.sql("""
                SELECT sha256 FROM yumpoo.attachment_blob WHERE storage_key=:key AND operation_type='CLEANUP'
                    AND operation_token=:token AND operation_lease_until>:now FOR UPDATE
                """).param("key", claim.key()).param("token", claim.token()).param("now", utc(clock.instant()))
                .query(String.class).optional().orElse(null);
        if (sha == null) return false;
        if (externalReference(companyId, projectId, claim.key(), sha)) {
            releaseCleanup(claim, false);
            return true;
        }
        // This transaction holds one blob row only while unlinking; no streaming, hashing or copying occurs here.
        // An expired/stolen cleanup token cannot unlink a blob published by a newer owner.
        try { storage.purgePublished(claim.key()); }
        catch (IOException failure) { throw new IllegalStateException("project blob physical purge failed", failure); }
        releaseCleanup(claim, true);
        return true;
    }

    private void releaseCleanup(BlobClaim claim, boolean deleted) {
        jdbc.sql("""
                UPDATE yumpoo.attachment_blob SET presence_status=CASE WHEN :deleted THEN 'DELETED' ELSE presence_status END,
                    operation_type=NULL,operation_owner=NULL,operation_token=NULL,operation_lease_until=NULL,
                    orphan_first_seen_at=NULL,updated_at=:now,row_version=row_version+1
                 WHERE storage_key=:key AND operation_type='CLEANUP' AND operation_token=:token
                """).param("deleted", deleted).param("now", utc(clock.instant()))
                .param("key", claim.key()).param("token", claim.token()).update();
    }

    private boolean externalReference(UUID companyId, UUID projectId, String key, String sha) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM yumpoo.attachment a
                    WHERE (a.company_id<>:company OR a.quota_project_id<>:project)
                      AND (a.storage_key=:key OR a.sha256=:sha))
                """).param("company", companyId).param("project", projectId).param("key", key)
                .param("sha", sha).query(Boolean.class).single();
    }

    private boolean deleteMetadata(UUID companyId, UUID projectId, Attachment candidate) {
        UUID attachmentId = candidate.id();
        lockQuotas(companyId, projectId);
        var row = jdbc.sql("""
                SELECT status,reserved_bytes,coalesce(size_bytes,0) FROM yumpoo.attachment
                 WHERE company_id=:company AND quota_project_id=:project AND id=:id
                   AND (upload_lease_until IS NULL OR upload_lease_until<=:now) FOR UPDATE SKIP LOCKED
                """).param("company", companyId).param("project", projectId).param("id", attachmentId)
                .param("now", utc(clock.instant())).query((rs, n) -> new Usage(rs.getString(1), rs.getLong(2), rs.getLong(3))).optional();
        if (row.isEmpty()) return false;
        boolean children = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM yumpoo.attachment_scan_task WHERE attachment_id=:id)
                    OR EXISTS (SELECT 1 FROM yumpoo.attachment_reconciliation_issue WHERE attachment_id=:id)
                """).param("id", attachmentId).query(Boolean.class).single();
        if (children) return false;
        String key = candidate.key();
        if (key == null && candidate.sha() != null) key = "sha256/" + candidate.sha().substring(0, 2) + "/"
                + candidate.sha().substring(2, 4) + "/" + candidate.sha();
        if (key != null) {
            var blob = jdbc.sql("SELECT presence_status FROM yumpoo.attachment_blob WHERE storage_key=:key FOR UPDATE SKIP LOCKED")
                    .param("key", key).query(String.class).optional();
            boolean registered = jdbc.sql("SELECT EXISTS (SELECT 1 FROM yumpoo.attachment_blob WHERE storage_key=:key)")
                    .param("key", key).query(Boolean.class).single();
            if (registered && (blob.isEmpty() || (!"DELETED".equals(blob.orElseThrow())
                    && !externalReference(companyId, projectId, key, key.substring(key.length() - 64))))) return false;
        }
        Usage usage = row.orElseThrow();
        long reserved = usage.status().equals("UPLOADING") ? usage.reserved() : 0;
        long available = usage.status().equals("AVAILABLE") ? usage.size() : 0;
        adjustQuotas(companyId, projectId, reserved, available);
        return jdbc.sql("DELETE FROM yumpoo.attachment WHERE company_id=:company AND quota_project_id=:project AND id=:id")
                .param("company", companyId).param("project", projectId).param("id", attachmentId).update() == 1;
    }

    private void lockQuotas(UUID companyId, UUID projectId) {
        jdbc.sql("""
                SELECT scope_type FROM yumpoo.attachment_quota_usage WHERE company_id=:company
                    AND ((scope_type='COMPANY' AND scope_id=:company) OR (scope_type='PROJECT' AND scope_id=:project))
                 ORDER BY scope_type FOR UPDATE
                """).param("company", companyId).param("project", projectId).query(String.class).list();
    }

    private void adjustQuotas(UUID companyId, UUID projectId, long reserved, long available) {
        jdbc.sql("""
                UPDATE yumpoo.attachment_quota_usage SET reserved_bytes=reserved_bytes-:reserved,
                    available_bytes=available_bytes-:available,row_version=row_version+1,updated_at=:now
                 WHERE company_id=:company AND ((scope_type='COMPANY' AND scope_id=:company)
                    OR (scope_type='PROJECT' AND scope_id=:project))
                """).param("reserved", reserved).param("available", available).param("now", utc(clock.instant()))
                .param("company", companyId).param("project", projectId).update();
    }

    private void deleteProjectQuota(UUID companyId, UUID projectId) {
        lockQuotas(companyId, projectId);
        if (hasMetadata(companyId, projectId)) return;
        var quota = jdbc.sql("""
                SELECT reserved_bytes,available_bytes FROM yumpoo.attachment_quota_usage
                 WHERE company_id=:company AND scope_type='PROJECT' AND scope_id=:project
                """).param("company", companyId).param("project", projectId)
                .query((rs, n) -> new Usage("PROJECT", rs.getLong(1), rs.getLong(2))).optional();
        if (quota.isEmpty()) return;
        Usage value = quota.orElseThrow();
        adjustQuotas(companyId, projectId, value.reserved(), value.size());
        jdbc.sql("DELETE FROM yumpoo.attachment_quota_usage WHERE company_id=:company AND scope_type='PROJECT' AND scope_id=:project")
                .param("company", companyId).param("project", projectId).update();
    }

    private boolean hasMetadata(UUID companyId, UUID projectId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM yumpoo.attachment WHERE company_id=:company AND quota_project_id=:project)")
                .param("company", companyId).param("project", projectId).query(Boolean.class).single();
    }

    private boolean hasQuota(UUID companyId, UUID projectId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM yumpoo.attachment_quota_usage WHERE company_id=:company AND scope_type='PROJECT' AND scope_id=:project)")
                .param("company", companyId).param("project", projectId).query(Boolean.class).single();
    }

    private boolean hasIssues(UUID companyId, UUID projectId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM yumpoo.attachment_reconciliation_issue WHERE subject_type='QUOTA' AND subject_key=:key)")
                .param("key", quotaKey(companyId, projectId)).query(Boolean.class).single();
    }

    private static String quotaKey(UUID companyId, UUID projectId) { return companyId + ":PROJECT:" + projectId; }
    private static OffsetDateTime utc(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private record Attachment(UUID id, String key, String sha, long size) { }
    private record BlobClaim(String key, UUID token) { }
    private record Usage(String status, long reserved, long size) { }
}
