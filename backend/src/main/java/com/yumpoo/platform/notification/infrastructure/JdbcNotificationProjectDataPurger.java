package com.yumpoo.platform.notification.infrastructure;

import com.yumpoo.platform.foundation.application.purge.ProjectDataPurger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Component
public class JdbcNotificationProjectDataPurger implements ProjectDataPurger {
    private final JdbcClient jdbc;
    public JdbcNotificationProjectDataPurger(JdbcClient jdbc) { this.jdbc=jdbc; }
    public String stage() { return "NOTIFICATION"; }
    public int order() { return 10; }

    @Transactional
    public boolean purgeBatch(UUID companyId, UUID projectId, int limit) {
        if (limit<1 || limit>500) throw new IllegalArgumentException("purge limit must be between 1 and 500");
        int remaining=limit-jdbc.sql("""
                DELETE FROM yumpoo.user_notification WHERE ctid IN (
                    SELECT n.ctid FROM yumpoo.user_notification n
                    JOIN yumpoo.notification_event e ON e.company_id=n.company_id AND e.id=n.notification_event_id
                    WHERE e.company_id=:company AND e.project_id=:project LIMIT :limit)
                """).param("company",companyId).param("project",projectId).param("limit",limit).update();
        if (remaining>0) {
            // Separate locking and deletion snapshots prevent a concurrent FK insert from causing an unbounded cascade.
            var events=jdbc.sql("""
                    SELECT id FROM yumpoo.notification_event WHERE company_id=:company AND project_id=:project
                    ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED
                    """).param("company",companyId).param("project",projectId).param("limit",remaining).query(UUID.class).list();
            if (!events.isEmpty()) remaining-=jdbc.sql("""
                    DELETE FROM yumpoo.notification_event e WHERE e.company_id=:company AND e.id IN (:events)
                      AND NOT EXISTS (SELECT 1 FROM yumpoo.user_notification n
                          WHERE n.company_id=e.company_id AND n.notification_event_id=e.id)
                    """).param("company",companyId).param("events",events).update();
        }
        if (remaining>0) jdbc.sql("""
                DELETE FROM yumpoo.project_notification_preference WHERE ctid IN (
                    SELECT ctid FROM yumpoo.project_notification_preference
                    WHERE company_id=:company AND project_id=:project LIMIT :limit)
                """).param("company",companyId).param("project",projectId).param("limit",remaining).update();
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM yumpoo.notification_event WHERE company_id=:company AND project_id=:project)
                    OR EXISTS (SELECT 1 FROM yumpoo.project_notification_preference WHERE company_id=:company AND project_id=:project)
                """).param("company",companyId).param("project",projectId).query(Boolean.class).single();
    }
}
