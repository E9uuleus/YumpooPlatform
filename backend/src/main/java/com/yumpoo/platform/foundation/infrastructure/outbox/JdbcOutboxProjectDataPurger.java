package com.yumpoo.platform.foundation.infrastructure.outbox;

import com.yumpoo.platform.foundation.application.purge.ProjectDataPurger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class JdbcOutboxProjectDataPurger implements ProjectDataPurger {
    private static final String SCOPE = """
            e.company_id=:company AND (
                (e.aggregate_type='Project' AND e.aggregate_id=:project)
                OR e.payload_json->>'projectId'=CAST(:project AS text)
                OR e.payload_json->>'sourceProjectId'=CAST(:project AS text)
                OR e.payload_json->>'targetProjectId'=CAST(:project AS text)
                OR e.payload_json->>'leftProjectId'=CAST(:project AS text)
                OR e.payload_json->>'rightProjectId'=CAST(:project AS text))
            """;
    private final JdbcClient jdbc;
    public JdbcOutboxProjectDataPurger(JdbcClient jdbc) { this.jdbc = jdbc; }
    public String stage() { return "OUTBOX"; }
    public int order() { return 40; }

    @Transactional
    public boolean purgeBatch(UUID companyId, UUID projectId, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("purge limit must be between 1 and 500");
        int receipts = jdbc.sql("DELETE FROM yumpoo.outbox_consumer_receipt WHERE ctid IN "
                + "(SELECT r.ctid FROM yumpoo.outbox_consumer_receipt r JOIN yumpoo.outbox_event e ON e.event_id=r.event_id "
                + "WHERE " + SCOPE + " AND e.status IN ('COMPLETED','DEAD') LIMIT :limit)")
                .param("company", companyId).param("project", projectId).param("limit", limit).update();
        if (receipts < limit) {
            var events = jdbc.sql("SELECT e.event_id FROM yumpoo.outbox_event e WHERE " + SCOPE
                    + " AND e.status IN ('COMPLETED','DEAD') ORDER BY e.event_id LIMIT :limit FOR UPDATE SKIP LOCKED")
                    .param("company", companyId).param("project", projectId).param("limit", limit - receipts).query(UUID.class).list();
            if (!events.isEmpty()) jdbc.sql("""
                    DELETE FROM yumpoo.outbox_event e WHERE e.company_id=:company AND e.event_id IN (:events)
                      AND e.status IN ('COMPLETED','DEAD') AND NOT EXISTS (
                        SELECT 1 FROM yumpoo.outbox_consumer_receipt r WHERE r.event_id=e.event_id)
                    """).param("company", companyId).param("events", events).update();
        }
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM yumpoo.outbox_event e WHERE " + SCOPE + ")")
                .param("company", companyId).param("project", projectId).query(Boolean.class).single();
    }
}
