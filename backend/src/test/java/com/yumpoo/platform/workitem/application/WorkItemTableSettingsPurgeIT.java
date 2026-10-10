package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import com.yumpoo.platform.workitem.infrastructure.JdbcWorkItemProjectDataPurger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "yumpoo.outbox.enabled=false", "yumpoo.projects.deletion.purge-poll-delay=1d",
        "yumpoo.attachments.scan-enabled=false", "yumpoo.attachments.maintenance-initial-delay=1d"})
class WorkItemTableSettingsPurgeIT {
    private static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID WORKSPACE = UUID.fromString("a460aa25-7180-490b-ab14-f9ec09049024");
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private WorkItemTableSettingsService settings;
    @Autowired private JdbcWorkItemProjectDataPurger purger;
    private UUID project;
    private CurrentActor owner;
    private CurrentActor member;

    @BeforeEach
    void archivedProject() {
        project = UUID.randomUUID();
        owner = actor("表格设置负责人");
        member = actor("表格设置普通成员");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("""
                    INSERT INTO yumpoo.project(id,company_id,workspace_id,project_code,name,lifecycle,
                        owner_user_id,created_at,created_by_user_id,updated_at,updated_by_user_id,archived_at,
                        deletion_requested_at,deletion_requested_by,purge_after)
                    VALUES (:project,:company,:workspace,:code,'归档个人表格设置','ARCHIVED',:owner,
                        '2026-01-01T12:00:00Z',:owner,'2026-01-01T12:00:00Z',:owner,'2026-01-01T12:00:00Z',
                        '2026-01-02T12:00:00Z',:owner,'2026-01-03T12:00:00Z')
                    """).param("project", project).param("company", COMPANY).param("workspace", WORKSPACE)
                    .param("code", "SETTINGS_" + project.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT))
                    .param("owner", owner.userId()).update();
            for (CurrentActor actor : java.util.List.of(owner, member)) jdbc.sql("""
                    INSERT INTO yumpoo.project_membership(id,company_id,project_id,user_id,status,joined_at,joined_by_user_id)
                    VALUES (:id,:company,:project,:user,'ACTIVE','2026-01-01T12:00:00Z',:owner)
                    """).param("id", UUID.randomUUID()).param("company", COMPANY).param("project", project)
                    .param("user", actor.userId()).param("owner", owner.userId()).update();
        });
    }

    @AfterEach
    void cleanFixture() {
        if (project != null) new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM yumpoo.work_item_table_settings WHERE company_id=:company AND project_id=:project")
                    .param("company", COMPANY).param("project", project).update();
            jdbc.sql("DELETE FROM yumpoo.project_membership WHERE company_id=:company AND project_id=:project")
                    .param("company", COMPANY).param("project", project).update();
            jdbc.sql("DELETE FROM yumpoo.project WHERE company_id=:company AND id=:project")
                    .param("company", COMPANY).param("project", project).update();
        });
        var users = java.util.stream.Stream.of(owner, member).filter(java.util.Objects::nonNull)
                .map(CurrentActor::userId).toList();
        if (!users.isEmpty()) jdbc.sql("DELETE FROM yumpoo.identity_user WHERE company_id=:company AND id IN (:users)")
                .param("company", COMPANY).param("users", users).update();
    }

    @Test
    void archivedOwnerCanSavePreferencesButOrdinaryMemberCannot() {
        var saved = settings.update(owner, project, WorkItemTableSettingsModels.Write.defaults(), 0);
        assertThat(saved.updatedAt()).isNotNull();
        assertThat(settings.get(owner, project)).isEqualTo(saved);
        assertMissing(() -> settings.update(member, project, WorkItemTableSettingsModels.Write.defaults(), 0));
        assertThat(settingsCount()).isOne();
    }

    @Test
    void purgingAndDeletedProjectsRejectFirstPreferenceWrite() {
        markStarted();
        assertMissing(() -> settings.update(owner, project, WorkItemTableSettingsModels.Write.defaults(), 0));
        assertThat(settingsCount()).isZero();
        new TransactionTemplate(transactions).executeWithoutResult(status -> deleteProject());
        assertMissing(() -> settings.update(owner, project, WorkItemTableSettingsModels.Write.defaults(), 0));
        assertThat(settingsCount()).isZero();
    }

    @Test
    void writerWaitingOnPurgeCannotRecreatePreferencesAfterProjectDeletion() throws Exception {
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger writerPid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var clearing = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    markStarted();
                    deleteProject();
                    deleted.countDown();
                    await(commit);
                }));
                assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue();
                var writing = executor.submit(() -> {
                    try {
                        new TransactionTemplate(transactions).executeWithoutResult(status -> {
                            writerPid.set(backendPid());
                            settings.update(owner, project, WorkItemTableSettingsModels.Write.defaults(), 0);
                        });
                        return null;
                    } catch (ApplicationException failure) { return failure.errorCode(); }
                });
                awaitDatabaseLock(writerPid, "yumpoo.project");
                commit.countDown();
                clearing.get(10, TimeUnit.SECONDS);
                assertThat(writing.get(10, TimeUnit.SECONDS)).isEqualTo(StandardErrorCode.RESOURCE_NOT_FOUND);
                assertThat(settingsCount()).isZero();
            } finally { commit.countDown(); }
        }
    }

    @Test
    void purgeWaitsForAuthorizedPreferenceWriterAndThenRemovesItsResult() throws Exception {
        var saved = settings.update(owner, project, WorkItemTableSettingsModels.Write.defaults(), 0);
        long expected = saved.updatedAt().getEpochSecond() * 1_000_000 + saved.updatedAt().getNano() / 1_000;
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger writerPid = new AtomicInteger();
        AtomicInteger purgePid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(3)) {
            try {
                var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.sql("SELECT project_id FROM yumpoo.work_item_table_settings WHERE company_id=:company "
                            + "AND project_id=:project FOR UPDATE").param("company", COMPANY).param("project", project)
                            .query(UUID.class).single();
                    held.countDown();
                    await(release);
                }));
                assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
                var writing = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    writerPid.set(backendPid());
                    return settings.update(owner, project, WorkItemTableSettingsModels.Write.defaults(), expected);
                }));
                awaitDatabaseLock(writerPid, "yumpoo.work_item_table_settings");
                var clearing = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    purgePid.set(backendPid());
                    markStarted();
                    deleteProject();
                }));
                awaitDatabaseLock(purgePid, "yumpoo.project");
                release.countDown();
                blocker.get(10, TimeUnit.SECONDS);
                assertThat(writing.get(10, TimeUnit.SECONDS).updatedAt()).isAfter(saved.updatedAt());
                clearing.get(10, TimeUnit.SECONDS);
                assertThat(settingsCount()).isZero();
            } finally { release.countDown(); }
        }
    }

    private void markStarted() {
        jdbc.sql("UPDATE yumpoo.project SET purge_started_at=CURRENT_TIMESTAMP WHERE company_id=:company AND id=:project")
                .param("company", COMPANY).param("project", project).update();
    }

    private void deleteProject() {
        assertThat(purger.purgeBatch(COMPANY, project, 500)).isFalse();
        jdbc.sql("DELETE FROM yumpoo.project_membership WHERE company_id=:company AND project_id=:project")
                .param("company", COMPANY).param("project", project).update();
        jdbc.sql("DELETE FROM yumpoo.project WHERE company_id=:company AND id=:project")
                .param("company", COMPANY).param("project", project).update();
    }

    private CurrentActor actor(String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO yumpoo.identity_user(id,company_id,employment_status,account_status,display_name,
                    directory_synced_at,created_at,updated_at)
                VALUES (:id,:company,'ACTIVE','ENABLED',:name,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).param("id", id).param("company", COMPANY).param("name", name).update();
        return new CurrentActor(id, COMPANY, 0, Set.of());
    }

    private long settingsCount() {
        return jdbc.sql("SELECT count(*) FROM yumpoo.work_item_table_settings WHERE company_id=:company AND project_id=:project")
                .param("company", COMPANY).param("project", project).query(Long.class).single();
    }

    private int backendPid() { return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single(); }

    private void awaitDatabaseLock(AtomicInteger pid, String table) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            boolean waiting = jdbc.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=:pid "
                    + "AND wait_event_type='Lock' AND query ILIKE :query)")
                    .param("pid", pid.get()).param("query", "%" + table + "%").query(Boolean.class).single();
            if (waiting) return;
            Thread.sleep(10);
        }
        throw new AssertionError("writer did not reach database lock: " + table);
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static void assertMissing(Runnable command) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(ApplicationException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(StandardErrorCode.RESOURCE_NOT_FOUND));
    }
}
