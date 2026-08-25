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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemUpdateModels.UpdateLocator;

@Repository
public class JdbcWorkItemUpdateRepository implements WorkItemUpdateRepository {
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
        insertMentions(update, mentionedDisplayNames, update.createdAt());
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

    @Override
    public Optional<UpdateLocator> findLocator(UUID companyId, UUID updateId) {
        return jdbc.sql("""
                SELECT company_id, project_id, content_id, work_item_id, id
                FROM yumpoo.work_item_update
                WHERE company_id=:companyId AND id=:updateId
                """).param("companyId", companyId).param("updateId", updateId)
                .query((rs, row) -> new UpdateLocator(rs.getObject("company_id", UUID.class),
                        rs.getObject("project_id", UUID.class), rs.getObject("content_id", UUID.class),
                        rs.getObject("work_item_id", UUID.class), rs.getObject("id", UUID.class)))
                .optional();
    }

    @Override
    public Optional<WorkItemUpdate> find(UUID companyId, UUID updateId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM yumpoo.work_item_update "
                        + "WHERE company_id=:companyId AND id=:updateId")
                .param("companyId", companyId).param("updateId", updateId)
                .query(JdbcWorkItemUpdateRepository::map).optional();
    }

    @Override
    public Optional<WorkItemUpdate> lock(UUID companyId, UUID updateId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM yumpoo.work_item_update "
                        + "WHERE company_id=:companyId AND id=:updateId FOR UPDATE")
                .param("companyId", companyId).param("updateId", updateId)
                .query(JdbcWorkItemUpdateRepository::map).optional();
    }

    @Override
    public Map<UUID, String> findMentionedDisplayNames(UUID companyId, UUID updateId) {
        LinkedHashMap<UUID, String> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT mentioned_user_id, mentioned_display_name
                FROM yumpoo.work_item_update_mention
                WHERE company_id=:companyId AND update_id=:updateId
                ORDER BY mentioned_user_id
                """).param("companyId", companyId).param("updateId", updateId)
                .query((rs, row) -> Map.entry(rs.getObject("mentioned_user_id", UUID.class),
                        rs.getString("mentioned_display_name"))).list()
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    @Override
    public boolean update(WorkItemUpdate update, Map<UUID, String> mentionedDisplayNames,
            long expectedVersion) {
        int changed = jdbc.sql("""
                UPDATE yumpoo.work_item_update
                SET body_html=:bodyHtml, body_text=:bodyText, status=:status,
                    edited_at=:editedAt, edited_by_user_id=:editedByUserId,
                    row_version=:rowVersion
                WHERE company_id=:companyId AND id=:updateId
                  AND row_version=:expectedVersion AND status<>'DELETED'
                """).param("bodyHtml", update.bodyHtml()).param("bodyText", update.bodyText())
                .param("status", update.status().name()).param("editedAt", utc(update.editedAt()))
                .param("editedByUserId", update.editedByUserId()).param("rowVersion", update.rowVersion())
                .param("companyId", update.companyId()).param("updateId", update.id())
                .param("expectedVersion", expectedVersion).update();
        if (changed != 1) return false;
        jdbc.sql("DELETE FROM yumpoo.work_item_update_mention "
                        + "WHERE company_id=:companyId AND update_id=:updateId")
                .param("companyId", update.companyId()).param("updateId", update.id()).update();
        insertMentions(update, mentionedDisplayNames, update.editedAt());
        return true;
    }

    @Override
    public boolean delete(WorkItemUpdate update, long expectedVersion) {
        return jdbc.sql("""
                UPDATE yumpoo.work_item_update
                SET body_html=NULL, body_text=NULL, status=:status,
                    deleted_at=:deletedAt, deleted_by_user_id=:deletedByUserId,
                    delete_reason=:deleteReason, row_version=:rowVersion
                WHERE company_id=:companyId AND id=:updateId
                  AND row_version=:expectedVersion AND status<>'DELETED'
                """).param("status", update.status().name()).param("deletedAt", utc(update.deletedAt()))
                .param("deletedByUserId", update.deletedByUserId()).param("deleteReason", update.deleteReason())
                .param("rowVersion", update.rowVersion()).param("companyId", update.companyId())
                .param("updateId", update.id()).param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    private void insertMentions(WorkItemUpdate update, Map<UUID, String> mentionedDisplayNames,
            Instant createdAt) {
        for (Map.Entry<UUID, String> mention : mentionedDisplayNames.entrySet()) {
            jdbc.sql("""
                    INSERT INTO yumpoo.work_item_update_mention (
                        update_id, company_id, mentioned_user_id, mentioned_display_name, created_at
                    ) VALUES (:updateId, :companyId, :userId, :displayName, :createdAt)
                    """).param("updateId", update.id()).param("companyId", update.companyId())
                    .param("userId", mention.getKey()).param("displayName", mention.getValue())
                    .param("createdAt", utc(createdAt)).update();
        }
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
