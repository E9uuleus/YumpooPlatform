package com.yumpoo.platform.identityaccess.infrastructure.identity;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PersonalWorkspaceSlugMigrationIT {

    private static final UUID COMPANY_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000001"
    );
    private static final UUID FIRST_USER_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111101"
    );
    private static final UUID COLLIDING_USER_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222202"
    );
    private static final UUID RESERVED_USER_ID = UUID.fromString(
            "33333333-3333-4333-8333-333333333303"
    );
    private static final UUID INVALID_USER_ID = UUID.fromString(
            "44444444-4444-4444-8444-444444444404"
    );
    private static final UUID UNBOUND_USER_ID = UUID.fromString(
            "55555555-5555-4555-8555-555555555505"
    );

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:17.10-alpine")
    ).withDatabaseName("yumpoo_workspace_slug")
            .withUsername("yumpoo_test")
            .withPassword("yumpoo_test")
            .withEnv("TZ", "UTC")
            .withCommand("postgres", "-c", "timezone=UTC");

    @Test
    void upgradesExistingIdentitiesWithStableReadableAndProtectedSlugs() {
        migrateTo(MigrationVersion.fromVersion("32"));
        JdbcClient jdbcClient = jdbcClient();
        insertUser(jdbcClient, FIRST_USER_ID, "First", 1);
        insertExternalIdentity(jdbcClient, FIRST_USER_ID, "Alpha/User", 1);
        insertUser(jdbcClient, COLLIDING_USER_ID, "Second", 2);
        insertExternalIdentity(jdbcClient, COLLIDING_USER_ID, "ALPHA USER", 2);
        insertUser(jdbcClient, RESERVED_USER_ID, "Reserved", 3);
        insertExternalIdentity(jdbcClient, RESERVED_USER_ID, "admin", 3);
        insertUser(jdbcClient, INVALID_USER_ID, "Invalid", 4);
        insertExternalIdentity(jdbcClient, INVALID_USER_ID, "研发一组", 4);
        insertUser(jdbcClient, UNBOUND_USER_ID, "Unbound", 5);

        migrateTo(null);

        assertThat(jdbcClient.sql("""
                        SELECT pg_get_constraintdef(oid)
                        FROM pg_constraint
                        WHERE connamespace = 'yumpoo'::regnamespace
                          AND conname = 'uq_identity_user_company_workspace_slug'
                        """)
                .query(String.class)
                .single()).isEqualTo("UNIQUE (company_id, workspace_slug)");
        assertThat(slug(jdbcClient, FIRST_USER_ID)).isEqualTo("alpha-user");
        assertThat(slug(jdbcClient, COLLIDING_USER_ID))
                .isEqualTo("alpha-user-22222222");
        assertThat(slug(jdbcClient, RESERVED_USER_ID))
                .isEqualTo("u-33333333333343338333333333333303");
        assertThat(slug(jdbcClient, INVALID_USER_ID))
                .isEqualTo("u-44444444444444448444444444444404");
        assertThat(slug(jdbcClient, UNBOUND_USER_ID))
                .isEqualTo("u-55555555555545558555555555555505");

        UUID fallbackUserId = UUID.fromString(
                "66666666-6666-4666-8666-666666666606"
        );
        insertUser(jdbcClient, fallbackUserId, "Internal", 6);
        assertThat(slug(jdbcClient, fallbackUserId))
                .isEqualTo("u-66666666666646668666666666666606");

        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE yumpoo.identity_user
                        SET workspace_slug = 'changed'
                        WHERE id = :id
                        """)
                .param("id", FIRST_USER_ID)
                .update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO yumpoo.identity_user (
                            id, company_id, employment_status, account_status,
                            display_name, workspace_slug, directory_synced_at,
                            row_version, created_at, updated_at
                        ) VALUES (
                            :id, :companyId, 'ACTIVE', 'ENABLED',
                            'Reserved Explicitly', 'settings', :now,
                            0, :now, :now
                        )
                        """)
                .param("id", UUID.fromString("77777777-7777-4777-8777-777777777707"))
                .param("companyId", COMPANY_ID)
                .param("now", timestamp(7))
                .update()).isInstanceOf(DataAccessException.class);
    }

    private static void migrateTo(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("yumpoo")
                .schemas("yumpoo")
                .createSchemas(true)
                .validateOnMigrate(true)
                .cleanDisabled(true);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static JdbcClient jdbcClient() {
        return JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ));
    }

    private static void insertUser(
            JdbcClient jdbcClient,
            UUID userId,
            String displayName,
            int minute
    ) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.identity_user (
                            id, company_id, employment_status, account_status,
                            display_name, directory_synced_at,
                            row_version, created_at, updated_at
                        ) VALUES (
                            :id, :companyId, 'ACTIVE', 'ENABLED',
                            :displayName, :now,
                            0, :now, :now
                        )
                        """)
                .param("id", userId)
                .param("companyId", COMPANY_ID)
                .param("displayName", displayName)
                .param("now", timestamp(minute))
                .update();
    }

    private static void insertExternalIdentity(
            JdbcClient jdbcClient,
            UUID userId,
            String externalUserId,
            int minute
    ) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.external_identity (
                            id, company_id, user_id, provider, external_user_id,
                            provider_employment_status, raw_profile_hash,
                            last_seen_at, created_at, updated_at
                        ) VALUES (
                            :id, :companyId, :userId, 'WECOM', :externalUserId,
                            'ACTIVE', :profileHash,
                            :now, :now, :now
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("companyId", COMPANY_ID)
                .param("userId", userId)
                .param("externalUserId", externalUserId)
                .param("profileHash", Integer.toHexString(minute).repeat(64))
                .param("now", timestamp(minute))
                .update();
    }

    private static String slug(JdbcClient jdbcClient, UUID userId) {
        return jdbcClient.sql("""
                        SELECT workspace_slug
                        FROM yumpoo.identity_user
                        WHERE id = :id
                        """)
                .param("id", userId)
                .query(String.class)
                .single();
    }

    private static OffsetDateTime timestamp(int minute) {
        return OffsetDateTime.of(2026, 8, 24, 0, minute, 0, 0, ZoneOffset.UTC);
    }
}
