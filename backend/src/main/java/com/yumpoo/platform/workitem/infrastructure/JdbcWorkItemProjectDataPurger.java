package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.foundation.application.purge.ProjectDataPurger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
public class JdbcWorkItemProjectDataPurger implements ProjectDataPurger {
    private record Table(String name, String predicate) {}
    private static final String SCOPE = "company_id=:company AND project_id=:project";
    private static final List<Table> TABLES = List.of(
            new Table("work_item_timer_state", "company_id=:company AND user_id IN "
                    + "(SELECT user_id FROM yumpoo.work_item_time_session WHERE company_id=:company AND project_id=:project) "
                    + "AND NOT EXISTS (SELECT 1 FROM yumpoo.work_item_time_session s WHERE s.company_id=:company "
                    + "AND s.user_id=work_item_timer_state.user_id AND s.project_id<>:project)"),
            new Table("work_item_time_session", SCOPE),
            new Table("work_item_time_revision", SCOPE),
            new Table("work_item_update_mention", "company_id=:company AND update_id IN "
                    + "(SELECT id FROM yumpoo.work_item_update WHERE company_id=:company AND project_id=:project)"),
            new Table("work_item_update", SCOPE + " AND parent_update_id IS NOT NULL"),
            new Table("work_item_update", SCOPE),
            new Table("work_item_assignee", SCOPE),
            new Table("work_item_relation", "company_id=:company AND (left_project_id=:project OR right_project_id=:project)"),
            new Table("work_item_connection", "company_id=:company AND (source_project_id=:project OR target_project_id=:project)"),
            new Table("work_item_connect_column_target", "company_id=:company AND column_id IN "
                    + "(SELECT id FROM yumpoo.work_item_connect_column WHERE company_id=:company AND project_id=:project)"),
            new Table("work_item_connect_column", SCOPE),
            new Table("work_item_connect_column_catalog", SCOPE),
            new Table("work_item_table_settings", SCOPE),
            new Table("work_item", SCOPE),
            new Table("project_work_item_status_label", "project_id=:project"),
            new Table("project_work_item_priority_label", "project_id=:project"),
            new Table("project_work_item_label_catalog", SCOPE),
            new Table("work_item_project_order", SCOPE),
            new Table("work_item_rank_lane", SCOPE),
            new Table("work_item_project_counter", SCOPE),
            new Table("content", SCOPE),
            new Table("content_catalog_version", SCOPE));
    private final JdbcClient jdbc;

    public JdbcWorkItemProjectDataPurger(JdbcClient jdbc) { this.jdbc = jdbc; }
    public String stage() { return "WORKITEM"; }
    public int order() { return 50; }

    @Transactional
    public boolean purgeBatch(UUID companyId, UUID projectId, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("purge limit must be between 1 and 500");
        int remaining = limit;
        for (Table table : TABLES) {
            if(table.name().equals("work_item_connect_column_target")) {
                remaining-=detachForeignTargets(companyId,projectId,remaining);
                if(remaining==0) return true;
            }
            int deleted = jdbc.sql("DELETE FROM yumpoo." + table.name() + " WHERE ctid IN "
                    + "(SELECT ctid FROM yumpoo." + table.name() + " WHERE " + table.predicate() + " LIMIT :limit)")
                    .param("company", companyId).param("project", projectId).param("limit", remaining).update();
            remaining -= deleted;
            if (remaining == 0) return true;
        }
        return false;
    }

    private int detachForeignTargets(UUID companyId,UUID projectId,int limit) {
        var columns=jdbc.sql("""
                SELECT c.id,c.project_id FROM yumpoo.work_item_connect_column c
                JOIN yumpoo.work_item_connect_column_target t ON t.column_id=c.id AND t.company_id=c.company_id
                WHERE c.company_id=:company AND c.project_id<>:project AND t.target_project_id=:project
                ORDER BY c.project_id,c.id LIMIT :limit
                """).param("company",companyId).param("project",projectId).param("limit",limit)
                .query((rs,n)->new UUID[]{rs.getObject("id",UUID.class),rs.getObject("project_id",UUID.class)}).list();
        int deleted=0;
        java.util.Set<UUID> changedProjects=new java.util.HashSet<>();
        for(UUID source:columns.stream().map(column->column[1]).distinct().sorted().toList())
            jdbc.sql("SELECT project_id FROM yumpoo.work_item_connect_column_catalog WHERE company_id=:company AND project_id=:source FOR UPDATE")
                    .param("company",companyId).param("source",source).query(UUID.class).optional();
        for(var column:columns) {
            jdbc.sql("SELECT id FROM yumpoo.work_item_connect_column WHERE company_id=:company AND id=:column FOR UPDATE")
                    .param("company",companyId).param("column",column[0]).query(UUID.class).optional();
            int changed=jdbc.sql("DELETE FROM yumpoo.work_item_connect_column_target WHERE company_id=:company AND column_id=:column AND target_project_id=:project")
                    .param("company",companyId).param("column",column[0]).param("project",projectId).update();
            if(changed==0) continue;
            deleted+=changed;
            jdbc.sql("UPDATE yumpoo.work_item_connect_column SET row_version=row_version+1,updated_at=clock_timestamp() WHERE company_id=:company AND id=:column")
                    .param("company",companyId).param("column",column[0]).update();
            changedProjects.add(column[1]);
        }
        for(UUID source:changedProjects) jdbc.sql("UPDATE yumpoo.work_item_connect_column_catalog SET row_version=row_version+1,updated_at=clock_timestamp() WHERE company_id=:company AND project_id=:source")
                .param("company",companyId).param("source",source).update();
        if(deleted==limit) return deleted;
        if(jdbc.sql("SELECT EXISTS(SELECT 1 FROM yumpoo.work_item_connect_column_target WHERE company_id=:company AND target_project_id=:project)")
                .param("company",companyId).param("project",projectId).query(Boolean.class).single()) {
            // The caller will resume before deleting work items while detached targets remain.
            return limit;
        }
        return deleted;
    }
}
