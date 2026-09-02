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
            createWorkspaceFact(source);
            createProductGovernanceFact(source);
            createProjectContentFact(source);
            createAttachmentFact(source, first);
            createActivityFact(source);

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
            assertThat(readProjectTemplateCatalogFact(target))
                    .isEqualTo(readProjectTemplateCatalogFact(source));
            assertThat(readWorkspaceFact(target)).isEqualTo(readWorkspaceFact(source));
            assertThat(readProductGovernanceFact(target))
                    .isEqualTo(readProductGovernanceFact(source));
            assertThat(readProjectContentFact(target))
                    .isEqualTo(readProjectContentFact(source));
            assertThat(readWorkItemStatusEventFact(target))
                    .isEqualTo(readWorkItemStatusEventFact(source));
            assertThat(readWorkItemUpdateFact(target))
                    .isEqualTo(readWorkItemUpdateFact(source));
            assertThat(readAdminOverrideFact(target))
                    .isEqualTo(readAdminOverrideFact(source));
            assertThat(readAttachmentFact(target)).isEqualTo(readAttachmentFact(source));
            assertThat(readActivityFact(target)).isEqualTo(readActivityFact(source));

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

    private static void createActivityFact(PostgreSQLContainer container) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO yumpoo.activity_event (
                        id,event_id,projection_code,company_id,scope_type,scope_id,
                        entity_type,entity_id,entity_ref,event_type,actor_type,actor_user_id,
                        actor_display_name,occurred_at,template_code,safe_parameters,
                        entity_version,request_id,correlation_id,primary_work_item_id
                    ) VALUES (
                        '44000000-0000-4000-8000-000000000020',
                        '44000000-0000-4000-8000-000000000021','ACTIVITY_V1',
                        '00000000-0000-4000-8000-000000000001','PROJECT',
                        '00000000-0000-4000-8000-000000000802','WORK_ITEM',
                        '00000000-0000-4000-8000-000000000816','M2-20 RESTORE-20',
                        'workitem.work_item_created','USER',
                        '00000000-0000-4000-8000-000000000102','Restore Member',
                        '2026-08-30T08:20:00Z','WORK_ITEM_CREATED',
                        '{"entityRef":"M2-20 RESTORE-20"}'::jsonb,1,
                        'm2-20-backup-restore','m2-20-backup-restore',
                        '00000000-0000-4000-8000-000000000816'
                    )
                    """);
        }
    }

    private static String readActivityFact(PostgreSQLContainer container) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT event_id || ':' || projection_code || ':' || scope_id || ':'
                            || entity_type || ':' || entity_id || ':' || entity_ref || ':'
                            || event_type || ':' || actor_display_name || ':' || template_code
                            || ':' || safe_parameters::text || ':' || primary_work_item_id AS fact
                     FROM yumpoo.activity_event
                     WHERE id = '44000000-0000-4000-8000-000000000020'
                     """)) {
            assertThat(result.next()).isTrue();
            String fact = result.getString("fact");
            assertThat(result.next()).isFalse();
            return fact;
        }
    }

    private static void createAttachmentFact(PostgreSQLContainer container, PublishedBlob blob)
            throws SQLException {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-25T03:00:00Z");
        try (Connection connection = connection(container)) {
            connection.setAutoCommit(false);
            try (PreparedStatement registry = connection.prepareStatement("""
                    INSERT INTO yumpoo.attachment_blob (
                        storage_key,sha256,size_bytes,presence_status,last_verified_at,created_at,updated_at
                    ) VALUES (?, ?, ?, 'PRESENT', ?, ?, ?)
                    """)) {
                registry.setString(1,blob.storageKey());
                registry.setString(2,blob.sha256());
                registry.setLong(3,blob.sizeBytes());
                registry.setObject(4,now);
                registry.setObject(5,now.minusMinutes(3));
                registry.setObject(6,now);
                assertThat(registry.executeUpdate()).isOne();
            }
            try (PreparedStatement attachment = connection.prepareStatement("""
                    INSERT INTO yumpoo.attachment (
                        id,company_id,quota_project_id,owner_type,owner_id,original_file_name,
                        file_extension,declared_mime,detected_mime,size_bytes,sha256,storage_key,
                        status,reserved_bytes,uploaded_by_user_id,intent_expires_at,sealed_at,
                        available_at,scan_generation,row_version,created_at,updated_at
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000819',
                        '00000000-0000-4000-8000-000000000001',
                        '00000000-0000-4000-8000-000000000802','WORK_ITEM',
                        '00000000-0000-4000-8000-000000000816','M2-18 restore evidence.txt',
                        'txt','text/plain','text/plain',?,?,?,'AVAILABLE',0,
                        '00000000-0000-4000-8000-000000000102',?,?,?,1,3,?,?
                    )
                    """)) {
                attachment.setLong(1, blob.sizeBytes());
                attachment.setString(2, blob.sha256());
                attachment.setString(3, blob.storageKey());
                attachment.setObject(4, now.plusDays(1));
                attachment.setObject(5, now.minusMinutes(2));
                attachment.setObject(6, now);
                attachment.setObject(7, now.minusMinutes(3));
                attachment.setObject(8, now);
                assertThat(attachment.executeUpdate()).isOne();
            }
            try (PreparedStatement task = connection.prepareStatement("""
                    INSERT INTO yumpoo.attachment_scan_task (
                        id,attachment_id,company_id,generation,status,attempt_count,
                        next_attempt_at,final_result,created_at,updated_at
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000820',
                        '00000000-0000-4000-8000-000000000819',
                        '00000000-0000-4000-8000-000000000001',1,'COMPLETED',1,?,
                        'AVAILABLE',?,?
                    )
                    """)) {
                task.setObject(1, now.minusMinutes(2));
                task.setObject(2, now.minusMinutes(2));
                task.setObject(3, now);
                assertThat(task.executeUpdate()).isOne();
            }
            try (PreparedStatement quota = connection.prepareStatement("""
                    INSERT INTO yumpoo.attachment_quota_usage (
                        company_id,scope_type,scope_id,reserved_bytes,available_bytes,row_version,updated_at
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000001','COMPANY',
                        '00000000-0000-4000-8000-000000000001',0,?,1,?
                    ),(
                        '00000000-0000-4000-8000-000000000001','PROJECT',
                        '00000000-0000-4000-8000-000000000802',0,?,1,?
                    )
                    """)) {
                quota.setLong(1, blob.sizeBytes());
                quota.setObject(2, now);
                quota.setLong(3, blob.sizeBytes());
                quota.setObject(4, now);
                assertThat(quota.executeUpdate()).isEqualTo(2);
            }
            connection.commit();
        }
    }

    private static String readAttachmentFact(PostgreSQLContainer container) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT concat_ws(':', attachment.owner_type, attachment.owner_id,
                                attachment.original_file_name, attachment.detected_mime,
                                attachment.size_bytes, attachment.status, attachment.storage_key,
                                attachment.row_version, task.status, task.attempt_count,
                                task.final_result, blob.presence_status, blob.size_bytes,
                                (SELECT string_agg(scope_type || '=' || reserved_bytes || '/'
                                    || available_bytes, ',' ORDER BY scope_type)
                                   FROM yumpoo.attachment_quota_usage
                                  WHERE company_id=attachment.company_id)) AS fact
                       FROM yumpoo.attachment attachment
                       JOIN yumpoo.attachment_scan_task task
                         ON task.attachment_id=attachment.id AND task.generation=attachment.scan_generation
                       JOIN yumpoo.attachment_blob blob ON blob.storage_key=attachment.storage_key
                      WHERE attachment.id='00000000-0000-4000-8000-000000000819'
                     """)) {
            assertThat(result.next()).isTrue();
            String fact = result.getString("fact");
            assertThat(result.next()).isFalse();
            return fact;
        }
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

    private static String readProjectTemplateCatalogFact(PostgreSQLContainer container) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT string_agg(fact, E'\n' ORDER BY fact)
                     FROM (
                         SELECT 'T|' || template_key || '|' || template_version || '|'
                                || version_code || '|' || project_type || '|' || lifecycle_status AS fact
                           FROM yumpoo.project_template_definition
                         UNION ALL
                         SELECT 'B|' || template.version_code || '|' || blueprint.content_code || '|'
                                || blueprint.display_name || '|' || blueprint.color_token || '|'
                                || blueprint.sort_order
                           FROM yumpoo.project_template_content_blueprint blueprint
                           JOIN yumpoo.project_template_definition template ON template.id = blueprint.template_id
                         UNION ALL
                         SELECT 'S|' || template.version_code || '|' || status.status_code || '|'
                                || status.status_category || '|' || status.sort_order || '|'
                                || status.is_initial || '|' || status.is_terminal
                           FROM yumpoo.workflow_status_definition status
                           JOIN yumpoo.project_template_definition template ON template.id = status.template_id
                         UNION ALL
                         SELECT 'E|' || template.version_code || '|' || transition.from_status || '|'
                                || transition.to_status || '|' || transition.required_permission || '|'
                                || transition.requires_resolution
                           FROM yumpoo.workflow_transition_definition transition
                           JOIN yumpoo.project_template_definition template ON template.id = transition.template_id
                     ) catalog
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
            try (PreparedStatement user = connection.prepareStatement("""
                    INSERT INTO yumpoo.identity_user (
                        id, company_id, employment_status, account_status, display_name,
                        directory_synced_at, authorization_version, row_version, created_at, updated_at
                    ) VALUES ('00000000-0000-4000-8000-000000000103',
                        '00000000-0000-4000-8000-000000000001','ACTIVE','ENABLED',
                        'M2-05 Removed Restore User',?,0,0,?,?)
                    """)) {
                user.setObject(1, observedAt); user.setObject(2, observedAt); user.setObject(3, observedAt);
                assertThat(user.executeUpdate()).isOne();
            }
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

    private static void createWorkspaceFact(PostgreSQLContainer container) throws SQLException {
        try (Connection connection = connection(container);
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE yumpoo.workspace
                        SET id='00000000-0000-4000-8000-000000000502',
                            name='M2-02 MAIN Restore Workspace',
                            description='Singleton MAIN backup restore probe',
                            row_version=3,
                            created_at=?,
                            created_by_user_id='00000000-0000-4000-8000-000000000102',
                            updated_at=?,
                            updated_by_user_id='00000000-0000-4000-8000-000000000102'
                      WHERE company_id='00000000-0000-4000-8000-000000000001'
                        AND code='MAIN' AND sort_order=0 AND status='ACTIVE'
                     """)) {
            OffsetDateTime observedAt = OffsetDateTime.ofInstant(
                    Instant.parse("2026-08-20T05:00:00Z"), ZoneOffset.UTC);
            update.setObject(1, observedAt);
            update.setObject(2, observedAt.plusHours(1));
            assertThat(update.executeUpdate()).isOne();
        }
    }

    private static WorkspaceFact readWorkspaceFact(PostgreSQLContainer container) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT id, company_id, code, name, description, sort_order,
                            status, row_version, created_by_user_id, updated_by_user_id
                     FROM yumpoo.workspace
                     WHERE id = '00000000-0000-4000-8000-000000000502'
                     """)) {
            assertThat(result.next()).isTrue();
            WorkspaceFact fact = new WorkspaceFact(
                    result.getObject("id", UUID.class),
                    result.getObject("company_id", UUID.class),
                    result.getString("code"),
                    result.getString("name"),
                    result.getString("description"),
                    result.getInt("sort_order"),
                    result.getString("status"),
                    result.getLong("row_version"),
                    result.getObject("created_by_user_id", UUID.class),
                    result.getObject("updated_by_user_id", UUID.class)
            );
            assertThat(result.next()).isFalse();
            return fact;
        }
    }

    private static void createProductGovernanceFact(PostgreSQLContainer container) throws SQLException {
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(
                Instant.parse("2026-08-20T06:00:00Z"), ZoneOffset.UTC);
        try (Connection connection = connection(container)) {
            connection.setAutoCommit(false);
            try (PreparedStatement product = connection.prepareStatement("""
                    INSERT INTO yumpoo.product (
                        id, company_id, product_code, name, description, status, owner_user_id,
                        row_version, created_at, created_by_user_id, updated_at, updated_by_user_id,
                        archived_at, archived_by_user_id
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000602',
                        '00000000-0000-4000-8000-000000000001',
                        'M2_03_RESTORE', 'M2-03 Restore Product', 'Product lifecycle restore probe',
                        'ARCHIVED', '00000000-0000-4000-8000-000000000102', 3, ?,
                        '00000000-0000-4000-8000-000000000102', ?,
                        '00000000-0000-4000-8000-000000000102', ?,
                        '00000000-0000-4000-8000-000000000102'
                    )
                    """)) {
                product.setObject(1, createdAt);
                product.setObject(2, createdAt.plusHours(2));
                product.setObject(3, createdAt.plusHours(2));
                assertThat(product.executeUpdate()).isOne();
            }
            try (PreparedStatement issue = connection.prepareStatement("""
                    INSERT INTO yumpoo.governance_issue (
                        id, company_id, issue_type, target_type, target_id, status,
                        safe_summary_code, detected_event_id, detected_at,
                        resolved_event_id, resolved_at, resolution_code,
                        row_version, created_at, updated_at
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000702',
                        '00000000-0000-4000-8000-000000000001',
                        'OWNER_MISSING', 'PRODUCT',
                        '00000000-0000-4000-8000-000000000602', 'RESOLVED',
                        'PRODUCT_OWNER_MISSING',
                        '00000000-0000-4000-8000-000000000703', ?,
                        '00000000-0000-4000-8000-000000000704', ?,
                        'PRODUCT_OWNER_GOVERNED', 1, ?, ?
                    )
                    """)) {
                issue.setObject(1, createdAt.plusHours(1));
                issue.setObject(2, createdAt.plusHours(2));
                issue.setObject(3, createdAt.plusHours(1));
                issue.setObject(4, createdAt.plusHours(2));
                assertThat(issue.executeUpdate()).isOne();
            }
            connection.commit();
        }
    }

    private static ProductGovernanceFact readProductGovernanceFact(
            PostgreSQLContainer container
    ) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT product.id, product.product_code, product.status,
                            product.owner_user_id, product.row_version,
                            issue.issue_type, issue.target_type, issue.target_id,
                            issue.status AS issue_status, issue.resolution_code
                     FROM yumpoo.product product
                     JOIN yumpoo.governance_issue issue ON issue.target_id = product.id
                     WHERE product.id = '00000000-0000-4000-8000-000000000602'
                     """)) {
            assertThat(result.next()).isTrue();
            ProductGovernanceFact fact = new ProductGovernanceFact(
                    result.getObject("id", UUID.class), result.getString("product_code"),
                    result.getString("status"), result.getObject("owner_user_id", UUID.class),
                    result.getLong("row_version"), result.getString("issue_type"),
                    result.getString("target_type"), result.getObject("target_id", UUID.class),
                    result.getString("issue_status"), result.getString("resolution_code"));
            assertThat(result.next()).isFalse();
            return fact;
        }
    }

    private static void createProjectContentFact(PostgreSQLContainer container) throws SQLException {
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(
                Instant.parse("2026-08-20T07:00:00Z"), ZoneOffset.UTC);
        try (Connection connection = connection(container)) {
            connection.setAutoCommit(false);
            try (PreparedStatement project = connection.prepareStatement("""
                    INSERT INTO yumpoo.project (
                        id, company_id, workspace_id, project_code, name, description,
                        project_type, lifecycle, owner_user_id, template_key, template_version,
                        customer_name, customer_reference, delivery_site, contact_note,
                        row_version, created_at, created_by_user_id, updated_at, updated_by_user_id,
                        activated_at, archived_at
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000802',
                        '00000000-0000-4000-8000-000000000001',
                        '00000000-0000-4000-8000-000000000502',
                        'M2_04_RESTORE', 'M2-04 Restore Project', 'Project restore probe',
                        'PRODUCT_DEVELOPMENT', 'ARCHIVED',
                        '00000000-0000-4000-8000-000000000102', 'RND', 1,
                        'Restore Customer', 'RESTORE-01', 'Shanghai', 'Private restore note',
                        4, ?, '00000000-0000-4000-8000-000000000102', ?,
                        '00000000-0000-4000-8000-000000000102', ?, ?
                    )
                    """)) {
                project.setObject(1, createdAt);
                project.setObject(2, createdAt.plusHours(3));
                project.setObject(3, createdAt.plusHours(2));
                project.setObject(4, createdAt.plusHours(3));
                assertThat(project.executeUpdate()).isOne();
            }
            try (PreparedStatement membership = connection.prepareStatement("""
                    INSERT INTO yumpoo.project_membership (
                        id, company_id, project_id, user_id, status, joined_at,
                        joined_by_user_id, row_version
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000803',
                        '00000000-0000-4000-8000-000000000001',
                        '00000000-0000-4000-8000-000000000802',
                        '00000000-0000-4000-8000-000000000102',
                        'ACTIVE', ?, '00000000-0000-4000-8000-000000000102', 0
                    )
                    """)) {
                membership.setObject(1, createdAt);
                assertThat(membership.executeUpdate()).isOne();
            }
            try (Statement labels = connection.createStatement()) {
                assertThat(labels.executeUpdate("""
                        INSERT INTO yumpoo.project_work_item_label_catalog
                            (project_id, company_id, row_version, created_at, updated_at)
                        VALUES ('00000000-0000-4000-8000-000000000802',
                            '00000000-0000-4000-8000-000000000001', 2,
                            transaction_timestamp(), transaction_timestamp())
                        """)).isOne();
                assertThat(labels.executeUpdate("""
                        INSERT INTO yumpoo.project_work_item_status_label (
                            project_id, company_id, status_code, display_name, color_token,
                            status_category, sort_order, active, protected_label)
                        VALUES ('00000000-0000-4000-8000-000000000802',
                            '00000000-0000-4000-8000-000000000001',
                            'NOT_STARTED', '未开始', 'GRAY', 'TODO', 0, true, true)
                        """)).isOne();
                assertThat(labels.executeUpdate("""
                        INSERT INTO yumpoo.project_work_item_status_label (
                            project_id, company_id, status_code, display_name, color_token,
                            status_category, sort_order, active, protected_label)
                        SELECT '00000000-0000-4000-8000-000000000802',
                               '00000000-0000-4000-8000-000000000001',
                               status.status_code, status.display_name,
                               CASE status.status_category WHEN 'DONE' THEN 'GREEN'
                                   WHEN 'CANCELED' THEN 'GRAY'
                                   WHEN 'IN_PROGRESS' THEN 'ORANGE' ELSE 'BLUE' END,
                               status.status_category, status.sort_order + 100, true, false
                          FROM yumpoo.project_template_definition template
                          JOIN yumpoo.workflow_status_definition status
                            ON status.template_id=template.id
                         WHERE template.template_key='RND' AND template.template_version=1
                           AND status.status_code <> 'NOT_STARTED'
                        """)).isPositive();
                assertThat(labels.executeUpdate("""
                        INSERT INTO yumpoo.project_work_item_priority_label (
                            project_id, company_id, priority_code, display_name, color_token,
                            sort_order, active)
                        SELECT '00000000-0000-4000-8000-000000000802',
                               '00000000-0000-4000-8000-000000000001', seed.code,
                               seed.name, seed.color, seed.sort_order, true
                          FROM (VALUES ('LOW', '低', 'BLUE', 10),
                               ('MEDIUM', '中', 'TEAL', 20),
                               ('HIGH', '高', 'ORANGE', 30),
                               ('URGENT', '紧急', 'RED', 40))
                               AS seed(code, name, color, sort_order)
                        """)).isEqualTo(4);
                assertThat(labels.executeUpdate("""
                        INSERT INTO yumpoo.work_item_project_order (project_id, company_id)
                        VALUES ('00000000-0000-4000-8000-000000000802',
                            '00000000-0000-4000-8000-000000000001')
                        """)).isOne();
                assertThat(labels.executeUpdate("""
                        INSERT INTO yumpoo.content_catalog_version
                            (project_id, company_id, row_version, updated_at)
                        VALUES ('00000000-0000-4000-8000-000000000802',
                            '00000000-0000-4000-8000-000000000001', 3,
                            transaction_timestamp())
                        """)).isOne();
            }
            try (PreparedStatement membership = connection.prepareStatement("""
                    INSERT INTO yumpoo.project_membership (
                        id, company_id, project_id, user_id, status, joined_at, joined_by_user_id,
                        removed_at, removed_by_user_id, remove_reason, row_version
                    ) VALUES ('00000000-0000-4000-8000-000000000807',
                        '00000000-0000-4000-8000-000000000001',
                        '00000000-0000-4000-8000-000000000802',
                        '00000000-0000-4000-8000-000000000103','REMOVED',?,
                        '00000000-0000-4000-8000-000000000102',?,
                        '00000000-0000-4000-8000-000000000102',NULL,7)
                    """)) {
                membership.setObject(1, createdAt);
                membership.setObject(2, createdAt.plusHours(1));
                assertThat(membership.executeUpdate()).isOne();
            }
            try (PreparedStatement issue = connection.prepareStatement("""
                    INSERT INTO yumpoo.governance_issue (
                        id,company_id,issue_type,target_type,target_id,status,safe_summary_code,
                        detected_event_id,detected_at,row_version,created_at,updated_at)
                    VALUES ('00000000-0000-4000-8000-000000000808',
                        '00000000-0000-4000-8000-000000000001','OWNER_MISSING','PROJECT',
                        '00000000-0000-4000-8000-000000000802','OPEN','PROJECT_OWNER_MISSING',
                        '00000000-0000-4000-8000-000000000809',?,2,?,?)
                    """)) {
                issue.setObject(1,createdAt); issue.setObject(2,createdAt); issue.setObject(3,createdAt);
                assertThat(issue.executeUpdate()).isOne();
            }
            try (PreparedStatement link = connection.prepareStatement("""
                    INSERT INTO yumpoo.project_product_link (
                        id,company_id,project_id,product_id,relation_type,is_primary,
                        linked_at,linked_by_user_id,updated_at,updated_by_user_id,
                        removed_at,removed_by_user_id,remove_reason,row_version
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000810',
                        '00000000-0000-4000-8000-000000000001',
                        '00000000-0000-4000-8000-000000000802',
                        '00000000-0000-4000-8000-000000000602','DEVELOPMENT',true,
                        ?,'00000000-0000-4000-8000-000000000102',?,
                        '00000000-0000-4000-8000-000000000102',NULL,NULL,NULL,0),(
                        '00000000-0000-4000-8000-000000000811',
                        '00000000-0000-4000-8000-000000000001',
                        '00000000-0000-4000-8000-000000000802',
                        '00000000-0000-4000-8000-000000000602','SUPPORT',false,
                        ?,'00000000-0000-4000-8000-000000000102',?,
                        '00000000-0000-4000-8000-000000000102',?,
                        '00000000-0000-4000-8000-000000000102','历史支持关系结束',4)
                    """)) {
                link.setObject(1, createdAt);
                link.setObject(2, createdAt.plusHours(1));
                link.setObject(3, createdAt);
                link.setObject(4, createdAt.plusHours(2));
                link.setObject(5, createdAt.plusHours(2));
                assertThat(link.executeUpdate()).isEqualTo(2);
            }
            try (PreparedStatement content = connection.prepareStatement("""
                    INSERT INTO yumpoo.content (
                        id, company_id, project_id, code, name, color_token, sort_order,
                        active, protected_content, ever_used, row_version,
                        created_at, created_by_user_id, updated_at, updated_by_user_id
                    ) VALUES (?,
                        '00000000-0000-4000-8000-000000000001',
                        '00000000-0000-4000-8000-000000000802', ?, ?, ?, ?, true, ?, ?, 0, ?,
                        '00000000-0000-4000-8000-000000000102', ?,
                        '00000000-0000-4000-8000-000000000102')
                    """)) {
                String[][] blueprints = {
                        {"00000000-0000-4000-8000-000000000804", "REQUIREMENTS", "需求", "BRIGHT_BLUE", "10", "false"},
                        {"00000000-0000-4000-8000-000000000805", "TASKS", "任务", "BRIGHT_GREEN", "20", "true"},
                        {"00000000-0000-4000-8000-000000000806", "DEFECTS", "缺陷", "DARK_RED", "30", "false"}
                };
                for (String[] blueprint : blueprints) {
                    content.setObject(1, UUID.fromString(blueprint[0]));
                    content.setString(2, blueprint[1]);
                    content.setString(3, blueprint[2]);
                    content.setString(4, blueprint[3]);
                    content.setInt(5, Integer.parseInt(blueprint[4]));
                    content.setBoolean(6, true);
                    content.setBoolean(7, Boolean.parseBoolean(blueprint[5]));
                    content.setObject(8, createdAt);
                    content.setObject(9, createdAt);
                    content.addBatch();
                }
                assertThat(content.executeBatch()).hasSize(3);
            }
            try (PreparedStatement archivedContent = connection.prepareStatement("""
                    INSERT INTO yumpoo.content (
                        id, company_id, project_id, code, name, color_token, sort_order,
                        active, protected_content, ever_used, row_version,
                        created_at, created_by_user_id, updated_at, updated_by_user_id,
                        deleted_at, deleted_by_user_id
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000814',
                        '00000000-0000-4000-8000-000000000001',
                        '00000000-0000-4000-8000-000000000802',
                        'LEGACY_REQUIREMENT', '停用历史类别', 'SKY', 40,
                        false, false, false, 2, ?,
                        '00000000-0000-4000-8000-000000000102', ?,
                        '00000000-0000-4000-8000-000000000102', NULL, NULL
                    )
                    """)) {
                archivedContent.setObject(1, createdAt);
                archivedContent.setObject(2, createdAt.plusHours(2));
                assertThat(archivedContent.executeUpdate()).isOne();
            }
            try (PreparedStatement counter = connection.prepareStatement("""
                    INSERT INTO yumpoo.work_item_project_counter (project_id, company_id, last_sequence)
                    VALUES ('00000000-0000-4000-8000-000000000802',
                        '00000000-0000-4000-8000-000000000001', 2)
                    """)) {
                assertThat(counter.executeUpdate()).isOne();
            }
            try (PreparedStatement lanes = connection.prepareStatement("""
                    INSERT INTO yumpoo.work_item_rank_lane (project_id, company_id, status_code)
                    VALUES
                        ('00000000-0000-4000-8000-000000000802',
                         '00000000-0000-4000-8000-000000000001', 'BACKLOG'),
                        ('00000000-0000-4000-8000-000000000802',
                         '00000000-0000-4000-8000-000000000001', 'DONE')
                    """)) {
                assertThat(lanes.executeUpdate()).isEqualTo(2);
            }
            try (PreparedStatement workItem = connection.prepareStatement("""
                    INSERT INTO yumpoo.work_item (
                        id, company_id, project_id, content_id, item_sequence, item_no,
                        title, status_code, status_category, rank, project_sort_key,
                        priority, assignee_user_id,
                        reporter_user_id, description, notes, timeline_start_date,
                        timeline_end_date, due_date, row_version, created_at,
                        created_by_user_id, updated_at, updated_by_user_id
                    ) VALUES (?, '00000000-0000-4000-8000-000000000001',
                        '00000000-0000-4000-8000-000000000802',
                        '00000000-0000-4000-8000-000000000805', ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, '00000000-0000-4000-8000-000000000102', ?, ?, ?, ?, ?, ?, ?,
                        '00000000-0000-4000-8000-000000000102', ?,
                        '00000000-0000-4000-8000-000000000102')
                    """)) {
                Object[][] facts = {
                        {UUID.fromString("00000000-0000-4000-8000-000000000815"), 1L,
                                "M2_04_RESTORE-1", "恢复工作项一", "BACKLOG", "TODO",
                                "500000000000000000000000000000000000000", "MEDIUM",
                                "333333333333333333333333333333333333333",
                                UUID.fromString("00000000-0000-4000-8000-000000000102"),
                                "保留纯文本描述", "保留纯文本备注", LocalDate.parse("2026-08-20"),
                                LocalDate.parse("2026-08-22"), LocalDate.parse("2026-08-23"), 0L},
                        {UUID.fromString("00000000-0000-4000-8000-000000000816"), 2L,
                                "M2_04_RESTORE-2", "恢复工作项二", "DONE", "DONE",
                                "500000000000000000000000000000000000000", "HIGH",
                                "666666666666666666666666666666666666666",
                                null, "第二项描述", null, null, null,
                                LocalDate.parse("2026-08-24"), 4L}
                };
                for (Object[] fact : facts) {
                    workItem.setObject(1, fact[0]); workItem.setObject(2, fact[1]);
                    workItem.setObject(3, fact[2]); workItem.setObject(4, fact[3]);
                    workItem.setObject(5, fact[4]); workItem.setObject(6, fact[5]);
                    workItem.setObject(7, fact[6]); workItem.setObject(8, fact[8]);
                    workItem.setObject(9, fact[7]); workItem.setObject(10, fact[9]);
                    workItem.setObject(11, fact[10]); workItem.setObject(12, fact[11]);
                    workItem.setObject(13, fact[12]); workItem.setObject(14, fact[13]);
                    workItem.setObject(15, fact[14]); workItem.setObject(16, fact[15]);
                    workItem.setObject(17, createdAt); workItem.setObject(18, createdAt.plusHours(1));
                    workItem.addBatch();
                }
                assertThat(workItem.executeBatch()).hasSize(2);
            }
            try (PreparedStatement tombstone = connection.prepareStatement("""
                    UPDATE yumpoo.work_item
                       SET deleted_at=?, deleted_by_user_id='00000000-0000-4000-8000-000000000102',
                           delete_reason='备份恢复需保留删除事实'
                     WHERE id='00000000-0000-4000-8000-000000000816'
                    """)) {
                tombstone.setObject(1, createdAt.plusHours(2));
                assertThat(tombstone.executeUpdate()).isOne();
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    INSERT INTO yumpoo.work_item_update (
                        id, company_id, project_id, work_item_id,
                        author_user_id, author_display_name, body_html, body_text, status,
                        edit_deadline_at, row_version, created_at, edited_at, edited_by_user_id,
                        deleted_at, deleted_by_user_id, delete_reason
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000818',
                        '00000000-0000-4000-8000-000000000001',
                        '00000000-0000-4000-8000-000000000802',
                        '00000000-0000-4000-8000-000000000815',
                        '00000000-0000-4000-8000-000000000102', 'M2-17 Author',
                        NULL, NULL, 'DELETED', ?, 2, ?, ?,
                        '00000000-0000-4000-8000-000000000102', ?,
                        '00000000-0000-4000-8000-000000000103', '治理删除备份恢复事实'
                    )
                    """)) {
                update.setObject(1, createdAt.plusMinutes(15));
                update.setObject(2, createdAt);
                update.setObject(3, createdAt.plusMinutes(5));
                update.setObject(4, createdAt.plusHours(1));
                assertThat(update.executeUpdate()).isOne();
            }
            try (PreparedStatement mention = connection.prepareStatement("""
                    INSERT INTO yumpoo.work_item_update_mention (
                        update_id, company_id, mentioned_user_id, mentioned_display_name, created_at
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000818',
                        '00000000-0000-4000-8000-000000000001',
                        '00000000-0000-4000-8000-000000000102', 'M2-17 Author', ?
                    )
                    """)) {
                mention.setObject(1, createdAt);
                assertThat(mention.executeUpdate()).isOne();
            }
            try (PreparedStatement event = connection.prepareStatement("""
                    INSERT INTO yumpoo.outbox_event (
                        event_id, event_type, event_version, aggregate_type, aggregate_id,
                        aggregate_version, company_id, actor_type, actor_user_id, occurred_at,
                        request_id, correlation_id, payload_json, status, attempt_count,
                        next_attempt_at, created_at
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000817',
                        'workitem.work_item_status_changed', 2, 'WorkItem',
                        '00000000-0000-4000-8000-000000000816', 4,
                        '00000000-0000-4000-8000-000000000001', 'USER',
                        '00000000-0000-4000-8000-000000000102', ?,
                        'm2-12-backup-restore', 'm2-12-backup-restore',
                        ('{"workItemId":"00000000-0000-4000-8000-000000000816",'
                        || '"projectId":"00000000-0000-4000-8000-000000000802",'
                        || '"contentId":"00000000-0000-4000-8000-000000000805",'
                        || '"itemNo":"M2_04_RESTORE-2","title":"恢复工作项二",'
                        || '"contentName":"任务","contentColorToken":"BRIGHT_GREEN","fromStatus":"IN_PROGRESS",'
                        || '"toStatus":"DONE","fromStatusCategory":"IN_PROGRESS",'
                        || '"toStatusCategory":"DONE","resolution":"验收通过",'
                        || '"rowVersion":4}')::jsonb,
                        'PENDING', 0, ?, ?
                    )
                    """)) {
                event.setObject(1, createdAt.plusHours(1));
                event.setObject(2, createdAt.plusHours(1));
                event.setObject(3, createdAt.plusHours(1));
                assertThat(event.executeUpdate()).isOne();
            }
            try (PreparedStatement override = connection.prepareStatement("""
                    INSERT INTO yumpoo.admin_override (
                        id, company_id, action, target_type, target_id, reason, request_hash,
                        idempotency_key, actor_user_id, before_snapshot, after_snapshot,
                        blocker_counts, result, error_code, occurred_at
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000812',
                        '00000000-0000-4000-8000-000000000001',
                        'PRODUCT_ARCHIVE_WITH_BLOCKERS', 'PRODUCT',
                        '00000000-0000-4000-8000-000000000602',
                        '备份恢复必须保留产品治理覆盖理由和安全快照', ?,
                        '00000000-0000-4000-8000-000000000813',
                        '00000000-0000-4000-8000-000000000102',
                        '{"productId":"00000000-0000-4000-8000-000000000602","status":"ACTIVE","rowVersion":2}'::jsonb,
                        '{"productId":"00000000-0000-4000-8000-000000000602","status":"ARCHIVED","rowVersion":3}'::jsonb,
                        '[{"code":"ACTIVE_DEVELOPMENT_SUPPORT_PROJECTS","count":1}]'::jsonb,
                        'SUCCEEDED', NULL, ?
                    )
                    """)) {
                override.setString(1, "a".repeat(64));
                override.setObject(2, createdAt.plusHours(3));
                assertThat(override.executeUpdate()).isOne();
            }
            connection.commit();
        }
    }

    private static ProjectContentFact readProjectContentFact(
            PostgreSQLContainer container
    ) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT project.id, project.project_code, project.lifecycle,
                            project.row_version, project.activated_at, project.archived_at,
                            project.owner_user_id, project.template_key, project.template_version,
                            membership.status AS membership_status,
                            (SELECT string_agg(m.user_id || ':' || m.status || ':' || m.row_version,
                                ',' ORDER BY m.user_id) FROM yumpoo.project_membership m
                                WHERE m.project_id=project.id) AS membership_facts,
                            (SELECT string_agg(l.product_id || ':' || l.relation_type || ':'
                                || l.is_primary || ':' || COALESCE(l.remove_reason,'-') || ':'
                                || l.row_version || ':' || (l.removed_at IS NULL), ',' ORDER BY l.id)
                                FROM yumpoo.project_product_link l
                                WHERE l.project_id=project.id) AS product_link_facts,
                            issue.issue_type, issue.target_type, issue.status AS issue_status,
                            issue.row_version AS issue_version,
                            (SELECT counter.last_sequence FROM yumpoo.work_item_project_counter counter
                              WHERE counter.project_id=project.id) AS work_item_sequence,
                            (SELECT catalog.row_version
                               FROM yumpoo.project_work_item_label_catalog catalog
                              WHERE catalog.project_id=project.id) AS label_catalog_version,
                            (SELECT catalog.row_version
                               FROM yumpoo.content_catalog_version catalog
                              WHERE catalog.project_id=project.id) AS content_catalog_version,
                            (SELECT string_agg(label.status_code || ':' || label.display_name || ':'
                                || label.color_token || ':' || label.status_category || ':'
                                || label.sort_order || ':' || label.active || ':'
                                || label.protected_label, ',' ORDER BY label.sort_order)
                               FROM yumpoo.project_work_item_status_label label
                              WHERE label.project_id=project.id) AS status_labels,
                            (SELECT string_agg(label.priority_code || ':' || label.display_name || ':'
                                || label.color_token || ':' || label.sort_order || ':' || label.active,
                                ',' ORDER BY label.sort_order)
                               FROM yumpoo.project_work_item_priority_label label
                              WHERE label.project_id=project.id) AS priority_labels,
                            (SELECT string_agg(lane.status_code, ',' ORDER BY lane.status_code)
                               FROM yumpoo.work_item_rank_lane lane
                              WHERE lane.project_id=project.id) AS rank_lanes,
                            (SELECT string_agg(item.item_no || ':' || item.content_id || ':' || item.title
                                || ':' || item.status_code || ':' || item.status_category || ':'
                                || item.rank || ':' || item.project_sort_key || ':' || item.priority || ':'
                                || COALESCE(item.description,'-') || ':'
                                || COALESCE(item.notes,'-') || ':'
                                || COALESCE(item.assignee_user_id::text,'-') || ':'
                                || COALESCE(item.timeline_start_date::text,'-') || ':'
                                || COALESCE(item.timeline_end_date::text,'-') || ':'
                                || COALESCE(item.due_date::text,'-') || ':' || item.row_version || ':'
                                || item.reporter_user_id || ':' || item.created_by_user_id || ':'
                                || item.updated_by_user_id || ':' || item.created_at || ':'
                                || item.updated_at || ':' || COALESCE(item.deleted_at::text,'-') || ':'
                                || COALESCE(item.deleted_by_user_id::text,'-') || ':'
                                || COALESCE(item.delete_reason,'-'), ',' ORDER BY item.item_sequence)
                               FROM yumpoo.work_item item WHERE item.project_id=project.id)
                                AS work_items,
                            string_agg(content.code || ':' || content.name || ':'
                                || content.color_token || ':' || content.sort_order || ':'
                                || content.active || ':' || content.protected_content || ':'
                                || content.ever_used || ':' || content.row_version || ':'
                                || (content.deleted_at IS NOT NULL), ',' ORDER BY content.sort_order) AS contents
                       FROM yumpoo.project project
                       JOIN yumpoo.project_membership membership
                         ON membership.project_id = project.id
                        AND membership.user_id = project.owner_user_id
                       JOIN yumpoo.content content ON content.project_id = project.id
                       JOIN yumpoo.governance_issue issue ON issue.target_id=project.id
                         AND issue.target_type='PROJECT'
                      WHERE project.id = '00000000-0000-4000-8000-000000000802'
                      GROUP BY project.id, project.project_code, project.lifecycle,
                               project.row_version, project.activated_at, project.archived_at,
                               project.owner_user_id, project.template_key, project.template_version,
                               membership.status, issue.issue_type, issue.target_type,
                               issue.status, issue.row_version
                     """)) {
            assertThat(result.next()).isTrue();
            ProjectContentFact fact = new ProjectContentFact(
                    result.getObject("id", UUID.class), result.getString("project_code"),
                    result.getString("lifecycle"), result.getLong("row_version"),
                    result.getObject("activated_at", OffsetDateTime.class),
                    result.getObject("archived_at", OffsetDateTime.class),
                    result.getObject("owner_user_id", UUID.class),
                    result.getString("template_key"), result.getInt("template_version"),
                    result.getString("membership_status"), result.getString("membership_facts"),
                    result.getString("product_link_facts"),
                    result.getString("issue_type"), result.getString("target_type"),
                    result.getString("issue_status"), result.getLong("issue_version"),
                    result.getLong("work_item_sequence"), result.getLong("label_catalog_version"),
                    result.getLong("content_catalog_version"),
                    result.getString("status_labels"), result.getString("priority_labels"),
                    result.getString("rank_lanes"),
                    result.getString("work_items"), result.getString("contents"));
            assertThat(result.next()).isFalse();
            return fact;
        }
    }

    private static String readAdminOverrideFact(PostgreSQLContainer container) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT action || ':' || target_type || ':' || target_id || ':' || reason || ':'
                            || result || ':' || blocker_counts::text || ':' || before_snapshot::text
                            || ':' || after_snapshot::text AS fact
                     FROM yumpoo.admin_override
                     WHERE id = '00000000-0000-4000-8000-000000000812'
                     """)) {
            assertThat(result.next()).isTrue();
            String fact = result.getString("fact");
            assertThat(result.next()).isFalse();
            return fact;
        }
    }

    private static String readWorkItemUpdateFact(PostgreSQLContainer container) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT concat_ws(':', coalesce(update_record.body_html, '<NULL>'),
                            coalesce(update_record.body_text, '<NULL>'), update_record.status,
                            update_record.edit_deadline_at, update_record.row_version,
                            update_record.edited_at, update_record.edited_by_user_id,
                            update_record.deleted_at, update_record.deleted_by_user_id,
                            update_record.delete_reason, mention.mentioned_user_id,
                            mention.mentioned_display_name) AS fact
                       FROM yumpoo.work_item_update update_record
                       JOIN yumpoo.work_item_update_mention mention
                         ON mention.update_id = update_record.id
                      WHERE update_record.id = '00000000-0000-4000-8000-000000000818'
                     """)) {
            assertThat(result.next()).isTrue();
            String fact = result.getString("fact");
            assertThat(result.next()).isFalse();
            return fact;
        }
    }

    private static String readWorkItemStatusEventFact(
            PostgreSQLContainer container
    ) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT event_type || ':' || event_version || ':' || aggregate_type || ':'
                            || aggregate_id || ':' || aggregate_version || ':' || payload_json::text
                            || ':' || status || ':' || attempt_count AS fact
                     FROM yumpoo.outbox_event
                     WHERE event_id = '00000000-0000-4000-8000-000000000817'
                     """)) {
            assertThat(result.next()).isTrue();
            String fact = result.getString("fact");
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

    private record WorkspaceFact(
            UUID id,
            UUID companyId,
            String code,
            String name,
            String description,
            int sortOrder,
            String status,
            long rowVersion,
            UUID createdByUserId,
            UUID updatedByUserId
    ) {
    }

    private record ProductGovernanceFact(
            UUID productId,
            String code,
            String productStatus,
            UUID ownerUserId,
            long rowVersion,
            String issueType,
            String targetType,
            UUID targetId,
            String issueStatus,
            String resolutionCode
    ) {
    }

    private record ProjectContentFact(
            UUID projectId,
            String projectCode,
            String lifecycle,
            long rowVersion,
            OffsetDateTime activatedAt,
            OffsetDateTime archivedAt,
            UUID ownerUserId,
            String templateKey,
            int templateVersion,
            String membershipStatus,
            String membershipFacts,
            String productLinkFacts,
            String issueType,
            String issueTargetType,
            String issueStatus,
            long issueVersion,
            long workItemSequence,
            long labelCatalogVersion,
            long contentCatalogVersion,
            String statusLabels,
            String priorityLabels,
            String rankLanes,
            String workItems,
            String contents
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
                        Map.entry("attachmentBlobRegistryReconciled", true),
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
