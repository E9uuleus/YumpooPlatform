package com.yumpoo.platform.administration.infrastructure.governance;

import com.yumpoo.platform.administration.application.GovernanceOverrideAction;
import com.yumpoo.platform.administration.application.GovernanceOverrideRecord;
import com.yumpoo.platform.administration.application.GovernanceOverrideRepository;
import com.yumpoo.platform.administration.application.GovernanceOverrideResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcGovernanceOverrideRepository implements GovernanceOverrideRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcGovernanceOverrideRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insert(GovernanceOverrideRecord record) {
        int inserted = jdbc.sql("""
                INSERT INTO yumpoo.admin_override (
                    id, company_id, action, target_type, target_id, reason, request_hash,
                    idempotency_key, actor_user_id, before_snapshot, after_snapshot,
                    blocker_counts, result, error_code, occurred_at
                ) VALUES (
                    :id, :companyId, :action, :targetType, :targetId, :reason, :requestHash,
                    :idempotencyKey, :actorUserId, CAST(:beforeSnapshot AS jsonb),
                    CAST(:afterSnapshot AS jsonb), CAST(:blockerCounts AS jsonb),
                    :result, :errorCode, :occurredAt
                )
                """).param("id", record.id()).param("companyId", record.companyId())
                .param("action", record.action().name()).param("targetType", record.targetType())
                .param("targetId", record.targetId()).param("reason", record.reason())
                .param("requestHash", record.requestHash()).param("idempotencyKey", record.idempotencyKey())
                .param("actorUserId", record.actorUserId()).param("beforeSnapshot", json(record.beforeSnapshot()))
                .param("afterSnapshot", json(record.afterSnapshot())).param("blockerCounts", json(record.blockerCounts()))
                .param("result", record.result().name()).param("errorCode", record.errorCode())
                .param("occurredAt", OffsetDateTime.ofInstant(record.occurredAt(), ZoneOffset.UTC)).update();
        if (inserted != 1) throw new IllegalStateException("admin override insert failed");
    }

    @Override
    public List<GovernanceOverrideRecord> findAll(UUID companyId, GovernanceOverrideAction action,
            String targetType, UUID targetId, GovernanceOverrideResult result, int offset, int size) {
        return query("""
                SELECT * FROM yumpoo.admin_override
                WHERE company_id = :companyId
                  AND (:action IS NULL OR action = :action)
                  AND (:targetType IS NULL OR target_type = :targetType)
                  AND (:targetId IS NULL OR target_id = :targetId)
                  AND (:result IS NULL OR result = :result)
                ORDER BY occurred_at DESC, id DESC
                OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY
                """, companyId, action, targetType, targetId, result)
                .param("offset", offset).param("size", size).query(this::map).list();
    }

    @Override
    public long count(UUID companyId, GovernanceOverrideAction action, String targetType,
            UUID targetId, GovernanceOverrideResult result) {
        return query("""
                SELECT count(*) FROM yumpoo.admin_override
                WHERE company_id = :companyId
                  AND (:action IS NULL OR action = :action)
                  AND (:targetType IS NULL OR target_type = :targetType)
                  AND (:targetId IS NULL OR target_id = :targetId)
                  AND (:result IS NULL OR result = :result)
                """, companyId, action, targetType, targetId, result).query(Long.class).single();
    }

    private JdbcClient.StatementSpec query(String sql, UUID companyId, GovernanceOverrideAction action,
            String targetType, UUID targetId, GovernanceOverrideResult result) {
        return jdbc.sql(sql).param("companyId", companyId)
                .param("action", action == null ? null : action.name())
                .param("targetType", targetType).param("targetId", targetId)
                .param("result", result == null ? null : result.name());
    }

    private GovernanceOverrideRecord map(ResultSet rs, int row) throws SQLException {
        return new GovernanceOverrideRecord(rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), GovernanceOverrideAction.valueOf(rs.getString("action")),
                rs.getString("target_type"), rs.getObject("target_id", UUID.class), rs.getString("reason"),
                rs.getString("request_hash"), rs.getObject("idempotency_key", UUID.class),
                rs.getObject("actor_user_id", UUID.class), tree(rs.getString("before_snapshot")),
                tree(rs.getString("after_snapshot")), tree(rs.getString("blocker_counts")),
                GovernanceOverrideResult.valueOf(rs.getString("result")), rs.getString("error_code"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
    }

    private String json(JsonNode node) {
        if (node == null) return null;
        try { return objectMapper.writeValueAsString(node); }
        catch (JacksonException e) { throw new IllegalArgumentException("override json serialization failed", e); }
    }

    private JsonNode tree(String value) {
        if (value == null) return null;
        try { return objectMapper.readTree(value); }
        catch (JacksonException e) { throw new IllegalStateException("stored override json is invalid", e); }
    }
}
