package com.yumpoo.platform;

import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class YumpooServerApplicationIT {

    private static final String PLATFORM_SCHEMA = "yumpoo";
    private static final String SCHEMA_COMMENT = "YumpooPlatform single business schema";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @Test
    void livenessAndDatabaseBackedReadinessProbesAreAvailableWithoutDetails() throws Exception {
        HttpResponse<String> liveness = get("/actuator/health/liveness");
        HttpResponse<String> readiness = get("/actuator/health/readiness");

        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void actuatorEndpointsOutsideHealthAreNotExposed() throws Exception {
        HttpResponse<String> response = get("/actuator/env");

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void m012WeComProbeRoutesAreAbsentWithoutTheExplicitLiveProfile() throws Exception {
        HttpResponse<String> authorize = get("/_m0/m0-12/wecom/authorize");
        HttpResponse<String> callback = get("/_m0/m0-12/wecom/callback");

        assertThat(authorize.statusCode()).isEqualTo(404);
        assertThat(callback.statusCode()).isEqualTo(404);
    }

    @Test
    void m014AttachmentProbeRoutesAreAbsentWithoutTheExplicitTestProfile() throws Exception {
        HttpResponse<String> response = get(
                "/api/v1/__test/m0-14/attachments/"
                        + "00000000-0000-0000-0000-000000000014"
        );

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void m015DesktopAuthProbeRoutesAreAbsentWithoutTheExplicitLiveProfile() throws Exception {
        HttpResponse<String> authorize = get("/_m0/m0-15/electron/auth/authorize");
        HttpResponse<String> callback = get("/_m0/m0-15/wecom/callback");
        HttpResponse<String> exchange = get("/_m0/m0-15/electron/auth/exchange");

        assertThat(authorize.statusCode()).isEqualTo(404);
        assertThat(callback.statusCode()).isEqualTo(404);
        assertThat(exchange.statusCode()).isEqualTo(404);
    }

    @Test
    void databaseUsesThePostgresql17Utf8UtcBaseline() throws IOException, InterruptedException {
        String version = jdbcTemplate.queryForObject("SHOW server_version", String.class);
        String versionNumber = jdbcTemplate.queryForObject("SHOW server_version_num", String.class);
        String encoding = jdbcTemplate.queryForObject("SHOW server_encoding", String.class);
        String sessionTimezone = jdbcTemplate.queryForObject("SHOW TimeZone", String.class);
        Container.ExecResult serverTimezone = postgresContainer.execInContainer(
                "psql",
                "-U",
                postgresContainer.getUsername(),
                "-d",
                postgresContainer.getDatabaseName(),
                "-tAc",
                "SHOW TimeZone"
        );

        assertThat(version).startsWith("17.10");
        assertThat(Integer.parseInt(versionNumber)).isBetween(170000, 179999);
        assertThat(encoding).isEqualTo("UTF8");
        assertThat(sessionTimezone).isEqualTo("UTC");
        assertThat(serverTimezone.getExitCode()).isZero();
        assertThat(serverTimezone.getStderr()).isBlank();
        assertThat(serverTimezone.getStdout().trim()).isEqualTo("UTC");
    }

    @Test
    void emptyDatabaseMigrationCreatesThePlatformSchemaAndFoundationTables() {
        Configuration configuration = flyway.getConfiguration();
        List<String> successfulMigrationVersions = jdbcTemplate.queryForList(
                "SELECT version FROM yumpoo.flyway_schema_history "
                        + "WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                String.class
        );
        String schemaComment = jdbcTemplate.queryForObject(
                "SELECT obj_description(oid, 'pg_namespace') "
                        + "FROM pg_namespace WHERE nspname = 'yumpoo'",
                String.class
        );
        List<String> applicationTableNames = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'yumpoo' "
                        + "AND table_name <> 'flyway_schema_history' "
                        + "ORDER BY table_name",
                String.class
        );
        List<String> outboxConstraintNames = jdbcTemplate.queryForList(
                "SELECT constraint_record.conname "
                        + "FROM pg_constraint constraint_record "
                        + "JOIN pg_class table_record "
                        + "ON table_record.oid = constraint_record.conrelid "
                        + "JOIN pg_namespace schema_record "
                        + "ON schema_record.oid = table_record.relnamespace "
                        + "WHERE schema_record.nspname = 'yumpoo' "
                        + "AND table_record.relname IN "
                        + "('outbox_event', 'outbox_consumer_receipt') "
                        + "AND constraint_record.contype <> 'n'",
                String.class
        );
        List<String> outboxIndexNames = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes "
                        + "WHERE schemaname = 'yumpoo' "
                        + "AND tablename IN ('outbox_event', 'outbox_consumer_receipt')",
                String.class
        );
        List<String> oauthAttemptConstraintNames = jdbcTemplate.queryForList(
                "SELECT constraint_record.conname "
                        + "FROM pg_constraint constraint_record "
                        + "JOIN pg_class table_record "
                        + "ON table_record.oid = constraint_record.conrelid "
                        + "JOIN pg_namespace schema_record "
                        + "ON schema_record.oid = table_record.relnamespace "
                        + "WHERE schema_record.nspname = 'yumpoo' "
                        + "AND table_record.relname = 'wecom_oauth_attempt' "
                        + "AND constraint_record.contype <> 'n'",
                String.class
        );
        List<String> oauthAttemptIndexNames = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes "
                        + "WHERE schemaname = 'yumpoo' "
                        + "AND tablename = 'wecom_oauth_attempt'",
                String.class
        );
        List<String> desktopAttemptConstraintNames = jdbcTemplate.queryForList(
                "SELECT constraint_record.conname "
                        + "FROM pg_constraint constraint_record "
                        + "JOIN pg_class table_record "
                        + "ON table_record.oid = constraint_record.conrelid "
                        + "JOIN pg_namespace schema_record "
                        + "ON schema_record.oid = table_record.relnamespace "
                        + "WHERE schema_record.nspname = 'yumpoo' "
                        + "AND table_record.relname = 'desktop_auth_attempt' "
                        + "AND constraint_record.contype <> 'n'",
                String.class
        );
        List<String> desktopAttemptIndexNames = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes "
                        + "WHERE schemaname = 'yumpoo' "
                        + "AND tablename = 'desktop_auth_attempt'",
                String.class
        );
        List<String> organizationConstraintNames = jdbcTemplate.queryForList(
                "SELECT constraint_record.conname "
                        + "FROM pg_constraint constraint_record "
                        + "JOIN pg_class table_record "
                        + "ON table_record.oid = constraint_record.conrelid "
                        + "JOIN pg_namespace schema_record "
                        + "ON schema_record.oid = table_record.relnamespace "
                        + "WHERE schema_record.nspname = 'yumpoo' "
                        + "AND table_record.relname IN ('company', 'company_calendar_day') "
                        + "AND constraint_record.contype <> 'n'",
                String.class
        );
        List<String> organizationIndexNames = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes "
                        + "WHERE schemaname = 'yumpoo' "
                        + "AND tablename IN ('company', 'company_calendar_day')",
                String.class
        );
        List<String> companySeeds = jdbcTemplate.queryForList(
                "SELECT id::text || '|' || singleton_slot || '|' || display_name || '|' "
                        + "|| timezone || '|' || week_start_day || '|' "
                        + "|| default_workday_minutes || '|' || row_version "
                        + "FROM yumpoo.company",
                String.class
        );

        assertThat(configuration.getDefaultSchema()).isEqualTo(PLATFORM_SCHEMA);
        assertThat(configuration.getSchemas()).containsExactly(PLATFORM_SCHEMA);
        assertThat(configuration.isValidateOnMigrate()).isTrue();
        assertThat(configuration.isCleanDisabled()).isTrue();
        assertThat(configuration.isBaselineOnMigrate()).isFalse();
        assertThat(successfulMigrationVersions).containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(schemaComment).isEqualTo(SCHEMA_COMMENT);
        assertThat(applicationTableNames).containsExactly(
                "company",
                "company_calendar_day",
                "desktop_auth_attempt",
                "idempotency_record",
                "outbox_consumer_receipt",
                "outbox_event",
                "wecom_oauth_attempt"
        );
        assertThat(outboxConstraintNames).containsExactlyInAnyOrder(
                "outbox_event_pkey",
                "uq_outbox_event_aggregate_fact",
                "ck_outbox_event_id_v4",
                "ck_outbox_event_type",
                "ck_outbox_event_version",
                "ck_outbox_event_aggregate_type",
                "ck_outbox_event_aggregate_version",
                "ck_outbox_event_actor",
                "ck_outbox_event_request_id",
                "ck_outbox_event_correlation_id",
                "ck_outbox_event_payload",
                "ck_outbox_event_status",
                "ck_outbox_event_attempt_count",
                "ck_outbox_event_last_error",
                "ck_outbox_event_lifecycle",
                "ck_outbox_event_times",
                "outbox_consumer_receipt_pkey",
                "fk_outbox_consumer_receipt_event",
                "ck_outbox_consumer_receipt_name"
        );
        assertThat(outboxIndexNames).containsExactlyInAnyOrder(
                "outbox_event_pkey",
                "uq_outbox_event_aggregate_fact",
                "idx_outbox_event_available",
                "idx_outbox_event_expired_lease",
                "idx_outbox_event_aggregate_order",
                "outbox_consumer_receipt_pkey",
                "idx_outbox_consumer_receipt_event"
        );
        assertThat(oauthAttemptConstraintNames).containsExactlyInAnyOrder(
                "wecom_oauth_attempt_pkey",
                "uq_wecom_oauth_attempt_nonce_hash",
                "ck_wecom_oauth_attempt_state_hash",
                "ck_wecom_oauth_attempt_nonce_hash",
                "ck_wecom_oauth_attempt_request_id",
                "ck_wecom_oauth_attempt_expires_at",
                "ck_wecom_oauth_attempt_consumed_at"
        );
        assertThat(oauthAttemptIndexNames).containsExactlyInAnyOrder(
                "wecom_oauth_attempt_pkey",
                "uq_wecom_oauth_attempt_nonce_hash",
                "idx_wecom_oauth_attempt_expires_at"
        );
        assertThat(desktopAttemptConstraintNames).containsExactlyInAnyOrder(
                "desktop_auth_attempt_pkey",
                "uq_desktop_auth_attempt_oauth_state_hash",
                "uq_desktop_auth_attempt_handoff_code_hash",
                "ck_desktop_auth_attempt_desktop_state_hash",
                "ck_desktop_auth_attempt_oauth_state_hash",
                "ck_desktop_auth_attempt_pkce_s256_challenge",
                "ck_desktop_auth_attempt_request_id",
                "ck_desktop_auth_attempt_authorize_window",
                "ck_desktop_auth_attempt_handoff_code_hash",
                "ck_desktop_auth_attempt_corp_fingerprint",
                "ck_desktop_auth_attempt_member_fingerprint",
                "ck_desktop_auth_attempt_handoff_lifecycle",
                "ck_desktop_auth_attempt_handoff_window",
                "ck_desktop_auth_attempt_consumed_at"
        );
        assertThat(desktopAttemptIndexNames).containsExactlyInAnyOrder(
                "desktop_auth_attempt_pkey",
                "uq_desktop_auth_attempt_oauth_state_hash",
                "uq_desktop_auth_attempt_handoff_code_hash",
                "idx_desktop_auth_attempt_authorize_expires_at",
                "idx_desktop_auth_attempt_handoff_expires_at"
        );
        assertThat(organizationConstraintNames).containsExactlyInAnyOrder(
                "company_pkey",
                "uq_company_singleton_slot",
                "ck_company_id_v4",
                "ck_company_singleton_slot",
                "ck_company_display_name",
                "ck_company_timezone",
                "ck_company_week_start_day",
                "ck_company_default_workday_minutes",
                "ck_company_row_version",
                "ck_company_timestamps",
                "company_calendar_day_pkey",
                "fk_company_calendar_day_company",
                "ck_company_calendar_day_type",
                "ck_company_calendar_day_minutes",
                "ck_company_calendar_day_source",
                "ck_company_calendar_day_note",
                "ck_company_calendar_day_row_version",
                "ck_company_calendar_day_timestamps"
        );
        assertThat(organizationIndexNames).containsExactlyInAnyOrder(
                "company_pkey",
                "uq_company_singleton_slot",
                "company_calendar_day_pkey"
        );
        assertThat(companySeeds).containsExactly(
                "00000000-0000-4000-8000-000000000001|1|Yumpoo|Asia/Shanghai|MONDAY|480|0"
        );
    }

    @Test
    void repeatedValidationAndMigrationDoNotDuplicateHistory() {
        Integer historyCountBefore = migrationHistoryCount();

        ValidateResult validation = flyway.validateWithResult();
        MigrateResult repeatMigration = flyway.migrate();

        assertThat(validation.validationSuccessful).isTrue();
        assertThat(validation.invalidMigrations).isEmpty();
        assertThat(repeatMigration.success).isTrue();
        assertThat(repeatMigration.migrationsExecuted).isZero();
        assertThat(migrationHistoryCount()).isEqualTo(historyCountBefore);
    }

    @Test
    void changedMigrationChecksumIsRejected(@TempDir Path migrationDirectory) throws IOException {
        Path migration = migrationDirectory.resolve("V1__create_checksum_probe.sql");
        Files.writeString(
                migration,
                "CREATE TABLE checksum_probe.probe (id integer PRIMARY KEY);\n",
                StandardCharsets.UTF_8
        );
        Flyway original = checksumProbeFlyway(migrationDirectory);

        MigrateResult firstMigration = original.migrate();

        Files.writeString(
                migration,
                "CREATE TABLE checksum_probe.probe (id integer PRIMARY KEY, note text);\n",
                StandardCharsets.UTF_8
        );
        ValidateResult changedValidation = checksumProbeFlyway(migrationDirectory).validateWithResult();

        assertThat(firstMigration.success).isTrue();
        assertThat(firstMigration.migrationsExecuted).isOne();
        assertThat(changedValidation.validationSuccessful).isFalse();
        assertThat(changedValidation.invalidMigrations).hasSize(1);
        assertThat(changedValidation.getAllErrorMessages()).containsIgnoringCase("checksum mismatch");
    }

    private Flyway checksumProbeFlyway(Path migrationDirectory) {
        String location = "filesystem:"
                + migrationDirectory.toAbsolutePath().toString().replace('\\', '/');
        return Flyway.configure()
                .dataSource(
                        postgresContainer.getJdbcUrl(),
                        postgresContainer.getUsername(),
                        postgresContainer.getPassword()
                )
                .locations(location)
                .defaultSchema("checksum_probe")
                .schemas("checksum_probe")
                .createSchemas(true)
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .load();
    }

    private int migrationHistoryCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM yumpoo.flyway_schema_history",
                Integer.class
        );
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
