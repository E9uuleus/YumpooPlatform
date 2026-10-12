package com.yumpoo.platform.identityaccess.infrastructure.onboarding;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class OnboardingMigrationIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");
    private static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Test
    void backfillsOnlyPreviouslyLoggedInMembersAndLeavesFirstLoginsEligible() {
        migrate("69");
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID active = insertUser(jdbc);
        UUID expired = insertUser(jdbc);
        UUID audited = insertUser(jdbc);
        UUID syncedOnly = insertUser(jdbc);
        insertSession(jdbc, active, false);
        insertSession(jdbc, active, false);
        insertSession(jdbc, expired, true);
        insertAudit(jdbc, audited, "LOGIN_SUCCEEDED", "SUCCEEDED");
        insertAudit(jdbc, audited, "LOGIN_SUCCEEDED", "SUCCEEDED");
        insertAudit(jdbc, syncedOnly, "LOGIN_REJECTED", "FAILED");

        migrate("70");

        for (UUID user : List.of(active, expired, audited)) {
            assertThat(jdbc.sql("""
                    SELECT tour_id FROM yumpoo.user_onboarding_tour
                    WHERE company_id=:company AND user_id=:user AND version=1 AND completed_at IS NOT NULL
                    """).param("company", COMPANY).param("user", user).query(String.class).list())
                    .containsExactlyInAnyOrder("welcome", "project-work-items", "workspace", "dashboards", "inbox", "company");
        }
        assertThat(recordCount(jdbc, syncedOnly)).isZero();
        assertThat(recordCount(jdbc, insertUser(jdbc))).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.user_onboarding_tour").query(Integer.class).single()).isEqualTo(18);
    }

    private static int recordCount(JdbcClient jdbc, UUID user) {
        return jdbc.sql("SELECT count(*) FROM yumpoo.user_onboarding_tour WHERE user_id=:user")
                .param("user", user).query(Integer.class).single();
    }

    private static UUID insertUser(JdbcClient jdbc) {
        UUID user = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO yumpoo.identity_user(id, company_id, employment_status, account_status,
                    display_name, directory_synced_at, created_at, updated_at)
                VALUES (:user, :company, 'ACTIVE', 'ENABLED', 'Onboarding migration member',
                    transaction_timestamp(), transaction_timestamp(), transaction_timestamp())
                """).param("user", user).param("company", COMPANY).update();
        return user;
    }

    private static void insertSession(JdbcClient jdbc, UUID user, boolean expired) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO yumpoo.login_session(id, company_id, user_id, status,
                    session_token_fingerprint, session_key_version, issued_authorization_version, client_type,
                    issued_at, last_seen_at, idle_expires_at, absolute_expires_at, purge_after, revoked_at, revoke_reason)
                VALUES (:id, :company, :user, :status, :fingerprint, 'test', 0, 'WEB',
                    transaction_timestamp() - interval '2 days', transaction_timestamp() - interval '2 days',
                    transaction_timestamp() - interval '1 day', transaction_timestamp() - interval '1 day',
                    transaction_timestamp(),
                    CASE WHEN :expired THEN transaction_timestamp() - interval '1 day' ELSE NULL END,
                    CASE WHEN :expired THEN 'ABSOLUTE_EXPIRED' ELSE NULL END)
                """).param("id", id).param("company", COMPANY).param("user", user)
                .param("status", expired ? "EXPIRED" : "ACTIVE").param("expired", expired)
                .param("fingerprint", id.toString().replace("-", "").repeat(2)).update();
    }

    private static void insertAudit(JdbcClient jdbc, UUID user, String action, String outcome) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO yumpoo.security_audit_event(id, company_id, fact_key, action, outcome,
                    actor_type, actor_user_id, target_type, target_id, request_id, correlation_id, error_code, occurred_at)
                VALUES (:id, :company, :fact, :action, :outcome, 'USER', :user, 'LOGIN_SESSION', :fact,
                    'onboarding-migration', 'onboarding-migration', :error, transaction_timestamp())
                """).param("id", id).param("company", COMPANY).param("fact", id.toString())
                .param("action", action).param("outcome", outcome).param("user", user)
                .param("error", outcome.equals("SUCCEEDED") ? null : "LOGIN_REJECTED").update();
    }

    private static void migrate(String target) {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").schemas("yumpoo").defaultSchema("yumpoo")
                .createSchemas(true).target(target).load().migrate();
    }
}
