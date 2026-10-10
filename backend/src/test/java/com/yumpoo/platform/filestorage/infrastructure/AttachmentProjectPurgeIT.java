package com.yumpoo.platform.filestorage.infrastructure;

import com.yumpoo.platform.filestorage.application.AttachmentData.CreateIntent;
import com.yumpoo.platform.filestorage.application.AttachmentData.Finalization;
import com.yumpoo.platform.filestorage.application.AttachmentData.ScanClaim;
import com.yumpoo.platform.filestorage.application.AttachmentFileNamePolicy;
import com.yumpoo.platform.filestorage.application.BlobVerification;
import com.yumpoo.platform.filestorage.application.PublishedBlob;
import com.yumpoo.platform.filestorage.application.QuarantineStorage;
import com.yumpoo.platform.filestorage.domain.AttachmentOwnerType;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

@ActiveProfiles("test")
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(properties = {"yumpoo.outbox.enabled=false", "yumpoo.attachments.scan-enabled=false",
        "yumpoo.attachments.maintenance-initial-delay=1d", "yumpoo.projects.deletion.purge-poll-delay=1d"})
class AttachmentProjectPurgeIT {
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcAttachmentRepository repository;
    @TempDir Path directory;
    private final Set<UUID> companies = new HashSet<>();
    private final Set<String> keys = new HashSet<>();
    private final Set<UUID> runs = new HashSet<>();
    private UUID company;
    private UUID project;
    private MutableClock clock;
    private LocalFileQuarantineStorage storage;

    @BeforeEach
    void prepare() throws IOException {
        company = UUID.randomUUID(); project = UUID.randomUUID(); companies.add(company);
        clock = new MutableClock(Instant.parse("2026-10-10T12:00:00Z"));
        storage = new LocalFileQuarantineStorage(directory.resolve("quarantine"), directory.resolve("blobs"));
    }

    @AfterEach
    void cleanDatabase() {
        for (UUID id : runs) jdbc.sql("DELETE FROM yumpoo.attachment_maintenance_run WHERE id=:id").param("id", id).update();
        for (UUID id : companies) {
            jdbc.sql("DELETE FROM yumpoo.attachment_reconciliation_issue WHERE company_id=:company OR attachment_id IN (SELECT id FROM yumpoo.attachment WHERE company_id=:company)")
                    .param("company", id).update();
            jdbc.sql("DELETE FROM yumpoo.attachment_scan_task WHERE company_id=:company").param("company", id).update();
            jdbc.sql("DELETE FROM yumpoo.attachment WHERE company_id=:company").param("company", id).update();
            jdbc.sql("DELETE FROM yumpoo.attachment_quota_usage WHERE company_id=:company").param("company", id).update();
            jdbc.sql("DELETE FROM yumpoo.attachment_project_purge WHERE company_id=:company").param("company", id).update();
        }
        for (String key : keys) {
            jdbc.sql("DELETE FROM yumpoo.attachment_reconciliation_issue WHERE subject_type='BLOB' AND subject_key=:key").param("key", key).update();
            jdbc.sql("DELETE FROM yumpoo.attachment_blob WHERE storage_key=:key").param("key", key).update();
        }
    }

    @Test
    void removesAllStatesTemporaryFilesAndPrivateBlobsWhilePreservingOtherProjects() throws Exception {
        PublishedBlob privateBlob = blob();
        PublishedBlob shared = blob();
        UUID privateId = attachment(company, project, "AVAILABLE", privateBlob, true);
        attachment(company, project, "DELETED", shared, true);
        UUID sealed = attachment(company, project, "REJECTED", privateBlob, false);
        Files.writeString(directory.resolve("quarantine").resolve(sealed + ".sealed"), "rejected evidence");
        UUID partial = attachment(company, project, "UPLOADING", null, false);
        Files.writeString(directory.resolve("quarantine").resolve(partial + ".part"), "partial evidence");
        PublishedBlob publishedBeforeCheckpoint = blob();
        UUID recovering = attachment(company, project, "UPLOADING", publishedBeforeCheckpoint, false);
        jdbc.sql("DELETE FROM yumpoo.attachment_blob WHERE storage_key=:key").param("key", publishedBeforeCheckpoint.storageKey()).update();
        scan(recovering, company, 1, "READY", null);
        UUID otherProject = UUID.randomUUID();
        UUID otherId = attachment(company, otherProject, "AVAILABLE", shared, true);
        issue("ATTACHMENT", privateId.toString(), privateId, "MISSING_BLOB");
        issue("QUARANTINE", sealed + ".sealed", null, "QUARANTINE_ORPHAN");
        issue("BLOB", privateBlob.storageKey(), null, "SIZE_MISMATCH");
        issue("BLOB", shared.storageKey(), null, "HASH_MISMATCH");
        issue("QUOTA", company + ":PROJECT:" + project, null, "QUOTA_MISMATCH");

        finish(purger(storage), company, project, 500);

        assertThat(ownedAttachments(company, project)).isZero();
        assertThat(repository.find(company, otherId)).isPresent();
        assertThat(storage.verify(shared)).isTrue();
        assertThat(Files.exists(blobPath(privateBlob))).isFalse();
        assertThat(Files.exists(blobPath(publishedBeforeCheckpoint))).isFalse();
        assertThat(storage.temporaryEntryExists(partial + ".part")).isFalse();
        assertThat(storage.temporaryEntryExists(sealed + ".sealed")).isFalse();
        assertThat(registry(privateBlob)).isEqualTo("DELETED");
        assertThat(registry(shared)).isEqualTo("PRESENT");
        assertThat(companyQuota("reserved_bytes")).isZero();
        assertThat(companyQuota("available_bytes")).isEqualTo(shared.sizeBytes());
        assertThat(projectQuotaCount(project)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.attachment_reconciliation_issue WHERE company_id=:company")
                .param("company", company).query(Long.class).single()).isEqualTo(1);
        assertThat(repository.isProjectPurging(company, project)).isTrue();
    }

    @Test
    void countsIssuesTasksMetadataAndQuotaAgainstOneSharedDeletionBudget() {
        UUID id = attachment(company, project, "UPLOADING", null, false);
        jdbc.sql("""
                INSERT INTO yumpoo.attachment_reconciliation_issue(id,issue_code,subject_type,subject_key,
                    attachment_id,company_id,first_detected_at,last_detected_at,resolved_at)
                SELECT md5(:prefix||g)::uuid,'MISSING_BLOB','ATTACHMENT',:key,:id,:company,:now,:now,:now
                  FROM generate_series(1,601) g
                """).param("prefix", UUID.randomUUID().toString()).param("key", id.toString()).param("id", id)
                .param("company", company).param("now", utc(clock.instant())).update();
        jdbc.sql("""
                INSERT INTO yumpoo.attachment_scan_task(id,attachment_id,company_id,generation,status,
                    next_attempt_at,created_at,updated_at)
                SELECT md5(:prefix||g)::uuid,:id,:company,g,'COMPLETED',:now,:now,:now
                  FROM generate_series(1,601) g
                """).param("prefix", UUID.randomUUID().toString()).param("id", id).param("company", company)
                .param("now", utc(clock.instant())).update();
        var purger = purger(storage);
        boolean remaining;
        int calls = 0;
        do {
            long before = ownedRows();
            remaining = purger.purgeBatch(company, project, 500);
            assertThat(before - ownedRows()).isBetween(0L, 500L);
            assertThat(++calls).isLessThan(10);
        } while (remaining);
        assertThat(calls).isEqualTo(3);
        assertThat(ownedRows()).isZero();
        assertThatThrownBy(() -> purger.purgeBatch(company, project, 501)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purger.purgeBatch(company, project, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedUnlinkRetainsMetadataQuotaAndDurableCleanupForRestart() throws Exception {
        PublishedBlob blob = blob();
        UUID id = attachment(company, project, "AVAILABLE", blob, true);
        var failing = spy(storage);
        doThrow(new IOException("synthetic disk failure")).when(failing).purgePublished(blob.storageKey());

        assertThatThrownBy(() -> purger(failing).purgeBatch(company, project, 500)).isInstanceOf(IllegalStateException.class);
        assertThat(repository.find(company, id)).isPresent();
        assertThat(companyQuota("available_bytes")).isEqualTo(blob.sizeBytes());
        assertThat(operation(blob)).isEqualTo("CLEANUP");
        assertThat(storage.verify(blob)).isTrue();

        clock.advance(Duration.ofMinutes(6));
        finish(purger(storage), company, project, 500);
        assertThat(ownedAttachments(company, project)).isZero();
        assertThat(Files.exists(blobPath(blob))).isFalse();
        assertThat(registry(blob)).isEqualTo("DELETED");
    }

    @Test
    void restartAfterPhysicalUnlinkAndTransactionRollbackFinishesIdempotently() throws Exception {
        PublishedBlob blob = blob();
        UUID id = attachment(company, project, "AVAILABLE", blob, true);
        var failing = spy(storage);
        doAnswer(call -> { storage.purgePublished(blob.storageKey()); throw new IOException("crash after unlink"); })
                .when(failing).purgePublished(blob.storageKey());

        assertThatThrownBy(() -> purger(failing).purgeBatch(company, project, 500)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(blobPath(blob))).isFalse();
        assertThat(repository.find(company, id)).isPresent();
        assertThat(registry(blob)).isEqualTo("PRESENT");
        assertThat(operation(blob)).isEqualTo("CLEANUP");
        clock.advance(Duration.ofMinutes(6));
        finish(purger(storage), company, project, 500);
        assertThat(companyQuota("available_bytes")).isZero();
        assertThat(registry(blob)).isEqualTo("DELETED");
    }

    @Test
    void waitsForUnexpiredUploadAndPublishLeases() throws Exception {
        UUID receiving = attachment(company, project, "UPLOADING", null, false);
        Files.writeString(directory.resolve("quarantine").resolve(receiving + ".part"), "receiving");
        jdbc.sql("UPDATE yumpoo.attachment SET upload_lease_token=:token,upload_lease_until=:until WHERE id=:id")
                .param("token", UUID.randomUUID()).param("until", utc(clock.instant().plusSeconds(60))).param("id", receiving).update();
        PublishedBlob blob = blob(); attachment(company, project, "AVAILABLE", blob, true);
        publishLease(blob, UUID.randomUUID());

        assertThat(purger(storage).purgeBatch(company, project, 500)).isTrue();
        assertThat(ownedAttachments(company, project)).isEqualTo(2);
        assertThat(storage.temporaryEntryExists(receiving + ".part")).isTrue();
        assertThat(storage.verify(blob)).isTrue();
        clock.advance(Duration.ofMinutes(6));
        finish(purger(storage), company, project, 500);
        assertThat(storage.temporaryEntryExists(receiving + ".part")).isFalse();
        assertThat(Files.exists(blobPath(blob))).isFalse();
    }

    @Test
    void aNewPublishTokenFencesAnExpiredCleanupBeforeItCanUnlink() throws Exception {
        PublishedBlob blob = blob(); UUID id = attachment(company, project, "AVAILABLE", blob, true);
        UUID newer = UUID.randomUUID();
        var racing = spy(storage);
        doAnswer(call -> { publishLease(blob, newer); storage.purgeTemporary(id); return null; })
                .when(racing).purgeTemporary(id);

        assertThat(purger(racing).purgeBatch(company, project, 500)).isTrue();
        assertThat(storage.verify(blob)).isTrue();
        assertThat(repository.find(company, id)).isPresent();
        assertThat(jdbc.sql("SELECT operation_token FROM yumpoo.attachment_blob WHERE storage_key=:key")
                .param("key", blob.storageKey()).query(UUID.class).single()).isEqualTo(newer);
        clock.advance(Duration.ofMinutes(6));
        finish(purger(storage), company, project, 500);
    }

    @Test
    void aReferenceCreatedAfterClaimPreservesTheBlobAcrossCompanies() throws Exception {
        PublishedBlob blob = blob(); UUID id = attachment(company, project, "AVAILABLE", blob, true);
        UUID otherCompany = UUID.randomUUID(); companies.add(otherCompany);
        UUID otherProject = UUID.randomUUID();
        var racing = spy(storage);
        doAnswer(call -> { attachment(otherCompany, otherProject, "UPLOADING", blob, false); return null; })
                .when(racing).purgeTemporary(id);

        finish(purger(racing), company, project, 500);
        assertThat(storage.verify(blob)).isTrue();
        assertThat(registry(blob)).isEqualTo("PRESENT");
        assertThat(operation(blob)).isNull();
        assertThat(ownedAttachments(otherCompany, otherProject)).isEqualTo(1);
        assertThat(companyQuota("available_bytes")).isZero();
    }

    @Test
    void concurrentPurgesOfTheLastSharedReferencesCannotLoseTheBlobCheckpoint() throws Exception {
        PublishedBlob blob = blob();
        attachment(company, project, "AVAILABLE", blob, true);
        UUID otherProject = UUID.randomUUID(); attachment(company, otherProject, "AVAILABLE", blob, true);
        var bothClaimedShared = new CountDownLatch(2);
        var racing = spy(storage);
        doAnswer(call -> {
            bothClaimedShared.countDown();
            if (!bothClaimedShared.await(10, TimeUnit.SECONDS)) throw new IOException("shared claim did not rendezvous");
            return null;
        }).when(racing).purgeTemporary(any(UUID.class));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> purger(racing).purgeBatch(company, project, 500));
            var second = executor.submit(() -> purger(racing).purgeBatch(company, otherProject, 500));
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
        finish(purger(storage), company, project, 500);
        finish(purger(storage), company, otherProject, 500);
        assertThat(Files.exists(blobPath(blob))).isFalse();
        assertThat(registry(blob)).isEqualTo("DELETED");
        assertThat(companyQuota("available_bytes")).isZero();
    }

    @Test
    void expiredCleanupRowLockKeepsANewPublishBehindTheUnlink() throws Exception {
        PublishedBlob blob = blob(); attachment(company, project, "AVAILABLE", blob, true);
        byte[] content = Files.readAllBytes(blobPath(blob));
        UUID otherCompany = UUID.randomUUID(); companies.add(otherCompany);
        UUID otherProject = UUID.randomUUID();
        var pending = new AtomicReference<Future<PublishedBlob>>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var racing = spy(storage);
            doAnswer(call -> {
                clock.advance(Duration.ofMinutes(6));
                var requesting = new CountDownLatch(1);
                pending.set(executor.submit(() -> {
                    UUID id = attachment(otherCompany, otherProject, "UPLOADING", blob, false);
                    var sealed = storage.receive(id, new ByteArrayInputStream(content), OptionalLong.of(content.length));
                    UUID task = scan(id, otherCompany, 1, "RUNNING", clock.instant().plusSeconds(300));
                    UUID lease = jdbc.sql("SELECT lease_token FROM yumpoo.attachment_scan_task WHERE id=:id").param("id", task).query(UUID.class).single();
                    var claim = new ScanClaim(task, lease, id, otherCompany, otherProject, AttachmentOwnerType.WORK_ITEM,
                            UUID.randomUUID(), UUID.randomUUID(), "evidence.txt", "text/plain", blob.sizeBytes(), blob.sha256(), "text/plain", null, 1, 1);
                    UUID operation = UUID.randomUUID();
                    requesting.countDown();
                    assertThat(repository.claimPublish(claim, blob.storageKey(), "new-publisher", operation, clock.instant(), clock.instant().plusSeconds(300))).isTrue();
                    PublishedBlob published = storage.publish(sealed, mutation -> repository.mutatePublish(
                            claim, blob.storageKey(), operation, clock.instant(), mutation));
                    repository.recordPublished(claim, published.storageKey(), clock.instant());
                    repository.completePublish(published.storageKey(), operation, clock.instant());
                    return published;
                }));
                assertThat(requesting.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> pending.get().get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                storage.purgePublished(blob.storageKey());
                return null;
            }).when(racing).purgePublished(blob.storageKey());
            finish(purger(racing), company, project, 500);
            assertThat(pending.get().get(10, TimeUnit.SECONDS)).isEqualTo(blob);
        }
        assertThat(ownedAttachments(company, project)).isZero();
        assertThat(ownedAttachments(otherCompany, otherProject)).isEqualTo(1);
        assertThat(storage.verify(blob)).isTrue();
        assertThat(registry(blob)).isEqualTo("PRESENT");
    }

    @Test
    void oldWritersAndLateObservationsCannotResurrectPurgedProjectRows() throws Exception {
        PublishedBlob blob = blob(); UUID id = attachment(company, project, "UPLOADING", blob, false);
        UUID task = scan(id, company, 1, "RUNNING", clock.instant().plusSeconds(300));
        UUID token = jdbc.sql("SELECT lease_token FROM yumpoo.attachment_scan_task WHERE id=:id").param("id", task).query(UUID.class).single();
        ScanClaim claim = new ScanClaim(task, token, id, company, project, AttachmentOwnerType.WORK_ITEM,
                UUID.randomUUID(), UUID.randomUUID(), "evidence.txt", "text/plain", blob.sizeBytes(), blob.sha256(), null, null, 1, 1);
        var purger = purger(storage);
        assertThat(purger.purgeBatch(company, project, 500)).isTrue();

        assertThatThrownBy(() -> repository.beginUpload(company, id, UUID.randomUUID(), clock.instant(), clock.instant().plusSeconds(60)))
                .isInstanceOf(ApplicationException.class).extracting(error -> ((ApplicationException) error).reason()).isEqualTo("PROJECT_PURGING");
        var intent = new CreateIntent(UUID.randomUUID(), company, project, AttachmentOwnerType.WORK_ITEM,
                UUID.randomUUID(), "new.txt", "text/plain", 8L, UUID.randomUUID(), clock.instant());
        assertThatThrownBy(() -> repository.insertIntent(intent, AttachmentFileNamePolicy.normalize("new.txt"), 8, 1_000_000, 1_000_000, clock.instant().plusSeconds(60)))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> repository.claimPublish(claim, blob.storageKey(), "stale", UUID.randomUUID(), clock.instant(), clock.instant().plusSeconds(60)))
                .isInstanceOf(ApplicationException.class);
        var finalization = new Finalization(id, company, project, AttachmentOwnerType.WORK_ITEM,
                UUID.randomUUID(), UUID.randomUUID(), "evidence.txt", "text/plain", blob.sizeBytes(), blob.storageKey(), 1, task, token);
        assertThatThrownBy(() -> repository.completeAvailable(finalization, clock.instant())).isInstanceOf(ApplicationException.class);
        assertThat(repository.claimDue("late-worker", UUID.randomUUID(), clock.instant(), clock.instant().plusSeconds(60))).isEmpty();
        repository.recordReconciliationIssue("MISSING_BLOB", "ATTACHMENT", id.toString(), id, company, clock.instant());
        repository.recordReconciliationIssue("STALE_SCAN_TASK", "SCAN_TASK", task.toString(), null, company, clock.instant());
        repository.recordReconciliationIssue("QUOTA_MISMATCH", "QUOTA", company + ":PROJECT:" + project, null, company, clock.instant());
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.attachment_reconciliation_issue WHERE company_id=:company")
                .param("company", company).query(Long.class).single()).isZero();

        clock.advance(Duration.ofMinutes(6)); finish(purger, company, project, 500);
        repository.recordReconciliationIssue("MISSING_BLOB", "ATTACHMENT", id.toString(), id, company, clock.instant());
        repository.recordReconciliationIssue("STALE_SCAN_TASK", "SCAN_TASK", task.toString(), null, company, clock.instant());
        repository.recordReconciliationIssue("MISSING_BLOB", "BLOB", blob.storageKey(), null, company, clock.instant());
        assertThat(ownedRows()).isZero();
    }

    @Test
    void aCachedMaintenanceVerificationCannotRestoreAPurgedBlob() throws Exception {
        PublishedBlob blob = blob(); attachment(company, project, "AVAILABLE", blob, true);
        var racing = spy(storage);
        doAnswer(call -> { finish(purger(storage), company, project, 500); return BlobVerification.VERIFIED; }).when(racing).inspect(blob);
        UUID run = UUID.randomUUID(); runs.add(run);
        jdbc.sql("""
                INSERT INTO yumpoo.attachment_maintenance_run(id,status,phase,dry_run,lease_owner,lease_token,
                    lease_until,started_at,updated_at) VALUES (:id,'RUNNING','VERIFY_BLOBS',true,'test',:token,:until,:now,:now)
                """).param("id", run).param("token", UUID.randomUUID()).param("until", utc(clock.instant().minusSeconds(60)))
                .param("now", utc(clock.instant())).update();
        var maintenance = new JdbcAttachmentMaintenanceService(jdbc, racing, new AttachmentProperties(), clock, transactions);

        assertThat(maintenance.resumeOneBatch("purge-maintenance-test")).isPresent();
        assertThat(registry(blob)).isEqualTo("DELETED");
        assertThat(ownedRows()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void uploadResumingAfterItsLeaseExpiresAndPurgeCompletesCannotCreateOrSealFiles(int pausedMutation) throws Exception {
        UUID id = attachment(company, project, "UPLOADING", null, false);
        UUID token = UUID.randomUUID();
        repository.beginUpload(company, id, token, clock.instant(), clock.instant().plusSeconds(300)).orElseThrow();
        var reached = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> storage.receive(id, new ByteArrayInputStream("evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    OptionalLong.of(8), 8, mutation -> {
                        if (calls.incrementAndGet() == pausedMutation) { reached.countDown(); await(resume); }
                        repository.mutateUpload(company, id, token, clock.instant(), mutation);
                    }));
            try {
                await(reached);
                clock.advance(Duration.ofMinutes(6));
                finish(purger(storage), company, project, 500);
                resume.countDown();
                assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(ApplicationException.class);
            } finally { resume.countDown(); }
        }
        assertThat(storage.temporaryEntryExists(id + ".part")).isFalse();
        assertThat(storage.temporaryEntryExists(id + ".sealed")).isFalse();
        assertThat(ownedRows()).isZero();
    }

    @Test
    void publishResumingAfterItsLeaseExpiresAndPurgeCompletesCannotRecreateTheBlob() throws Exception {
        PublishedBlob blob = blob(); byte[] content = Files.readAllBytes(blobPath(blob));
        storage.purgePublished(blob.storageKey());
        UUID id = attachment(company, project, "UPLOADING", blob, false);
        var sealed = storage.receive(id, new ByteArrayInputStream(content), OptionalLong.of(content.length));
        var claim = claimFor(id, blob);
        UUID operation = UUID.randomUUID();
        assertThat(repository.claimPublish(claim, blob.storageKey(), "paused-publisher", operation, clock.instant(), clock.instant().plusSeconds(300))).isTrue();
        var reached = new CountDownLatch(1); var resume = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> storage.publish(sealed, mutation -> {
                reached.countDown(); await(resume);
                repository.mutatePublish(claim, blob.storageKey(), operation, clock.instant(), mutation);
            }));
            try {
                await(reached); clock.advance(Duration.ofMinutes(6));
                finish(purger(storage), company, project, 500);
                resume.countDown();
                assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(ApplicationException.class);
            } finally { resume.countDown(); }
        }
        assertThat(Files.exists(blobPath(blob))).isFalse();
        assertThat(storage.temporaryEntryExists(id + ".sealed")).isFalse();
        assertThat(registry(blob)).isEqualTo("DELETED");
        assertThat(ownedRows()).isZero();
    }

    @Test
    void anOldPublishTokenCannotDiscardTheSealedCheckpointWhenReusingAnExistingBlob() throws Exception {
        PublishedBlob blob = blob(); byte[] content = Files.readAllBytes(blobPath(blob));
        UUID id = attachment(company, project, "UPLOADING", blob, false);
        var sealed = storage.receive(id, new ByteArrayInputStream(content), OptionalLong.of(content.length));
        var claim = claimFor(id, blob);
        UUID old = UUID.randomUUID();
        assertThat(repository.claimPublish(claim, blob.storageKey(), "paused-publisher", old, clock.instant(), clock.instant().plusSeconds(300))).isTrue();
        publishLease(blob, UUID.randomUUID());

        assertThatThrownBy(() -> storage.publish(sealed, mutation -> repository.mutatePublish(
                claim, blob.storageKey(), old, clock.instant(), mutation))).isInstanceOf(IOException.class);
        assertThat(storage.temporaryEntryExists(id + ".sealed")).isTrue();
        assertThat(storage.verify(blob)).isTrue();
    }

    @Test
    void anExpiredUploadCannotRenameOrDeleteANewerOwnersPartialFile() throws Exception {
        UUID id = attachment(company, project, "UPLOADING", null, false);
        UUID old = UUID.randomUUID();
        repository.beginUpload(company, id, old, clock.instant(), clock.instant().plusSeconds(300)).orElseThrow();
        var oldReached = new CountDownLatch(1); var oldResume = new CountDownLatch(1);
        var newReached = new CountDownLatch(1); var newResume = new CountDownLatch(1);
        var oldCalls = new AtomicInteger(); var newCalls = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> storage.receive(id, new ByteArrayInputStream(new byte[]{1, 2, 3}), OptionalLong.of(3), 8, mutation -> {
                if (oldCalls.incrementAndGet() == 2) { oldReached.countDown(); await(oldResume); }
                repository.mutateUpload(company, id, old, clock.instant(), mutation);
            }));
            try {
                await(oldReached); clock.advance(Duration.ofMinutes(6));
                UUID newer = UUID.randomUUID();
                repository.beginUpload(company, id, newer, clock.instant(), clock.instant().plusSeconds(300)).orElseThrow();
                var second = executor.submit(() -> storage.receive(id, new ByteArrayInputStream(new byte[]{4, 5, 6}), OptionalLong.of(3), 8, mutation -> {
                    if (newCalls.incrementAndGet() == 2) { newReached.countDown(); await(newResume); }
                    repository.mutateUpload(company, id, newer, clock.instant(), mutation);
                }));
                await(newReached); oldResume.countDown();
                assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ApplicationException.class);
                assertThat(Files.readAllBytes(directory.resolve("quarantine").resolve(id + ".part"))).containsExactly(new byte[]{4, 5, 6});
                newResume.countDown();
                var received = second.get(10, TimeUnit.SECONDS);
                assertThat(Files.readAllBytes(received.quarantinedPath())).containsExactly(new byte[]{4, 5, 6});
            } finally { oldResume.countDown(); newResume.countDown(); }
        }
    }

    private JdbcAttachmentProjectDataPurger purger(QuarantineStorage selected) {
        return new JdbcAttachmentProjectDataPurger(jdbc, selected, clock, transactions);
    }

    private void finish(JdbcAttachmentProjectDataPurger purger, UUID selectedCompany, UUID selectedProject, int limit) {
        for (int attempt = 0; attempt < 20; attempt++) if (!purger.purgeBatch(selectedCompany, selectedProject, limit)) return;
        throw new AssertionError("purge did not finish");
    }

    private PublishedBlob blob() throws IOException {
        byte[] content = UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var sealed = storage.receive(UUID.randomUUID(), new ByteArrayInputStream(content), OptionalLong.of(content.length));
        var blob = storage.publish(sealed); keys.add(blob.storageKey());
        jdbc.sql("INSERT INTO yumpoo.attachment_blob(storage_key,sha256,size_bytes,created_at,updated_at) VALUES (:key,:sha,:size,:now,:now)")
                .param("key", blob.storageKey()).param("sha", blob.sha256()).param("size", blob.sizeBytes()).param("now", utc(clock.instant())).update();
        return blob;
    }

    private UUID attachment(UUID selectedCompany, UUID selectedProject, String status, PublishedBlob blob, boolean linked) {
        UUID id = UUID.randomUUID(); long size = blob == null ? 8 : blob.sizeBytes();
        jdbc.sql("""
                INSERT INTO yumpoo.attachment(id,company_id,quota_project_id,owner_type,owner_id,original_file_name,
                    file_extension,declared_mime,detected_mime,size_bytes,sha256,storage_key,status,reserved_bytes,
                    uploaded_by_user_id,intent_expires_at,available_at,rejected_code,rejected_at,deleted_by_user_id,
                    deleted_at,delete_reason,created_at,updated_at)
                VALUES (:id,:company,:project,'WORK_ITEM',:owner,'evidence.txt','txt','text/plain','text/plain',
                    :size,:sha,:key,:status,:reserved,:user,:expires,:available,:rejected,:rejectedAt,:deletedBy,:deletedAt,:reason,:now,:now)
                """).param("id", id).param("company", selectedCompany).param("project", selectedProject)
                .param("owner", UUID.randomUUID()).param("size", size).param("sha", blob == null ? null : blob.sha256())
                .param("key", linked ? blob.storageKey() : null).param("status", status)
                .param("reserved", "UPLOADING".equals(status) ? size : 0).param("user", UUID.randomUUID())
                .param("expires", utc(clock.instant().plusSeconds(3600)))
                .param("available", "AVAILABLE".equals(status) ? utc(clock.instant()) : null)
                .param("rejected", "REJECTED".equals(status) ? "FILE_TYPE_NOT_ALLOWED" : null)
                .param("rejectedAt", "REJECTED".equals(status) ? utc(clock.instant()) : null)
                .param("deletedBy", "DELETED".equals(status) ? UUID.randomUUID() : null)
                .param("deletedAt", "DELETED".equals(status) ? utc(clock.instant()) : null)
                .param("reason", "DELETED".equals(status) ? "removed" : null).param("now", utc(clock.instant())).update();
        for (String scope : new String[]{"COMPANY", "PROJECT"}) {
            jdbc.sql("""
                    INSERT INTO yumpoo.attachment_quota_usage(company_id,scope_type,scope_id,reserved_bytes,available_bytes,updated_at)
                    VALUES (:company,:scope,:id,:reserved,:available,:now) ON CONFLICT(company_id,scope_type,scope_id)
                    DO UPDATE SET reserved_bytes=yumpoo.attachment_quota_usage.reserved_bytes+:reserved,
                        available_bytes=yumpoo.attachment_quota_usage.available_bytes+:available
                    """).param("company", selectedCompany).param("scope", scope).param("id", "COMPANY".equals(scope) ? selectedCompany : selectedProject)
                    .param("reserved", "UPLOADING".equals(status) ? size : 0).param("available", "AVAILABLE".equals(status) ? size : 0)
                    .param("now", utc(clock.instant())).update();
        }
        return id;
    }

    private UUID scan(UUID attachment, UUID selectedCompany, int generation, String status, Instant until) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO yumpoo.attachment_scan_task(id,attachment_id,company_id,generation,status,next_attempt_at,
                    lease_owner,lease_token,lease_until,created_at,updated_at)
                VALUES (:id,:attachment,:company,:generation,:status,:now,:owner,:token,:until,:now,:now)
                """).param("id", id).param("attachment", attachment).param("company", selectedCompany).param("generation", generation)
                .param("status", status).param("owner", until == null ? null : "stale-worker").param("token", until == null ? null : UUID.randomUUID())
                .param("until", until == null ? null : utc(until)).param("now", utc(clock.instant())).update();
        return id;
    }

    private ScanClaim claimFor(UUID id, PublishedBlob blob) {
        UUID task = scan(id, company, 1, "RUNNING", clock.instant().plusSeconds(300));
        UUID token = jdbc.sql("SELECT lease_token FROM yumpoo.attachment_scan_task WHERE id=:id").param("id", task).query(UUID.class).single();
        return new ScanClaim(task, token, id, company, project, AttachmentOwnerType.WORK_ITEM, UUID.randomUUID(), UUID.randomUUID(),
                "evidence.txt", "text/plain", blob.sizeBytes(), blob.sha256(), "text/plain", null, 1, 1);
    }

    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException("storage mutation did not rendezvous"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
    }

    private void issue(String type, String key, UUID attachment, String code) {
        jdbc.sql("""
                INSERT INTO yumpoo.attachment_reconciliation_issue(id,issue_code,subject_type,subject_key,attachment_id,
                    company_id,first_detected_at,last_detected_at) VALUES (:id,:code,:type,:key,:attachment,:company,:now,:now)
                """).param("id", UUID.randomUUID()).param("code", code).param("type", type).param("key", key).param("attachment", attachment)
                .param("company", company).param("now", utc(clock.instant())).update();
    }

    private void publishLease(PublishedBlob blob, UUID token) {
        jdbc.sql("UPDATE yumpoo.attachment_blob SET operation_type='PUBLISH',operation_owner='new-publisher',operation_token=:token,operation_lease_until=:until WHERE storage_key=:key")
                .param("token", token).param("until", utc(clock.instant().plusSeconds(300))).param("key", blob.storageKey()).update();
    }

    private long ownedRows() {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM yumpoo.attachment WHERE company_id=:company AND quota_project_id=:project)
                     + (SELECT count(*) FROM yumpoo.attachment_scan_task WHERE company_id=:company)
                     + (SELECT count(*) FROM yumpoo.attachment_reconciliation_issue WHERE company_id=:company)
                     + (SELECT count(*) FROM yumpoo.attachment_quota_usage WHERE company_id=:company AND scope_type='PROJECT' AND scope_id=:project)
                """).param("company", company).param("project", project).query(Long.class).single();
    }
    private long ownedAttachments(UUID selectedCompany, UUID selectedProject) {
        return jdbc.sql("SELECT count(*) FROM yumpoo.attachment WHERE company_id=:company AND quota_project_id=:project")
                .param("company", selectedCompany).param("project", selectedProject).query(Long.class).single();
    }
    private long companyQuota(String column) {
        return jdbc.sql("SELECT " + column + " FROM yumpoo.attachment_quota_usage WHERE company_id=:company AND scope_type='COMPANY'")
                .param("company", company).query(Long.class).single();
    }
    private long projectQuotaCount(UUID selectedProject) {
        return jdbc.sql("SELECT count(*) FROM yumpoo.attachment_quota_usage WHERE company_id=:company AND scope_type='PROJECT' AND scope_id=:project")
                .param("company", company).param("project", selectedProject).query(Long.class).single();
    }
    private String registry(PublishedBlob blob) {
        return jdbc.sql("SELECT presence_status FROM yumpoo.attachment_blob WHERE storage_key=:key").param("key", blob.storageKey()).query(String.class).single();
    }
    private String operation(PublishedBlob blob) {
        return jdbc.sql("SELECT operation_type FROM yumpoo.attachment_blob WHERE storage_key=:key").param("key", blob.storageKey()).query(String.class).optional().orElse(null);
    }
    private Path blobPath(PublishedBlob blob) { return directory.resolve("blobs").resolve(blob.storageKey()); }
    private static OffsetDateTime utc(Instant value) { return value.atOffset(ZoneOffset.UTC); }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private MutableClock(Instant initial) { instant = initial; }
        private void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
