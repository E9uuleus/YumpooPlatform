package com.yumpoo.platform.identityaccess.infrastructure.authorization;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PlatformRoleTierMigrationIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");
    static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Test
    void consolidatesDualAssignmentsWithoutInvalidatingAuthorizationAndPreservesHistory() {
        migrate("55");
        var jdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID user = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO yumpoo.identity_user(id, company_id, employment_status, account_status,
                    display_name, directory_synced_at, authorization_version, created_at, updated_at)
                VALUES (:id, :company, 'ACTIVE', 'ENABLED', 'Migration user', transaction_timestamp(), 7, transaction_timestamp(), transaction_timestamp())
                """).param("id", user).param("company", COMPANY).update();
        insertRole(jdbc, user, "COMPANY_ADMIN", "COMPANY");
        jdbc.sql("""
                UPDATE yumpoo.platform_role_assignment SET status='REVOKED', revoked_by_user_id=:id,
                    revoked_at=transaction_timestamp(), revoke_reason='historical revoke'
                WHERE user_id=:id
                """).param("id", user).update();
        insertRole(jdbc, user, "APP_MANAGER", "PLATFORM");
        insertRole(jdbc, user, "COMPANY_ADMIN", "COMPANY");
        long events = jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event").query(Long.class).single();
        migrate("56");
        assertThat(jdbc.sql("SELECT role_code FROM yumpoo.platform_role_assignment WHERE user_id=:id AND status='ACTIVE'")
                .param("id", user).query(String.class).list()).containsExactly("APP_MANAGER");
        assertThat(jdbc.sql("SELECT revoked_by_actor_type || ':' || revoked_by_system_code FROM yumpoo.platform_role_assignment WHERE user_id=:id AND status='REVOKED' AND revoked_by_actor_type='SYSTEM'")
                .param("id", user).query(String.class).single()).isEqualTo("SYSTEM:ROLE_TIER_CONSOLIDATION");
        assertThat(jdbc.sql("SELECT authorization_version FROM yumpoo.identity_user WHERE id=:id")
                .param("id", user).query(Long.class).single()).isEqualTo(7);
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event").query(Long.class).single()).isEqualTo(events);
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.platform_role_assignment WHERE user_id=:id AND revoked_by_actor_type='USER'")
                .param("id", user).query(Integer.class).single()).isOne();
        assertThatThrownBy(() -> insertRole(jdbc, user, "COMPANY_ADMIN", "COMPANY"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE yumpoo.platform_role_assignment SET revoked_by_actor_type=NULL WHERE user_id=:id AND status='REVOKED'")
                .param("id", user).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static void insertRole(JdbcClient jdbc, UUID user, String role, String scope) {
        jdbc.sql("""
                INSERT INTO yumpoo.platform_role_assignment(id, company_id, user_id, role_code,
                    scope_type, scope_id, status, granted_by_actor_type, granted_by_system_code,
                    grant_reason, granted_at)
                VALUES (:id, :company, :user, :role, :scope, :company, 'ACTIVE', 'SYSTEM',
                    'MIGRATION_TEST', 'migration test', transaction_timestamp())
                """).param("id", UUID.randomUUID()).param("company", COMPANY).param("user", user)
                .param("role", role).param("scope", scope).update();
    }

    private static void migrate(String target) {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").schemas("yumpoo").defaultSchema("yumpoo")
                .createSchemas(true).target(target).load().migrate();
    }
}
