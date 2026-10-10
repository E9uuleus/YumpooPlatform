package com.yumpoo.platform.filestorage.infrastructure;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

final class JdbcAttachmentPurgeFence {
    private JdbcAttachmentPurgeFence() { }

    static void requireWritable(JdbcClient jdbc, UUID companyId, UUID projectId) {
        if (!writable(jdbc, companyId, projectId)) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION, "PROJECT_PURGING");
        }
    }

    private static boolean writable(JdbcClient jdbc, UUID companyId, UUID projectId) {
        // The same company quota lock serializes the purge marker with writers that already passed owner checks.
        jdbc.sql("SELECT scope_id FROM yumpoo.attachment_quota_usage WHERE company_id=:company AND scope_type='COMPANY' FOR UPDATE")
                .param("company", companyId).query(UUID.class).list();
        return !jdbc.sql("SELECT EXISTS (SELECT 1 FROM yumpoo.attachment_project_purge WHERE company_id=:company AND project_id=:project)")
                .param("company", companyId).param("project", projectId).query(Boolean.class).single();
    }

    static boolean allowObservation(JdbcClient jdbc, String type, String key, UUID attachmentId) {
        if ("BLOB".equals(type)) {
            return jdbc.sql("SELECT presence_status<>'DELETED' AND operation_type IS NULL FROM yumpoo.attachment_blob WHERE storage_key=:key FOR UPDATE")
                    .param("key", key).query(Boolean.class).optional().orElse(true);
        }
        if ("QUOTA".equals(type)) {
            String[] parts = key.split(":", -1);
            if (parts.length != 3 || !"PROJECT".equals(parts[1])) return true;
            try {
                UUID company = UUID.fromString(parts[0]);
                UUID project = UUID.fromString(parts[2]);
                return writable(jdbc, company, project) && jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM yumpoo.attachment_quota_usage
                            WHERE company_id=:company AND scope_type='PROJECT' AND scope_id=:project)
                        """).param("company", company).param("project", project).query(Boolean.class).single();
            } catch (IllegalArgumentException invalidKey) { return true; }
        }
        if ("SCAN_TASK".equals(type)) {
            var scope = jdbc.sql("""
                    SELECT a.company_id,a.quota_project_id FROM yumpoo.attachment_scan_task t
                      JOIN yumpoo.attachment a ON a.id=t.attachment_id
                     WHERE t.id::text=:key FOR KEY SHARE OF a,t
                    """).param("key", key).query((rs, n) -> new Scope(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).optional();
            return scope.isPresent() && writable(jdbc, scope.orElseThrow().company(), scope.orElseThrow().project());
        }
        UUID id = attachmentId;
        if (id == null && ("ATTACHMENT".equals(type) || "QUARANTINE".equals(type))) {
            try { id = UUID.fromString("QUARANTINE".equals(type) ? key.replaceFirst("\\.(part|sealed)$", "") : key); }
            catch (IllegalArgumentException invalidKey) { return true; }
        }
        if (id == null) return true;
        var scope = jdbc.sql("SELECT company_id,quota_project_id FROM yumpoo.attachment WHERE id=:id FOR KEY SHARE")
                .param("id", id).query((rs, n) -> new Scope(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).optional();
        return scope.isEmpty() ? "QUARANTINE".equals(type)
                : writable(jdbc, scope.orElseThrow().company(), scope.orElseThrow().project());
    }

    private record Scope(UUID company, UUID project) { }
}
