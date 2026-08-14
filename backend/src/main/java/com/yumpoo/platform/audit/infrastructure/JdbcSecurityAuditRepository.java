package com.yumpoo.platform.audit.infrastructure;

import com.yumpoo.platform.audit.application.SecurityAuditRepository;
import com.yumpoo.platform.audit.application.SecurityAuditRecord;
import com.yumpoo.platform.audit.application.SecurityAuditStoredEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcSecurityAuditRepository implements SecurityAuditRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcSecurityAuditRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public UUID append(SecurityAuditRecord draft, String requestId, String correlationId) {
        UUID id = UUID.randomUUID();
        int inserted = jdbcClient.sql("""
                            INSERT INTO yumpoo.security_audit_event (
                                id, company_id, fact_key, action, outcome,
                                actor_type, actor_user_id, actor_system_code, actor_role_snapshot,
                                target_type, target_id, reason_reference,
                                before_summary, after_summary, error_code, command_id,
                                request_id, correlation_id, client_type, client_version, occurred_at
                            ) VALUES (
                                :id, :companyId, :factKey, :action, :outcome,
                                :actorType, :actorUserId, :actorSystemCode, CAST(:actorRoles AS jsonb),
                                :targetType, :targetId, :reasonReference,
                                CAST(:beforeSummary AS jsonb), CAST(:afterSummary AS jsonb),
                                :errorCode, :commandId, :requestId, :correlationId,
                                :clientType, :clientVersion, :occurredAt
                            ) ON CONFLICT (company_id, fact_key) DO NOTHING
                            """)
                    .param("id", id)
                    .param("companyId", draft.companyId())
                    .param("factKey", draft.factKey())
                    .param("action", draft.action())
                    .param("outcome", draft.outcome())
                    .param("actorType", draft.actorType())
                    .param("actorUserId", draft.actorUserId())
                    .param("actorSystemCode", draft.actorSystemCode())
                    .param("actorRoles", json(draft.actorRoles()))
                    .param("targetType", draft.targetType())
                    .param("targetId", draft.targetId())
                    .param("reasonReference", draft.reasonReference())
                    .param("beforeSummary", json(draft.beforeSummary()))
                    .param("afterSummary", json(draft.afterSummary()))
                    .param("errorCode", draft.errorCode())
                    .param("commandId", draft.commandId())
                    .param("requestId", requestId)
                    .param("correlationId", correlationId)
                    .param("clientType", draft.clientType())
                    .param("clientVersion", draft.clientVersion())
                    .param("occurredAt", OffsetDateTime.ofInstant(draft.occurredAt(), ZoneOffset.UTC))
                    .update();
        if (inserted == 1) {
            return id;
        }
        ExistingFact existing = jdbcClient.sql("""
                        SELECT id, action, outcome, target_type, target_id
                        FROM yumpoo.security_audit_event
                        WHERE company_id = :companyId AND fact_key = :factKey
                        """)
                .param("companyId", draft.companyId())
                .param("factKey", draft.factKey())
                .query((rs, row) -> new ExistingFact(
                        rs.getObject("id", UUID.class), rs.getString("action"),
                        rs.getString("outcome"), rs.getString("target_type"),
                        rs.getString("target_id")))
                .single();
        if (!existing.action().equals(draft.action())
                || !existing.outcome().equals(draft.outcome())
                || !existing.targetType().equals(draft.targetType())
                || !existing.targetId().equals(draft.targetId())) {
            throw new IllegalStateException("security audit fact key collision");
        }
        return existing.id();
    }

    @Override
    public List<SecurityAuditStoredEvent> findByRequestId(
            UUID companyId, String requestId, int offset, int size
    ) {
        return jdbcClient.sql("""
                        SELECT * FROM yumpoo.security_audit_event
                        WHERE company_id = :companyId AND request_id = :requestId
                        ORDER BY occurred_at DESC, id DESC
                        OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY
                        """)
                .param("companyId", companyId)
                .param("requestId", requestId)
                .param("offset", offset)
                .param("size", size)
                .query(this::map)
                .list();
    }

    @Override
    public long countByRequestId(UUID companyId, String requestId) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM yumpoo.security_audit_event
                        WHERE company_id = :companyId AND request_id = :requestId
                        """)
                .param("companyId", companyId)
                .param("requestId", requestId)
                .query(Long.class)
                .single();
    }

    private SecurityAuditStoredEvent map(ResultSet rs, int row) throws SQLException {
        return new SecurityAuditStoredEvent(
                rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("fact_key"), rs.getString("action"),
                rs.getString("outcome"), rs.getString("actor_type"),
                rs.getObject("actor_user_id", UUID.class), rs.getString("actor_system_code"),
                readRoles(rs.getString("actor_role_snapshot")), rs.getString("target_type"),
                rs.getString("target_id"), rs.getString("reason_reference"),
                readTree(rs.getString("before_summary")), readTree(rs.getString("after_summary")),
                rs.getString("error_code"), rs.getObject("command_id", UUID.class),
                rs.getString("request_id"), rs.getString("correlation_id"),
                rs.getString("client_type"), rs.getString("client_version"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("security audit summary serialization failed", exception);
        }
    }

    private JsonNode readTree(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("security audit summary is invalid", exception);
        }
    }

    private Set<String> readRoles(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<Set<String>>() { });
        } catch (JacksonException exception) {
            throw new IllegalStateException("security audit role snapshot is invalid", exception);
        }
    }

    private record ExistingFact(
            UUID id, String action, String outcome, String targetType, String targetId
    ) {
    }
}
