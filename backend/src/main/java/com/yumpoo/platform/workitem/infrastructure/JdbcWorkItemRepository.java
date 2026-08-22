package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.workitem.application.WorkItemModels.WorkItemLocator;
import com.yumpoo.platform.workitem.application.WorkItemRepository;
import com.yumpoo.platform.workitem.domain.ContentWorkItemType;
import com.yumpoo.platform.workitem.domain.WorkItem;
import com.yumpoo.platform.workitem.domain.WorkItemPriority;
import com.yumpoo.platform.workitem.domain.WorkItemStatusCategory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcWorkItemRepository implements WorkItemRepository {
    private static final String COLUMNS = """
            id, company_id, project_id, content_id, item_sequence, item_no, type, title,
            status_code, status_category, priority, assignee_user_id, reporter_user_id,
            description, notes, timeline_start_date, timeline_end_date, due_date, rank,
            row_version, created_at, created_by_user_id, updated_at, updated_by_user_id,
            deleted_at, deleted_by_user_id, delete_reason
            """;

    private final JdbcClient jdbc;

    public JdbcWorkItemRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long nextSequence(UUID companyId, UUID projectId) {
        return jdbc.sql("""
                INSERT INTO yumpoo.work_item_project_counter (project_id, company_id, last_sequence)
                VALUES (:projectId, :companyId, 1)
                ON CONFLICT (project_id) DO UPDATE
                   SET last_sequence = yumpoo.work_item_project_counter.last_sequence + 1
                 WHERE yumpoo.work_item_project_counter.company_id = EXCLUDED.company_id
                RETURNING last_sequence
                """).param("projectId", projectId).param("companyId", companyId)
                .query(Long.class).single();
    }

    @Override
    public boolean insert(WorkItem item) {
        JdbcClient.StatementSpec statement = jdbc.sql("""
                INSERT INTO yumpoo.work_item (
                    id, company_id, project_id, content_id, item_sequence, item_no, type,
                    title, status_code, status_category, priority, assignee_user_id,
                    reporter_user_id, description, notes, timeline_start_date,
                    timeline_end_date, due_date, rank, row_version, created_at,
                    created_by_user_id, updated_at, updated_by_user_id, deleted_at,
                    deleted_by_user_id, delete_reason
                ) VALUES (
                    :id, :companyId, :projectId, :contentId, :itemSequence, :itemNo, :type,
                    :title, :statusCode, :statusCategory, :priority, :assigneeUserId,
                    :reporterUserId, :description, :notes, :timelineStartDate,
                    :timelineEndDate, :dueDate, :rank, :rowVersion, :createdAt,
                    :createdByUserId, :updatedAt, :updatedByUserId, :deletedAt,
                    :deletedByUserId, :deleteReason
                )
                """).param("id", item.id()).param("companyId", item.companyId())
                .param("projectId", item.projectId()).param("contentId", item.contentId())
                .param("itemSequence", item.itemSequence()).param("itemNo", item.itemNo())
                .param("type", item.type().name()).param("title", item.title())
                .param("statusCode", item.statusCode())
                .param("statusCategory", item.statusCategory().name())
                .param("priority", item.priority().name())
                .param("reporterUserId", item.reporterUserId())
                .param("rowVersion", item.rowVersion())
                .param("createdAt", OffsetDateTime.ofInstant(item.createdAt(), ZoneOffset.UTC))
                .param("createdByUserId", item.createdByUserId())
                .param("updatedAt", OffsetDateTime.ofInstant(item.updatedAt(), ZoneOffset.UTC))
                .param("updatedByUserId", item.updatedByUserId());
        statement = nullable(statement, "assigneeUserId", item.assigneeUserId(), Types.OTHER);
        statement = nullable(statement, "description", item.description(), Types.VARCHAR);
        statement = nullable(statement, "notes", item.notes(), Types.VARCHAR);
        statement = nullable(statement, "timelineStartDate", item.timelineStartDate(), Types.DATE);
        statement = nullable(statement, "timelineEndDate", item.timelineEndDate(), Types.DATE);
        statement = nullable(statement, "dueDate", item.dueDate(), Types.DATE);
        statement = nullable(statement, "rank", item.rank(), Types.VARCHAR);
        statement = nullable(statement, "deletedAt", item.deletedAt() == null ? null
                : OffsetDateTime.ofInstant(item.deletedAt(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        statement = nullable(statement, "deletedByUserId", item.deletedByUserId(), Types.OTHER);
        statement = nullable(statement, "deleteReason", item.deleteReason(), Types.VARCHAR);
        return statement.update() == 1;
    }

    @Override
    public Optional<WorkItemLocator> findLocator(UUID companyId, UUID workItemId) {
        return jdbc.sql("SELECT id, project_id, content_id FROM yumpoo.work_item "
                        + "WHERE company_id=:companyId AND id=:workItemId AND deleted_at IS NULL")
                .param("companyId", companyId).param("workItemId", workItemId)
                .query((rs, row) -> new WorkItemLocator(rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("content_id", UUID.class))).optional();
    }

    @Override
    public Optional<WorkItem> find(UUID companyId, UUID projectId, UUID contentId, UUID workItemId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM yumpoo.work_item WHERE company_id=:companyId "
                        + "AND project_id=:projectId AND content_id=:contentId AND id=:workItemId "
                        + "AND deleted_at IS NULL")
                .param("companyId", companyId).param("projectId", projectId)
                .param("contentId", contentId).param("workItemId", workItemId)
                .query(JdbcWorkItemRepository::map).optional();
    }

    @Override
    public List<WorkItem> findPage(UUID companyId, UUID projectId, UUID contentId,
            Set<String> statuses, OffsetPageRequest page) {
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT " + COLUMNS
                        + " FROM yumpoo.work_item WHERE company_id=:companyId AND project_id=:projectId "
                        + "AND content_id=:contentId AND deleted_at IS NULL"
                        + (statuses.isEmpty() ? "" : " AND status_code IN (:statuses)")
                        + " ORDER BY item_sequence DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("companyId", companyId).param("projectId", projectId)
                .param("contentId", contentId).param("limit", page.size())
                .param("offset", Math.multiplyExact(page.page(), page.size()));
        if (!statuses.isEmpty()) statement = statement.param("statuses", statuses);
        return statement.query(JdbcWorkItemRepository::map).list();
    }

    @Override
    public long countPage(UUID companyId, UUID projectId, UUID contentId, Set<String> statuses) {
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT count(*) FROM yumpoo.work_item "
                        + "WHERE company_id=:companyId AND project_id=:projectId "
                        + "AND content_id=:contentId AND deleted_at IS NULL"
                        + (statuses.isEmpty() ? "" : " AND status_code IN (:statuses)"))
                .param("companyId", companyId).param("projectId", projectId)
                .param("contentId", contentId);
        if (!statuses.isEmpty()) statement = statement.param("statuses", statuses);
        return statement.query(Long.class).single();
    }

    @Override
    public long countOpenByProject(UUID companyId, UUID projectId) {
        return jdbc.sql("SELECT count(*) FROM yumpoo.work_item WHERE company_id=:companyId "
                        + "AND project_id=:projectId AND deleted_at IS NULL "
                        + "AND status_category IN ('TODO','IN_PROGRESS')")
                .param("companyId", companyId).param("projectId", projectId)
                .query(Long.class).single();
    }

    @Override
    public long countOpenByContent(UUID companyId, UUID projectId, UUID contentId) {
        return jdbc.sql("SELECT count(*) FROM yumpoo.work_item WHERE company_id=:companyId "
                        + "AND project_id=:projectId AND content_id=:contentId AND deleted_at IS NULL "
                        + "AND status_category IN ('TODO','IN_PROGRESS')")
                .param("companyId", companyId).param("projectId", projectId)
                .param("contentId", contentId).query(Long.class).single();
    }

    private static WorkItem map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime deleted = rs.getObject("deleted_at", OffsetDateTime.class);
        return new WorkItem(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("content_id", UUID.class),
                rs.getLong("item_sequence"), rs.getString("item_no"),
                ContentWorkItemType.valueOf(rs.getString("type")), rs.getString("title"),
                rs.getString("status_code"), WorkItemStatusCategory.valueOf(rs.getString("status_category")),
                WorkItemPriority.valueOf(rs.getString("priority")),
                rs.getObject("assignee_user_id", UUID.class), rs.getObject("reporter_user_id", UUID.class),
                rs.getString("description"), rs.getString("notes"), rs.getObject("timeline_start_date", java.time.LocalDate.class),
                rs.getObject("timeline_end_date", java.time.LocalDate.class), rs.getObject("due_date", java.time.LocalDate.class),
                rs.getString("rank"), rs.getLong("row_version"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("created_by_user_id", UUID.class), rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_by_user_id", UUID.class), deleted == null ? null : deleted.toInstant(),
                rs.getObject("deleted_by_user_id", UUID.class), rs.getString("delete_reason"));
    }

    private static JdbcClient.StatementSpec nullable(JdbcClient.StatementSpec statement,
            String name, Object value, int sqlType) {
        return value == null ? statement.param(name, null, sqlType) : statement.param(name, value);
    }
}
