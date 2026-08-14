package com.yumpoo.platform.audit.infrastructure;

import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.audit.api.SecurityAuditQueryPort;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "yumpoo.outbox.enabled=false")
class M110SecurityAuditIT {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired
    private SecurityAuditAppendPort appendPort;
    @Autowired
    private SecurityAuditQueryPort queryPort;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanUp() {
        jdbcClient.sql("DROP TRIGGER IF EXISTS m110_fail_audit ON yumpoo.security_audit_event").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS yumpoo.m110_fail_audit()").update();
        jdbcClient.sql("DELETE FROM yumpoo.security_audit_event WHERE fact_key LIKE 'm110:%'").update();
        jdbcClient.sql("DELETE FROM yumpoo.identity_user WHERE display_name = 'M1-10 Rollback Probe'").update();
    }

    @Test
    void appendsIdempotentFactsAndQueriesNewestFirstWithCompanyIsolation() {
        UUID first = append("m110:fact:1", "m110-request", Instant.parse("2026-08-14T01:00:00Z"));
        UUID replay = append("m110:fact:1", "m110-request", Instant.parse("2026-08-14T01:00:00Z"));
        UUID second = append("m110:fact:2", "m110-request", Instant.parse("2026-08-14T02:00:00Z"));

        assertThat(replay).isEqualTo(first);
        var page = queryPort.findByRequestId(COMPANY_ID, "m110-request", 0, 1);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).extracting(event -> event.id()).containsExactly(second);
        assertThat(queryPort.findByRequestId(UUID.randomUUID(), "m110-request", 0, 100).items())
                .isEmpty();
    }

    @Test
    void persistsOnlyWhitelistedSummariesWithoutSensitiveSentinels() {
        append("m110:sensitive-scan", "m110-sensitive", Instant.parse("2026-08-14T03:00:00Z"));

        String persisted = jdbcClient.sql("""
                        SELECT row_to_json(event)::text
                        FROM yumpoo.security_audit_event event
                        WHERE fact_key = 'm110:sensitive-scan'
                        """).query(String.class).single();
        assertThat(persisted).doesNotContain(
                "Cookie", "csrf", "wecom-secret", "13800000000", "raw-ip", "exception-message");
    }

    @Test
    void auditInsertFailureRollsBackBusinessMutation() {
        jdbcClient.sql("""
                CREATE FUNCTION yumpoo.m110_fail_audit() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'injected audit failure'; END;
                $$ LANGUAGE plpgsql
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER m110_fail_audit BEFORE INSERT ON yumpoo.security_audit_event
                FOR EACH ROW EXECUTE FUNCTION yumpoo.m110_fail_audit()
                """).update();
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> withContext("m110-rollback", () ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbcClient.sql("""
                                    INSERT INTO yumpoo.identity_user (
                                        id, company_id, employment_status, account_status,
                                        display_name, directory_synced_at, authorization_version,
                                        row_version, created_at, updated_at
                                    ) VALUES (
                                        :id, :companyId, 'ACTIVE', 'ENABLED', 'M1-10 Rollback Probe',
                                        transaction_timestamp(), 0, 0,
                                        transaction_timestamp(), transaction_timestamp()
                                    )
                                    """)
                            .param("id", userId).param("companyId", COMPANY_ID).update();
                    appendPort.append(draft("m110:rollback", Instant.now()));
                }))).isInstanceOf(RuntimeException.class);

        assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.identity_user WHERE id = :id")
                .param("id", userId).query(Integer.class).single()).isZero();
    }

    private UUID append(String factKey, String requestId, Instant occurredAt) {
        final UUID[] result = new UUID[1];
        withContext(requestId, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> result[0] = appendPort.append(draft(factKey, occurredAt))));
        return result[0];
    }

    private SecurityAuditDraft draft(String factKey, Instant occurredAt) {
        return new SecurityAuditDraft(
                COMPANY_ID, factKey, "M110_AUDIT_PROBE", SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.system("M110_TEST"), "USER", factKey,
                null, objectMapper.valueToTree(Map.of("status", "BEFORE")),
                objectMapper.valueToTree(Map.of("status", "AFTER")), null,
                null, "WEB", "m110", occurredAt);
    }

    private void withContext(String requestId, Runnable runnable) {
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root(requestId))) {
            runnable.run();
        }
    }
}
