package com.yumpoo.platform.audit.infrastructure;

import com.yumpoo.platform.audit.application.WorkItemCellActivityRepository;
import com.yumpoo.platform.audit.application.WorkItemCellActivityStoredEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcWorkItemCellActivityRepository implements WorkItemCellActivityRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcWorkItemCellActivityRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Instant acceptedFrom() {
        return jdbcClient.sql("""
                SELECT accepted_from FROM yumpoo.activity_projection_state
                WHERE projection_code = 'WORK_ITEM_CELL_ACTIVITY_V1'
                """).query(OffsetDateTime.class).single().toInstant();
    }

    @Override
    public void append(WorkItemCellActivityStoredEvent event) {
        jdbcClient.sql("""
                INSERT INTO yumpoo.work_item_cell_activity (
                    id, event_id, company_id, project_id, work_item_id, content_id,
                    content_display_name, event_type, column_code, change_type,
                    before_value, after_value, actor_type, actor_user_id,
                    actor_system_code, actor_display_name, occurred_at, request_id,
                    correlation_id
                ) VALUES (
                    :id, :eventId, :companyId, :projectId, :workItemId, :contentId,
                    :contentDisplayName, :eventType, :columnCode, :changeType,
                    CAST(:beforeValue AS jsonb), CAST(:afterValue AS jsonb), :actorType,
                    :actorUserId, :actorSystemCode, :actorDisplayName, :occurredAt,
                    :requestId, :correlationId
                ) ON CONFLICT (event_id, projection_code, column_code) DO NOTHING
                """)
                .param("id", event.id()).param("eventId", event.eventId())
                .param("companyId", event.companyId()).param("projectId", event.projectId())
                .param("workItemId", event.workItemId()).param("contentId", event.contentId())
                .param("contentDisplayName", event.contentDisplayName())
                .param("eventType", event.eventType()).param("columnCode", event.columnCode())
                .param("changeType", event.changeType())
                .param("beforeValue", json(event.beforeValue())).param("afterValue", json(event.afterValue()))
                .param("actorType", event.actorType()).param("actorUserId", event.actorUserId())
                .param("actorSystemCode", event.actorSystemCode())
                .param("actorDisplayName", event.actorDisplayName())
                .param("occurredAt", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .param("requestId", event.requestId()).param("correlationId", event.correlationId())
                .update();
    }

    @Override
    public List<WorkItemCellActivityStoredEvent> find(UUID companyId, UUID workItemId,
            Filters filters, CursorAnchor anchor, int limit) {
        String sql = "SELECT * FROM yumpoo.work_item_cell_activity WHERE company_id=:companyId "
                + "AND work_item_id=:workItemId" + filters(filters, anchor)
                + " ORDER BY occurred_at DESC, id DESC LIMIT :limit";
        return bind(jdbcClient.sql(sql), companyId, workItemId, filters, anchor)
                .param("limit", limit).query(this::map).list();
    }

    @Override
    public List<WorkItemCellActivityStoredEvent> findForFacets(UUID companyId, UUID workItemId,
            Filters filters) {
        String sql = "SELECT * FROM yumpoo.work_item_cell_activity WHERE company_id=:companyId "
                + "AND work_item_id=:workItemId" + filters(filters, null);
        return bind(jdbcClient.sql(sql), companyId, workItemId, filters, null)
                .query(this::map).list();
    }

    private static String filters(Filters filters, CursorAnchor anchor) {
        StringBuilder sql = new StringBuilder();
        if (filters.occurredFrom() != null) sql.append(" AND occurred_at >= :occurredFrom");
        if (filters.occurredTo() != null) sql.append(" AND occurred_at < :occurredTo");
        sql.append(" AND occurred_at < :snapshotAt");
        if (!filters.actorUserIds().isEmpty()) sql.append(" AND actor_user_id IN (:actorUserIds)");
        if (!filters.columns().isEmpty()) sql.append(" AND column_code IN (:columns)");
        if (anchor != null) sql.append(" AND (occurred_at, id) < (:anchorAt, :anchorId)");
        return sql.toString();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement,
            UUID companyId, UUID workItemId, Filters filters, CursorAnchor anchor) {
        statement = statement.param("companyId", companyId).param("workItemId", workItemId)
                .param("snapshotAt", utc(filters.snapshotAt()));
        if (filters.occurredFrom() != null)
            statement = statement.param("occurredFrom", utc(filters.occurredFrom()));
        if (filters.occurredTo() != null)
            statement = statement.param("occurredTo", utc(filters.occurredTo()));
        if (!filters.actorUserIds().isEmpty())
            statement = statement.param("actorUserIds", filters.actorUserIds());
        if (!filters.columns().isEmpty()) statement = statement.param("columns", filters.columns());
        if (anchor != null) statement = statement.param("anchorAt", utc(anchor.occurredAt()))
                .param("anchorId", anchor.id());
        return statement;
    }

    private WorkItemCellActivityStoredEvent map(ResultSet rs, int row) throws SQLException {
        return new WorkItemCellActivityStoredEvent(rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("work_item_id", UUID.class),
                rs.getObject("content_id", UUID.class), rs.getString("content_display_name"),
                rs.getString("event_type"), rs.getString("column_code"),
                rs.getString("change_type"), tree(rs.getString("before_value")),
                tree(rs.getString("after_value")), rs.getString("actor_type"),
                rs.getObject("actor_user_id", UUID.class), rs.getString("actor_system_code"),
                rs.getString("actor_display_name"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getString("request_id"), rs.getString("correlation_id"));
    }

    private String json(JsonNode value) {
        if (value == null) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalArgumentException(failure); }
    }

    private JsonNode tree(String value) {
        if (value == null) return null;
        try { return objectMapper.readTree(value); }
        catch (JacksonException failure) { throw new IllegalStateException(failure); }
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
