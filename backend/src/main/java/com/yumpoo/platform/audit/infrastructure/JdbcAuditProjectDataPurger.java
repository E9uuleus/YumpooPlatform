package com.yumpoo.platform.audit.infrastructure;

import com.yumpoo.platform.foundation.application.purge.ProjectDataPurger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class JdbcAuditProjectDataPurger implements ProjectDataPurger {
    private final JdbcClient jdbc;
    public JdbcAuditProjectDataPurger(JdbcClient jdbc) { this.jdbc = jdbc; }
    public String stage() { return "AUDIT"; }
    public int order() { return 20; }

    @Transactional
    public boolean purgeBatch(UUID companyId, UUID projectId, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("purge limit must be between 1 and 500");
        int events = jdbc.sql("""
                DELETE FROM yumpoo.activity_event WHERE ctid IN (
                    SELECT ctid FROM yumpoo.activity_event WHERE company_id=:company
                      AND ((scope_type='PROJECT' AND scope_id=:project)
                        OR primary_work_item_id IN (SELECT id FROM yumpoo.work_item WHERE company_id=:company AND project_id=:project)
                        OR secondary_work_item_id IN (SELECT id FROM yumpoo.work_item WHERE company_id=:company AND project_id=:project))
                    LIMIT :limit)
                """).param("company", companyId).param("project", projectId).param("limit", limit).update();
        if (events == limit) return true;
        int cells = jdbc.sql("""
                DELETE FROM yumpoo.work_item_cell_activity WHERE ctid IN (
                    SELECT ctid FROM yumpoo.work_item_cell_activity WHERE company_id=:company AND project_id=:project LIMIT :limit)
                """).param("company", companyId).param("project", projectId).param("limit", limit - events).update();
        return events + cells == limit;
    }
}
