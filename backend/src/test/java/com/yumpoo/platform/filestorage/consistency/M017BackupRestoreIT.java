package com.yumpoo.platform.filestorage.consistency;

import com.yumpoo.platform.filestorage.application.PublishedBlob;
import com.yumpoo.platform.filestorage.application.SealedUpload;
import com.yumpoo.platform.filestorage.infrastructure.LocalFileQuarantineStorage;
import com.yumpoo.platform.filestorage.infrastructure.backup.M017BackupManifest;
import com.yumpoo.platform.filestorage.infrastructure.backup.M017BackupSet;
import com.yumpoo.platform.filestorage.infrastructure.backup.M017RetentionPlanner;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class M017BackupRestoreIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17.10-alpine");
    private static final UUID BACKUP_SET_ID = UUID.fromString("00000000-0000-4000-8000-000000000017");

    @Test
    void backsUpDatabaseAndAttachmentsAndRestoresIntoIsolatedTargets() throws Exception {
        Path repositoryRoot = Path.of("..").toAbsolutePath().normalize();
        Path outputRoot = repositoryRoot.resolve("out/m0-17");
        M017BackupSet.deleteTreeIfExists(outputRoot);
        Files.createDirectories(outputRoot);
        Path workRoot = Files.createDirectories(outputRoot.resolve("m017-work"));
        Instant startedAt = Instant.now();

        try (PostgreSQLContainer source = postgres("yumpoo_m017_source");
             PostgreSQLContainer target = postgres("yumpoo_m017_restore")) {
            source.start();
            migrate(source);

            Path sourceQuarantine = Files.createDirectories(workRoot.resolve("source-quarantine"));
            Path sourceBlobs = Files.createDirectories(workRoot.resolve("source-blobs"));
            LocalFileQuarantineStorage sourceStorage = new LocalFileQuarantineStorage(
                    sourceQuarantine,
                    sourceBlobs
            );
            PublishedBlob first = publish(sourceStorage, "M0-17 合成附件 A\n");
            PublishedBlob second = publish(sourceStorage, "M0-17 synthetic attachment B\n");
            PublishedBlob orphan = publish(sourceStorage, "M0-17 unreferenced orphan\n");
            createSyntheticReferences(source, List.of(first, second));
            createCompanyCalendarFact(source);
            createIdentityBindingFact(source);

            Path dump = workRoot.resolve("yumpoo.dump");
            createDump(source, dump);
            Path configuration = writeConfiguration(workRoot);
            Path restoreDescriptor = writeRestoreDescriptor(workRoot);
            String sourceCommit = gitHead(repositoryRoot);
            String flywayVersion = latestFlywayVersion(source);

            Path backupRoot = M017BackupSet.create(new M017BackupSet.CreateRequest(
                    outputRoot,
                    BACKUP_SET_ID,
                    startedAt,
                    "Asia/Shanghai",
                    "0.0.1-SNAPSHOT",
                    sourceCommit,
                    postgresVersion(source),
                    flywayVersion,
                    dump,
                    sourceBlobs,
                    configuration,
                    restoreDescriptor,
                    List.of("daily"),
                    false
            ));
            M017BackupManifest manifest = M017BackupSet.validate(backupRoot);

            target.start();
            requireEmptyDatabaseTarget(target);
            Path restoreRoot = Files.createDirectories(outputRoot.resolve("restore-sandbox"));
            Path restoredBlobs = Files.createDirectories(restoreRoot.resolve("blobs"));
            M017BackupSet.requireEmptyAttachmentTarget(restoredBlobs);
            restoreDump(target, backupRoot.resolve(manifest.databaseDumpPath()));
            M017BackupSet.restoreAttachments(backupRoot, restoredBlobs);

            List<PublishedBlob> restoredReferences = readSyntheticReferences(target);
            assertThat(restoredReferences).containsExactlyInAnyOrder(first, second);
            assertThat(latestFlywayVersion(target)).isEqualTo(flywayVersion);
            assertThat(countSyntheticReferences(source)).isEqualTo(2);
            assertThat(readCompanyCalendarFact(target)).isEqualTo(readCompanyCalendarFact(source));
            assertThat(readIdentityBindingFact(target)).isEqualTo(readIdentityBindingFact(source));
            assertThat(readSessionSecurityFact(target)).isEqualTo(readSessionSecurityFact(source));
            assertThat(readDirectorySyncFact(target)).isEqualTo(readDirectorySyncFact(source));

            Path restoreQuarantine = Files.createDirectories(restoreRoot.resolve("quarantine"));
            LocalFileQuarantineStorage restoredStorage = new LocalFileQuarantineStorage(
                    restoreQuarantine,
                    restoredBlobs
            );
            for (PublishedBlob reference : restoredReferences) {
                assertThat(restoredStorage.verify(reference)).isTrue();
                try (InputStream input = restoredStorage.open(reference)) {
                    assertThat(input.readAllBytes()).isNotEmpty();
                }
            }
            List<String> orphanKeys = orphanKeys(restoredBlobs, restoredReferences);
            assertThat(orphanKeys).containsExactly(orphan.storageKey());
            assertThat(Files.exists(restoredBlobs.resolve(Path.of(orphan.storageKey())))).isTrue();

            assertThatThrownBy(() -> requireEmptyDatabaseTarget(target))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("yumpoo schema");
            Path nonEmpty = Files.createDirectories(restoreRoot.resolve("nonempty"));
            Files.writeString(nonEmpty.resolve("existing"), "preserve", StandardCharsets.UTF_8);
            assertThatThrownBy(() -> M017BackupSet.restoreAttachments(backupRoot, nonEmpty))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("must be empty");
            assertThat(Files.readString(nonEmpty.resolve("existing"), StandardCharsets.UTF_8))
                    .isEqualTo("preserve");

            M017RetentionPlanner.RetentionPlan retentionPlan = sampleRetentionPlan(startedAt);
            M017BackupSet.json().writerWithDefaultPrettyPrinter()
                    .writeValue(outputRoot.resolve("retention-plan.json").toFile(), retentionPlan);
            writeVerificationReport(
                    outputRoot,
                    startedAt,
                    sourceCommit,
                    manifest,
                    restoredReferences.size(),
                    orphanKeys.size()
            );
        } finally {
            M017BackupSet.deleteTreeIfExists(workRoot);
        }
    }

    private static PostgreSQLContainer postgres(String databaseName) {
        return new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName(databaseName)
                .withUsername("yumpoo_m017")
                .withPassword("yumpoo_m017")
                .withEnv("TZ", "UTC")
                .withCommand("postgres", "-c", "timezone=UTC");
    }

    private static void migrate(PostgreSQLContainer container) {
        Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("yumpoo")
                .schemas("yumpoo")
                .createSchemas(true)
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .load()
                .migrate();
    }

    private static PublishedBlob publish(LocalFileQuarantineStorage storage, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        SealedUpload sealed = storage.receive(
                UUID.randomUUID(),
                new ByteArrayInputStream(bytes),
                OptionalLong.of(bytes.length)
        );
        return storage.publish(sealed);
    }

    private static void createSyntheticReferences(
            PostgreSQLContainer container,
            List<PublishedBlob> blobs
    ) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE yumpoo.m017_backup_reference (
                        id uuid PRIMARY KEY,
                        business_key varchar(64) NOT NULL UNIQUE,
                        storage_key varchar(96) NOT NULL,
                        size_bytes bigint NOT NULL CHECK (size_bytes > 0),
                        sha256 char(64) NOT NULL,
                        created_at timestamptz NOT NULL
                    )
                    """);
        }
        try (Connection connection = connection(container);
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO yumpoo.m017_backup_reference (
                         id, business_key, storage_key, size_bytes, sha256, created_at
                     ) VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            int index = 0;
            for (PublishedBlob blob : blobs) {
                index++;
                insert.setObject(1, UUID.nameUUIDFromBytes(("m017-" + index).getBytes(StandardCharsets.UTF_8)));
                insert.setString(2, "合成引用-" + index);
                insert.setString(3, blob.storageKey());
                insert.setLong(4, blob.sizeBytes());
                insert.setString(5, blob.sha256());
                insert.setObject(6, java.time.OffsetDateTime.parse("2026-08-12T12:00:00Z"));
                insert.addBatch();
            }
            assertThat(insert.executeBatch()).hasSize(blobs.size());
        }
    }

    private static void createDump(PostgreSQLContainer container, Path destination) throws Exception {
        String containerPath = "/tmp/m0-17-yumpoo.dump";
        Container.ExecResult result = container.execInContainer(
                "pg_dump",
                "--format=custom",
                "--no-owner",
                "--no-privileges",
                "--file=" + containerPath,
                "--username=" + container.getUsername(),
                "--dbname=" + container.getDatabaseName()
        );
        requireSuccess(result, "pg_dump");
        container.copyFileFromContainer(containerPath, destination.toString());
        assertThat(Files.size(destination)).isPositive();
    }

    private static void restoreDump(PostgreSQLContainer container, Path dump) throws Exception {
        String containerPath = "/tmp/m0-17-yumpoo.dump";
        container.copyFileToContainer(MountableFile.forHostPath(dump), containerPath);
        Container.ExecResult result = container.execInContainer(
                "pg_restore",
                "--exit-on-error",
                "--single-transaction",
                "--no-owner",
                "--no-privileges",
                "--username=" + container.getUsername(),
                "--dbname=" + container.getDatabaseName(),
                containerPath
        );
        requireSuccess(result, "pg_restore");
    }

    private static void requireEmptyDatabaseTarget(PostgreSQLContainer container) throws SQLException {
        try (Connection connection = connection(container);
             PreparedStatement query = connection.prepareStatement(
                     "SELECT count(*) FROM pg_namespace WHERE nspname = 'yumpoo'"
             );
             ResultSet result = query.executeQuery()) {
            result.next();
            if (result.getInt(1) != 0) {
                throw new IllegalStateException("restore target already contains yumpoo schema");
            }
        }
    }

    private static String latestFlywayVersion(PostgreSQLContainer container) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT version FROM yumpoo.flyway_schema_history
                     WHERE success AND version IS NOT NULL
                     ORDER BY installed_rank DESC LIMIT 1
                     """)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static void createCompanyCalendarFact(PostgreSQLContainer container) throws SQLException {
        try (Connection connection = connection(container);
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO yumpoo.company_calendar_day (
                         company_id, calendar_date, day_type, standard_minutes,
                         source, note, row_version, created_at, updated_at
                     ) VALUES (
                         '00000000-0000-4000-8000-000000000001',
                         DATE '2026-10-10', 'WORKDAY', 420,
                         'IMPORT', 'M0-17 restore probe', 3, ?, ?
                     )
                     """)) {
            OffsetDateTime createdAt = OffsetDateTime.ofInstant(
                    Instant.parse("2026-08-13T03:00:00Z"),
                    ZoneOffset.UTC
            );
            insert.setObject(1, createdAt);
            insert.setObject(2, createdAt);
            assertThat(insert.executeUpdate()).isOne();
        }
    }

    private static CompanyCalendarFact readCompanyCalendarFact(
            PostgreSQLContainer container
    ) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT
                         company.timezone,
                         company.week_start_day,
                         company.default_workday_minutes,
                         calendar.calendar_date,
                         calendar.day_type,
                         calendar.standard_minutes,
                         calendar.row_version
                     FROM yumpoo.company company
                     JOIN yumpoo.company_calendar_day calendar
                       ON calendar.company_id = company.id
                     WHERE calendar.calendar_date = DATE '2026-10-10'
                     """)) {
            assertThat(result.next()).isTrue();
            CompanyCalendarFact fact = new CompanyCalendarFact(
                    result.getString("timezone"),
                    result.getString("week_start_day"),
                    result.getInt("default_workday_minutes"),
                    result.getObject("calendar_date", LocalDate.class),
                    result.getString("day_type"),
                    result.getInt("standard_minutes"),
                    result.getLong("row_version")
            );
            assertThat(result.next()).isFalse();
            return fact;
        }
    }

    private static void createIdentityBindingFact(PostgreSQLContainer container) throws SQLException {
        OffsetDateTime observedAt = OffsetDateTime.ofInstant(
                Instant.parse("2026-08-13T04:00:00Z"),
                ZoneOffset.UTC
        );
        try (Connection connection = connection(container)) {
            connection.setAutoCommit(false);
            try (PreparedStatement insertUser = connection.prepareStatement("""
                    INSERT INTO yumpoo.identity_user (
                        id, company_id, employment_status, account_status,
                        display_name, email, mobile, department_summary,
                        directory_synced_at, authorization_version,
                        row_version, created_at, updated_at
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000102',
                        '00000000-0000-4000-8000-000000000001',
                        'ACTIVE', 'ENABLED', 'M1-02 Restore User',
                        'restore@example.test', '13800000102', 'Restore Team',
                        ?, 3, 4, ?, ?
                    )
                    """)) {
                insertUser.setObject(1, observedAt);
                insertUser.setObject(2, observedAt);
                insertUser.setObject(3, observedAt);
                assertThat(insertUser.executeUpdate()).isOne();
            }
            try (PreparedStatement insertIdentity = connection.prepareStatement("""
                    INSERT INTO yumpoo.external_identity (
                        id, company_id, user_id, provider, external_user_id,
                        provider_employment_status, raw_profile_hash,
                        last_seen_at, created_at, updated_at
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000202',
                        '00000000-0000-4000-8000-000000000001',
                        '00000000-0000-4000-8000-000000000102',
                        'WECOM', 'M1-02-Restore-Member', 'ACTIVE',
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        ?, ?, ?
                    )
                    """)) {
                insertIdentity.setObject(1, observedAt);
                insertIdentity.setObject(2, observedAt);
                insertIdentity.setObject(3, observedAt);
                assertThat(insertIdentity.executeUpdate()).isOne();
            }
            try (PreparedStatement insertSession = connection.prepareStatement("""
                    INSERT INTO yumpoo.login_session (
                        id, company_id, user_id, status,
                        session_token_fingerprint, session_key_version,
                        csrf_token_fingerprint, csrf_key_version,
                        issued_authorization_version, client_type, client_version,
                        issued_at, last_seen_at, idle_expires_at,
                        absolute_expires_at, revoked_at, revoke_reason, purge_after
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000302',
                        '00000000-0000-4000-8000-000000000001',
                        '00000000-0000-4000-8000-000000000102', 'REVOKED',
                        'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                        'current-v1',
                        'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                        'current-v1', 3, 'WEB', 'restore-test',
                        ?, ?, ?, ?, ?, 'AUTHORIZATION_CHANGED', ?
                    )
                    """)) {
                insertSession.setObject(1, observedAt);
                insertSession.setObject(2, observedAt.plusHours(1));
                insertSession.setObject(3, observedAt.plusHours(8));
                insertSession.setObject(4, observedAt.plusDays(7));
                insertSession.setObject(5, observedAt.plusHours(2));
                insertSession.setObject(6, observedAt.plusDays(8));
                assertThat(insertSession.executeUpdate()).isOne();
            }
            try (PreparedStatement insertRun = connection.prepareStatement("""
                    INSERT INTO yumpoo.directory_sync_run (
                        id, company_id, trigger_type, triggered_by_user_id,
                        trigger_key_hash, phase, status, cursor_termination_mode,
                        page_count, member_set_hash, page_trajectory_hash, scan_complete,
                        discovered_count, staged_count, unchanged_count,
                        request_id, row_version, started_at, finished_at, created_at, updated_at
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000402',
                        '00000000-0000-4000-8000-000000000001',
                        'MANUAL', '00000000-0000-4000-8000-000000000102',
                        'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                        'COMPLETED', 'SUCCEEDED', 'EXPLICIT_EMPTY',
                        1,
                        'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                        'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                        true, 1, 1, 1, 'm104-backup-restore', 4, ?, ?, ?, ?
                    )
                    """)) {
                insertRun.setObject(1, observedAt);
                insertRun.setObject(2, observedAt.plusMinutes(1));
                insertRun.setObject(3, observedAt);
                insertRun.setObject(4, observedAt.plusMinutes(1));
                assertThat(insertRun.executeUpdate()).isOne();
            }
            try (PreparedStatement insertItem = connection.prepareStatement("""
                    INSERT INTO yumpoo.directory_sync_item (
                        run_id, external_user_id, profile_hash, user_id,
                        action, result, created_at, updated_at
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000402',
                        'M1-02-Restore-Member',
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        '00000000-0000-4000-8000-000000000102',
                        'PROVISION', 'UNCHANGED', ?, ?
                    )
                    """)) {
                insertItem.setObject(1, observedAt);
                insertItem.setObject(2, observedAt.plusMinutes(1));
                assertThat(insertItem.executeUpdate()).isOne();
            }
            connection.commit();
        }
    }

    private static IdentityBindingFact readIdentityBindingFact(
            PostgreSQLContainer container
    ) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT
                         identity_user.id,
                         identity_user.company_id,
                         identity_user.employment_status,
                         identity_user.account_status,
                         identity_user.display_name,
                         identity_user.authorization_version,
                         identity_user.row_version,
                         external_identity.id AS identity_id,
                         external_identity.provider,
                         external_identity.external_user_id,
                         external_identity.raw_profile_hash
                     FROM yumpoo.identity_user identity_user
                     JOIN yumpoo.external_identity external_identity
                       ON external_identity.user_id = identity_user.id
                      AND external_identity.company_id = identity_user.company_id
                     WHERE identity_user.id = '00000000-0000-4000-8000-000000000102'
                     """)) {
            assertThat(result.next()).isTrue();
            IdentityBindingFact fact = new IdentityBindingFact(
                    result.getObject("id", UUID.class),
                    result.getObject("company_id", UUID.class),
                    result.getString("employment_status"),
                    result.getString("account_status"),
                    result.getString("display_name"),
                    result.getLong("authorization_version"),
                    result.getLong("row_version"),
                    result.getObject("identity_id", UUID.class),
                    result.getString("provider"),
                    result.getString("external_user_id"),
                    result.getString("raw_profile_hash")
            );
            assertThat(result.next()).isFalse();
            return fact;
        }
    }

    private static SessionSecurityFact readSessionSecurityFact(
            PostgreSQLContainer container
    ) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT status, session_token_fingerprint, session_key_version,
                            csrf_token_fingerprint, csrf_key_version,
                            issued_authorization_version, revoke_reason, purge_after
                     FROM yumpoo.login_session
                     WHERE id = '00000000-0000-4000-8000-000000000302'
                     """)) {
            assertThat(result.next()).isTrue();
            SessionSecurityFact fact = new SessionSecurityFact(
                    result.getString("status"),
                    result.getString("session_token_fingerprint"),
                    result.getString("session_key_version"),
                    result.getString("csrf_token_fingerprint"),
                    result.getString("csrf_key_version"),
                    result.getLong("issued_authorization_version"),
                    result.getString("revoke_reason"),
                    result.getObject("purge_after", OffsetDateTime.class)
            );
            assertThat(result.next()).isFalse();
            return fact;
        }
    }

    private static DirectorySyncFact readDirectorySyncFact(
            PostgreSQLContainer container
    ) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT run.status, run.cursor_termination_mode,
                            run.discovered_count, run.unchanged_count,
                            item.external_user_id, item.result, item.profile_hash
                     FROM yumpoo.directory_sync_run run
                     JOIN yumpoo.directory_sync_item item ON item.run_id = run.id
                     WHERE run.id = '00000000-0000-4000-8000-000000000402'
                     """)) {
            assertThat(result.next()).isTrue();
            DirectorySyncFact fact = new DirectorySyncFact(
                    result.getString("status"),
                    result.getString("cursor_termination_mode"),
                    result.getInt("discovered_count"),
                    result.getInt("unchanged_count"),
                    result.getString("external_user_id"),
                    result.getString("result"),
                    result.getString("profile_hash")
            );
            assertThat(result.next()).isFalse();
            return fact;
        }
    }

    private static String postgresVersion(PostgreSQLContainer container) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SHOW server_version")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static int countSyntheticReferences(PostgreSQLContainer container) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM yumpoo.m017_backup_reference")) {
            result.next();
            return result.getInt(1);
        }
    }

    private record CompanyCalendarFact(
            String timezone,
            String weekStartDay,
            int defaultWorkdayMinutes,
            LocalDate calendarDate,
            String dayType,
            int standardMinutes,
            long rowVersion
    ) {
    }

    private record IdentityBindingFact(
            UUID userId,
            UUID companyId,
            String employmentStatus,
            String accountStatus,
            String displayName,
            long authorizationVersion,
            long rowVersion,
            UUID identityId,
            String provider,
            String externalUserId,
            String rawProfileHash
    ) {
    }

    private record SessionSecurityFact(
            String status,
            String sessionFingerprint,
            String sessionKeyVersion,
            String csrfFingerprint,
            String csrfKeyVersion,
            long issuedAuthorizationVersion,
            String revokeReason,
            OffsetDateTime purgeAfter
    ) {
    }

    private record DirectorySyncFact(
            String status,
            String terminationMode,
            int discoveredCount,
            int unchangedCount,
            String externalUserId,
            String result,
            String profileHash
    ) {
    }

    private static List<PublishedBlob> readSyntheticReferences(PostgreSQLContainer container) throws SQLException {
        List<PublishedBlob> records = new ArrayList<>();
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT storage_key, size_bytes, sha256
                     FROM yumpoo.m017_backup_reference ORDER BY business_key
                     """)) {
            while (result.next()) {
                records.add(new PublishedBlob(
                        result.getString("storage_key"),
                        result.getLong("size_bytes"),
                        result.getString("sha256")
                ));
            }
        }
        return List.copyOf(records);
    }

    private static Connection connection(PostgreSQLContainer container) throws SQLException {
        return DriverManager.getConnection(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword()
        );
    }

    private static List<String> orphanKeys(Path blobRoot, List<PublishedBlob> references) throws IOException {
        Set<String> referenced = new HashSet<>();
        references.forEach(blob -> referenced.add(blob.storageKey()));
        try (Stream<Path> files = Files.walk(blobRoot)) {
            return files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(blobRoot::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .filter(path -> !referenced.contains(path))
                    .sorted()
                    .toList();
        }
    }

    private static Path writeConfiguration(Path workRoot) throws IOException {
        Path file = workRoot.resolve("application-restore.yml");
        Files.writeString(
                file,
                "server:\n  address: 127.0.0.1\nspring:\n  datasource:\n    url: ${SPRING_DATASOURCE_URL}\n",
                StandardCharsets.UTF_8
        );
        return file;
    }

    private static Path writeRestoreDescriptor(Path workRoot) throws IOException {
        Path file = workRoot.resolve("secret-recovery.json");
        M017BackupSet.json().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), Map.of(
                "schemaVersion", 1,
                "secretValuesIncluded", false,
                "requiredSecretVariables", List.of(
                        "SPRING_DATASOURCE_URL",
                        "SPRING_DATASOURCE_USERNAME",
                        "SPRING_DATASOURCE_PASSWORD"
                ),
                "recoveryProcess", "Retrieve approved values from the external secret store after restore"
        ));
        return file;
    }

    private static M017RetentionPlanner.RetentionPlan sampleRetentionPlan(Instant startedAt) {
        List<M017RetentionPlanner.Candidate> candidates = new ArrayList<>();
        for (int day = 0; day < 220; day++) {
            Instant verifiedAt = startedAt.minusSeconds(day * 86_400L);
            candidates.add(new M017RetentionPlanner.Candidate(
                    UUID.nameUUIDFromBytes(verifiedAt.toString().getBytes(StandardCharsets.UTF_8)),
                    verifiedAt,
                    true,
                    day == 30
            ));
        }
        return M017RetentionPlanner.plan(candidates, ZoneId.of("Asia/Shanghai"));
    }

    private static void writeVerificationReport(
            Path outputRoot,
            Instant startedAt,
            String sourceCommit,
            M017BackupManifest manifest,
            int restoredReferenceCount,
            int orphanCount
    ) throws IOException {
        Map<String, Object> report = Map.of(
                "schemaVersion", 1,
                "milestone", "M0-17",
                "status", "PASS",
                "startedAt", startedAt,
                "completedAt", Instant.now(),
                "sourceCommit", sourceCommit,
                "backupSetId", manifest.backupSetId(),
                "restoredReferenceCount", restoredReferenceCount,
                "orphanCount", orphanCount,
                "checks", Map.ofEntries(
                        Map.entry("postgresCustomDumpCreated", true),
                        Map.entry("manifestExactCoverageVerified", true),
                        Map.entry("payloadHashesVerified", true),
                        Map.entry("secretValuesExcluded", true),
                        Map.entry("emptyTargetsRequired", true),
                        Map.entry("isolatedDatabaseRestored", true),
                        Map.entry("flywayVersionMatched", true),
                        Map.entry("attachmentReferencesMatched", true),
                        Map.entry("attachmentBytesReadable", true),
                        Map.entry("orphansReportedWithoutDeletion", true),
                        Map.entry("retentionDryRunGenerated", true)
                )
        );
        M017BackupSet.json().writerWithDefaultPrettyPrinter()
                .writeValue(outputRoot.resolve("verification-report.json").toFile(), report);
    }

    private static String gitHead(Path repositoryRoot) throws Exception {
        Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(repositoryRoot.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.waitFor()).isZero();
        assertThat(output).matches("[0-9a-f]{40}");
        return output;
    }

    private static void requireSuccess(Container.ExecResult result, String command) {
        assertThat(result.getExitCode())
                .withFailMessage("%s failed: %s", command, result.getStderr())
                .isZero();
        assertThat(result.getStderr()).isBlank();
    }
}
