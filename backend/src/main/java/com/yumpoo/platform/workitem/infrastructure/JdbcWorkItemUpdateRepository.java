package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.workitem.application.WorkItemUpdateModels.UpdateCursor;
import com.yumpoo.platform.workitem.application.WorkItemUpdateRepository;
import com.yumpoo.platform.workitem.domain.WorkItemUpdate;
import com.yumpoo.platform.workitem.domain.WorkItemUpdateStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public final class JdbcWorkItemUpdateRepository implements WorkItemUpdateRepository {
    private static final String COLUMNS = """
            id, company_id, project_id, content_id, work_item_id, author_user_id,
            author_display_name, body_html, body_text, status, edit_deadline_at,
            row_version, created_at, edited_at, edited_by_user_id, deleted_at,
            deleted_by_user_id, delete_reason
            """;

    private final JdbcClient jdbc;

    public JdbcWorkItemUpdateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(WorkItemUpdate update, Map<UUID, String> mentionedDisplayNames) {
        int inserted = jdbc.sql("""
                INSERT INTO yumpoo.work_item_update (
                    id, company_id, project_id, content_id, work_item_id, author_user_id,
                    author_display_name, body_html, body_text, status, edit_deadline_at,
                    row_version, created_at, edited_at, edited_by_user_id, deleted_at,
                    deleted_by_user_id, delete_reason
                ) VALUES (
                    :id, :companyId, :projectId, :contentId, :workItemId, :authorUserId,
                    :authorDisplayName, :bodyHtml, :bodyText, :status, :editDeadlineAt,
                    :rowVersion, :createdAt, NULL, NULL, NULL, NULL, NULL
                )
                """).param("id", update.id()).param("companyId", update.companyId())
                .param("projectId", update.projectId()).param("contentId", update.contentId())
                .param("workItemId", update.workItemId()).param("authorUserId", update.authorUserId())
                .param("authorDisplayName", update.authorDisplayName()).param("bodyHtml", update.bodyHtml())
                .param("bodyText", update.bodyText()).param("status", update.status().name())
                .param("editDeadlineAt", utc(update.editDeadlineAt())).param("rowVersion", update.rowVersion())
                .param("createdAt", utc(update.createdAt())).update();
        if (inserted != 1) return false;
        for (Map.Entry<UUID, String> mention : mentionedDisplayNames.entrySet()) {
            jdbc.sql("""
                    INSERT INTO yumpoo.work_item_update_mention (
                        update_id, company_id, mentioned_user_id, mentioned_display_name, created_at
                    ) VALUES (:updateId, :companyId, :userId, :displayName, :createdAt)
                    """).param("updateId", update.id()).param("companyId", update.companyId())
                    .param("userId", mention.getKey()).param("displayName", mention.getValue())
                    .param("createdAt", utc(update.createdAt())).update();
        }
        return true;
    }

    @Override
    public List<WorkItemUpdate> findOlderWindow(UUID companyId, UUID workItemId,
            UpdateCursor before, int limit) {
        String boundary = before == null ? "" : " AND (created_at, id) < (:createdAt, :cursorId)";
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT " + COLUMNS
                        + " FROM yumpoo.work_item_update WHERE company_id=:companyId"
                        + " AND work_item_id=:workItemId" + boundary
                        + " ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("companyId", companyId).param("workItemId", workItemId).param("limit", limit);
        if (before != null) {
            statement = statement.param("createdAt", utc(before.createdAt()))
                    .param("cursorId", before.id());
        }
        return statement.query(JdbcWorkItemUpdateRepository::map).list();
    }

    private static WorkItemUpdate map(ResultSet rs, int row) throws SQLException {
        return new WorkItemUpdate(rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("content_id", UUID.class), rs.getObject("work_item_id", UUID.class),
                rs.getObject("author_user_id", UUID.class), rs.getString("author_display_name"),
                rs.getString("body_html"), rs.getString("body_text"),
                WorkItemUpdateStatus.valueOf(rs.getString("status")), instant(rs, "edit_deadline_at"),
                rs.getLong("row_version"), instant(rs, "created_at"), nullableInstant(rs, "edited_at"),
                rs.getObject("edited_by_user_id", UUID.class), nullableInstant(rs, "deleted_at"),
                rs.getObject("deleted_by_user_id", UUID.class), rs.getString("delete_reason"));
    }

    private static Instant instant(ResultSet rs, String field) throws SQLException {
        return rs.getObject(field, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String field) throws SQLException {
        OffsetDateTime value = rs.getObject(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
