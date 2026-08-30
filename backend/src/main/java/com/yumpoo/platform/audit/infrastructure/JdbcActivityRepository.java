package com.yumpoo.platform.audit.infrastructure;

import com.yumpoo.platform.audit.application.ActivityRepository;
import com.yumpoo.platform.audit.application.ActivityStoredEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcActivityRepository implements ActivityRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcActivityRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Instant acceptedFrom() {
        return jdbcClient.sql("""
                        SELECT accepted_from FROM yumpoo.activity_projection_state
                        WHERE projection_code = 'ACTIVITY_V1'
                        """).query(OffsetDateTime.class).single().toInstant();
    }

    @Override
    public void append(ActivityStoredEvent event) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.activity_event (
                            id, event_id, projection_code, company_id, scope_type, scope_id,
                            entity_type, entity_id, entity_ref, event_type,
                            actor_type, actor_user_id, actor_system_code, actor_display_name,
                            occurred_at, template_code, safe_parameters, entity_version,
                            request_id, correlation_id, primary_work_item_id,
                            secondary_work_item_id
                        ) VALUES (
                            :id, :eventId, 'ACTIVITY_V1', :companyId, :audience, :scopeId,
                            :entityType, :entityId, :entityRef, :eventType,
                            :actorType, :actorUserId, :actorSystemCode, :actorDisplayName,
                            :occurredAt, :templateCode, CAST(:safeParameters AS jsonb),
                            :entityVersion, :requestId, :correlationId, :primaryWorkItemId,
                            :secondaryWorkItemId
                        ) ON CONFLICT (event_id, projection_code, scope_type, scope_id) DO NOTHING
                        """)
                .param("id", event.id()).param("eventId", event.eventId())
                .param("audience", event.audienceType())
                .param("companyId", event.companyId()).param("scopeId", event.scopeId())
                .param("entityType", event.entityType()).param("entityId", event.entityId())
                .param("entityRef", event.entityRef()).param("eventType", event.eventType())
                .param("actorType", event.actorType()).param("actorUserId", event.actorUserId())
                .param("actorSystemCode", event.actorSystemCode())
                .param("actorDisplayName", event.actorDisplayName())
                .param("occurredAt", utc(event.occurredAt()))
                .param("templateCode", event.templateCode())
                .param("safeParameters", json(event.safeParameters()))
                .param("entityVersion", event.entityVersion()).param("requestId", event.requestId())
                .param("correlationId", event.correlationId())
                .param("primaryWorkItemId", event.primaryWorkItemId())
                .param("secondaryWorkItemId", event.secondaryWorkItemId()).update();
    }

    @Override
    public List<ActivityStoredEvent> findScope(UUID companyId, String audience,
            UUID scopeId, Set<String> eventTypes, Set<String> entityTypes,
            Instant occurredFrom, Instant occurredTo, CursorAnchor anchor, int limit) {
        String sql = "SELECT * FROM yumpoo.activity_event WHERE company_id = :companyId "
                + "AND scope_type = :audience AND scope_id = :scopeId"
                + filters(eventTypes, entityTypes, occurredFrom, occurredTo, anchor)
                + " ORDER BY occurred_at DESC, id DESC LIMIT :limit";
        JdbcClient.StatementSpec statement = base(jdbcClient.sql(sql), companyId, eventTypes,
                entityTypes, occurredFrom, occurredTo, anchor).param("audience", audience)
                .param("scopeId", scopeId).param("limit", limit);
        return statement.query(this::map).list();
    }

    @Override
    public List<ActivityStoredEvent> findWorkItem(UUID companyId, UUID projectId, UUID workItemId,
            Set<String> eventTypes, Set<String> entityTypes, Instant occurredFrom,
            Instant occurredTo, CursorAnchor anchor, int limit) {
        String sql = "SELECT * FROM yumpoo.activity_event WHERE company_id = :companyId "
                + "AND scope_type = 'PROJECT' AND scope_id = :projectId "
                + "AND (primary_work_item_id = :workItemId OR secondary_work_item_id = :workItemId)"
                + filters(eventTypes, entityTypes, occurredFrom, occurredTo, anchor)
                + " ORDER BY occurred_at DESC, id DESC LIMIT :limit";
        JdbcClient.StatementSpec statement = base(jdbcClient.sql(sql), companyId, eventTypes,
                entityTypes, occurredFrom, occurredTo, anchor).param("projectId", projectId)
                .param("workItemId", workItemId).param("limit", limit);
        return statement.query(this::map).list();
    }

    private static String filters(Set<String> eventTypes, Set<String> entityTypes,
            Instant from, Instant to, CursorAnchor anchor) {
        StringBuilder sql = new StringBuilder();
        if (!eventTypes.isEmpty()) sql.append(" AND event_type IN (:eventTypes)");
        if (!entityTypes.isEmpty()) sql.append(" AND entity_type IN (:entityTypes)");
        if (from != null) sql.append(" AND occurred_at >= :occurredFrom");
        if (to != null) sql.append(" AND occurred_at <= :occurredTo");
        if (anchor != null) sql.append(" AND (occurred_at, id) < (:anchorAt, :anchorId)");
        return sql.toString();
    }

    private static JdbcClient.StatementSpec base(JdbcClient.StatementSpec statement,
            UUID companyId, Set<String> eventTypes, Set<String> entityTypes,
            Instant from, Instant to, CursorAnchor anchor) {
        statement = statement.param("companyId", companyId);
        if (!eventTypes.isEmpty()) statement = statement.param("eventTypes", eventTypes);
        if (!entityTypes.isEmpty()) statement = statement.param("entityTypes", entityTypes);
        if (from != null) statement = statement.param("occurredFrom", utc(from));
        if (to != null) statement = statement.param("occurredTo", utc(to));
        if (anchor != null) statement = statement.param("anchorAt", utc(anchor.occurredAt()))
                .param("anchorId", anchor.id());
        return statement;
    }

    private ActivityStoredEvent map(ResultSet rs, int row) throws SQLException {
        return new ActivityStoredEvent(rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("scope_type"),
                rs.getObject("company_id", UUID.class), rs.getObject("scope_id", UUID.class),
                rs.getString("entity_type"), rs.getObject("entity_id", UUID.class),
                rs.getString("entity_ref"), rs.getString("event_type"),
                rs.getString("actor_type"), rs.getObject("actor_user_id", UUID.class),
                rs.getString("actor_system_code"), rs.getString("actor_display_name"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getString("template_code"), tree(rs.getString("safe_parameters")),
                rs.getLong("entity_version"), rs.getString("request_id"),
                rs.getString("correlation_id"),
                rs.getObject("primary_work_item_id", UUID.class),
                rs.getObject("secondary_work_item_id", UUID.class));
    }

    private String json(JsonNode value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalArgumentException(failure); }
    }

    private JsonNode tree(String value) {
        try { return objectMapper.readTree(value); }
        catch (JacksonException failure) { throw new IllegalStateException(failure); }
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
