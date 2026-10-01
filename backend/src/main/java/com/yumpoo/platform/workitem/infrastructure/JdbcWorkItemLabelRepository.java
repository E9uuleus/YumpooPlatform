package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.workitem.application.WorkItemLabelModels.PriorityLabel;
import com.yumpoo.platform.workitem.application.WorkItemLabelModels.StatusLabel;
import com.yumpoo.platform.workitem.application.WorkItemLabelRepository;
import com.yumpoo.platform.workitem.application.DefaultProjectStructure;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

@Repository
public class JdbcWorkItemLabelRepository implements WorkItemLabelRepository {
    private final JdbcClient jdbc;

    public JdbcWorkItemLabelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void initialize(UUID companyId, UUID projectId, Instant now) {
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO yumpoo.project_work_item_label_catalog
                    (project_id, company_id, created_at, updated_at)
                VALUES (:projectId, :companyId, :now, :now)
                ON CONFLICT (project_id) DO NOTHING
                """).param("projectId", projectId).param("companyId", companyId)
                .param("now", timestamp).update();
        for (var label : DefaultProjectStructure.STATUSES) {
            jdbc.sql("""
                    INSERT INTO yumpoo.project_work_item_status_label (
                        project_id, company_id, status_code, display_name, color_token, status_category,
                        sort_order, active, protected_label, created_at, updated_at)
                    VALUES (:projectId, :companyId, :code, :name, :color, :category,
                        :sortOrder, true, :protected, :now, :now)
                    ON CONFLICT (project_id, status_code) DO NOTHING
                    """).param("projectId", projectId).param("companyId", companyId)
                    .param("code", label.code()).param("name", label.displayName())
                    .param("color", label.colorToken()).param("category", label.category())
                    .param("sortOrder", label.sortOrder()).param("protected", label.protectedLabel())
                    .param("now", timestamp).update();
        }
        for (var label : DefaultProjectStructure.PRIORITIES) {
            jdbc.sql("""
                    INSERT INTO yumpoo.project_work_item_priority_label (
                        project_id, company_id, priority_code, display_name, color_token,
                        sort_order, active, created_at, updated_at)
                    VALUES (:projectId, :companyId, :code, :name, :color, :sortOrder, true, :now, :now)
                    ON CONFLICT (project_id, priority_code) DO NOTHING
                    """).param("projectId", projectId).param("companyId", companyId)
                    .param("code", label.code()).param("name", label.displayName())
                    .param("color", label.colorToken()).param("sortOrder", label.sortOrder())
                    .param("now", timestamp).update();
        }
    }

    @Override
    public OptionalLong version(UUID companyId, UUID projectId, boolean lock) {
        String sql = "SELECT row_version FROM yumpoo.project_work_item_label_catalog "
                + "WHERE company_id=:companyId AND project_id=:projectId"
                + (lock ? " FOR UPDATE" : "");
        return jdbc.sql(sql).param("companyId", companyId).param("projectId", projectId)
                .query(Long.class).optional().map(OptionalLong::of).orElseGet(OptionalLong::empty);
    }

    @Override
    public List<StatusLabel> statuses(UUID companyId, UUID projectId) {
        return jdbc.sql("""
                SELECT label.status_code, label.display_name, label.color_token,
                       label.status_category, label.sort_order, label.active,
                       label.protected_label,
                       EXISTS (SELECT 1 FROM yumpoo.work_item item
                                WHERE item.project_id=label.project_id
                                  AND item.status_code=label.status_code) AS in_use
                  FROM yumpoo.project_work_item_status_label label
                 WHERE label.company_id=:companyId AND label.project_id=:projectId
                   AND label.deleted_at IS NULL
                 ORDER BY label.sort_order, label.status_code
                """).param("companyId", companyId).param("projectId", projectId)
                .query((rs, row) -> new StatusLabel(rs.getString("status_code"),
                        rs.getString("display_name"), rs.getString("color_token"),
                        rs.getString("status_category"), rs.getInt("sort_order"),
                        rs.getBoolean("active"), rs.getBoolean("protected_label"),
                        rs.getBoolean("in_use"))).list();
    }

    @Override
    public List<PriorityLabel> priorities(UUID companyId, UUID projectId) {
        return jdbc.sql("""
                SELECT label.priority_code, label.display_name, label.color_token,
                       label.sort_order, label.active,
                       EXISTS (SELECT 1 FROM yumpoo.work_item item
                                WHERE item.project_id=label.project_id
                                  AND item.priority=label.priority_code) AS in_use
                  FROM yumpoo.project_work_item_priority_label label
                 WHERE label.company_id=:companyId AND label.project_id=:projectId
                   AND label.deleted_at IS NULL
                 ORDER BY label.sort_order, label.priority_code
                """).param("companyId", companyId).param("projectId", projectId)
                .query((rs, row) -> new PriorityLabel(rs.getString("priority_code"),
                        rs.getString("display_name"), rs.getString("color_token"),
                        rs.getInt("sort_order"), rs.getBoolean("active"),
                        rs.getBoolean("in_use"))).list();
    }

    @Override
    public boolean insertStatus(UUID companyId, UUID projectId, String code, String displayName,
            String colorToken, int sortOrder, Instant now) {
        return jdbc.sql("""
                INSERT INTO yumpoo.project_work_item_status_label (
                    project_id, company_id, status_code, display_name, color_token,
                    status_category, sort_order, active, protected_label, created_at, updated_at
                ) VALUES (:projectId, :companyId, :code, :displayName, :colorToken,
                    'TODO', :sortOrder, true, false, :now, :now)
                """).param("projectId", projectId).param("companyId", companyId)
                .param("code", code).param("displayName", displayName)
                .param("colorToken", colorToken).param("sortOrder", sortOrder)
                .param("now", utc(now)).update() == 1;
    }

    @Override
    public boolean insertPriority(UUID companyId, UUID projectId, String code, String displayName,
            String colorToken, int sortOrder, Instant now) {
        return jdbc.sql("""
                INSERT INTO yumpoo.project_work_item_priority_label (
                    project_id, company_id, priority_code, display_name, color_token,
                    sort_order, active, created_at, updated_at
                ) VALUES (:projectId, :companyId, :code, :displayName, :colorToken,
                    :sortOrder, true, :now, :now)
                """).param("projectId", projectId).param("companyId", companyId)
                .param("code", code).param("displayName", displayName)
                .param("colorToken", colorToken).param("sortOrder", sortOrder)
                .param("now", utc(now)).update() == 1;
    }

    @Override
    public boolean updateStatus(UUID companyId, UUID projectId, String code, String displayName,
            String colorToken, boolean active, Instant now) {
        return jdbc.sql("""
                UPDATE yumpoo.project_work_item_status_label
                   SET display_name=:displayName, color_token=:colorToken, active=:active,
                       updated_at=:now
                 WHERE company_id=:companyId AND project_id=:projectId AND status_code=:code
                   AND deleted_at IS NULL AND (:active OR NOT protected_label)
                """).param("displayName", displayName).param("colorToken", colorToken)
                .param("active", active).param("now", utc(now)).param("companyId", companyId)
                .param("projectId", projectId).param("code", code).update() == 1;
    }

    @Override
    public boolean updatePriority(UUID companyId, UUID projectId, String code, String displayName,
            String colorToken, boolean active, Instant now) {
        return jdbc.sql("""
                UPDATE yumpoo.project_work_item_priority_label
                   SET display_name=:displayName, color_token=:colorToken, active=:active,
                       updated_at=:now
                 WHERE company_id=:companyId AND project_id=:projectId AND priority_code=:code
                   AND deleted_at IS NULL
                """).param("displayName", displayName).param("colorToken", colorToken)
                .param("active", active).param("now", utc(now)).param("companyId", companyId)
                .param("projectId", projectId).param("code", code).update() == 1;
    }

    @Override
    public void rewriteStatusOrders(UUID companyId, UUID projectId, Map<String, Integer> orders,
            Instant now) {
        jdbc.sql("""
                UPDATE yumpoo.project_work_item_status_label
                   SET sort_order=sort_order+1000000
                 WHERE company_id=:companyId AND project_id=:projectId AND deleted_at IS NULL
                """).param("companyId", companyId).param("projectId", projectId).update();
        orders.forEach((code, order) -> jdbc.sql("""
                UPDATE yumpoo.project_work_item_status_label
                   SET sort_order=:sortOrder, updated_at=:now
                 WHERE company_id=:companyId AND project_id=:projectId
                   AND status_code=:code AND deleted_at IS NULL
                """).param("sortOrder", order).param("now", utc(now))
                .param("companyId", companyId).param("projectId", projectId)
                .param("code", code).update());
    }

    @Override
    public void rewritePriorityOrders(UUID companyId, UUID projectId, Map<String, Integer> orders,
            Instant now) {
        jdbc.sql("""
                UPDATE yumpoo.project_work_item_priority_label
                   SET sort_order=sort_order+1000000
                 WHERE company_id=:companyId AND project_id=:projectId AND deleted_at IS NULL
                """).param("companyId", companyId).param("projectId", projectId).update();
        orders.forEach((code, order) -> jdbc.sql("""
                UPDATE yumpoo.project_work_item_priority_label
                   SET sort_order=:sortOrder, updated_at=:now
                 WHERE company_id=:companyId AND project_id=:projectId
                   AND priority_code=:code AND deleted_at IS NULL
                """).param("sortOrder", order).param("now", utc(now))
                .param("companyId", companyId).param("projectId", projectId)
                .param("code", code).update());
    }

    @Override
    public boolean deleteStatus(UUID companyId, UUID projectId, String code, Instant now) {
        return jdbc.sql("""
                UPDATE yumpoo.project_work_item_status_label label
                   SET active=false, deleted_at=:now, updated_at=:now
                 WHERE label.company_id=:companyId AND label.project_id=:projectId
                   AND label.status_code=:code AND label.deleted_at IS NULL
                   AND NOT label.protected_label
                   AND NOT EXISTS (SELECT 1 FROM yumpoo.work_item item
                                    WHERE item.project_id=label.project_id
                                      AND item.status_code=label.status_code)
                """).param("now", utc(now)).param("companyId", companyId)
                .param("projectId", projectId).param("code", code).update() == 1;
    }

    @Override
    public boolean deletePriority(UUID companyId, UUID projectId, String code, Instant now) {
        return jdbc.sql("""
                UPDATE yumpoo.project_work_item_priority_label label
                   SET active=false, deleted_at=:now, updated_at=:now
                 WHERE label.company_id=:companyId AND label.project_id=:projectId
                   AND label.priority_code=:code AND label.deleted_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM yumpoo.work_item item
                                    WHERE item.project_id=label.project_id
                                      AND item.priority=label.priority_code)
                """).param("now", utc(now)).param("companyId", companyId)
                .param("projectId", projectId).param("code", code).update() == 1;
    }

    @Override
    public void incrementVersion(UUID companyId, UUID projectId, long expectedVersion, Instant now) {
        int updated = jdbc.sql("""
                UPDATE yumpoo.project_work_item_label_catalog
                   SET row_version=row_version+1, updated_at=:now
                 WHERE company_id=:companyId AND project_id=:projectId
                   AND row_version=:expectedVersion
                """).param("now", utc(now)).param("companyId", companyId)
                .param("projectId", projectId).param("expectedVersion", expectedVersion).update();
        if (updated != 1) throw new IllegalStateException("label catalog version update failed");
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
