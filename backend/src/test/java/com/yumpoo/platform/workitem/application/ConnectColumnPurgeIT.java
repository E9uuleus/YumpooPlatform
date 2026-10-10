package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import com.yumpoo.platform.workitem.infrastructure.JdbcWorkItemProjectDataPurger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.yumpoo.platform.workitem.application.ConnectionFixture.COMPANY;
import static com.yumpoo.platform.workitem.application.ConnectionFixture.correlation;
import static com.yumpoo.platform.workitem.application.ConnectionFixture.hash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "yumpoo.outbox.enabled=false", "yumpoo.projects.deletion.purge-poll-delay=1d",
        "yumpoo.attachments.scan-enabled=false", "yumpoo.attachments.maintenance-initial-delay=1d"})
class ConnectColumnPurgeIT {
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ConnectColumnService columns;
    @Autowired private JdbcWorkItemProjectDataPurger purger;
    @Autowired private ObjectMapper json;
    private CurrentActor owner;
    private UUID source;
    private UUID target;
    private ConnectColumnModels.Column baseline;

    @BeforeEach
    void activeProjectsWithColumn() {
        UUID user = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO yumpoo.identity_user(id,company_id,employment_status,account_status,display_name,
                    directory_synced_at,created_at,updated_at)
                VALUES (:user,:company,'ACTIVE','ENABLED','清除连接列负责人',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).param("user", user).param("company", COMPANY).update();
        owner = new CurrentActor(user, COMPANY, 0, Set.of());
        source = project("保留源项目");
        target = project("待清除目标项目");
        baseline = create("已有连接列");
    }

    @AfterEach
    void cleanFixture() {
        if (owner == null) return;
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (UUID id : java.util.stream.Stream.of(source, target).filter(java.util.Objects::nonNull).toList()) {
                markStarted(id);
                removeProject(id);
            }
            jdbc.sql("DELETE FROM yumpoo.outbox_consumer_receipt WHERE event_id IN "
                    + "(SELECT event_id FROM yumpoo.outbox_event WHERE actor_user_id=:owner)")
                    .param("owner", owner.userId()).update();
            jdbc.sql("DELETE FROM yumpoo.outbox_event WHERE actor_user_id=:owner").param("owner", owner.userId()).update();
            jdbc.sql("DELETE FROM yumpoo.identity_user WHERE company_id=:company AND id=:owner")
                    .param("company", COMPANY).param("owner", owner.userId()).update();
        });
    }

    @Test
    void archivedTargetCanBeRetainedButPurgingOrMissingTargetCannot() {
        archiveTarget();
        var retained = update("保留归档目标");
        assertThat(retained.targets()).singleElement().satisfies(value -> assertThat(value.lifecycle()).isEqualTo("ARCHIVED"));
        assertThatThrownBy(() -> create("新增归档目标")).isInstanceOfSatisfying(ApplicationException.class,
                failure -> assertThat(failure.reason()).isEqualTo("PROJECT_ARCHIVED"));
        baseline = retained;
        markStarted(target);
        assertMissing(() -> update(baseline.name()));
        assertMissing(() -> create("新增清除中目标"));
        new TransactionTemplate(transactions).executeWithoutResult(status -> removeProject(target));
        assertMissing(() -> create("新增已删除目标"));
        assertThat(targetRows()).isZero();
        assertThat(columns.catalog(owner, source).items()).singleElement().satisfies(column -> {
            assertThat(column.name()).isEqualTo("保留归档目标");
            assertThat(column.targets()).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void purgeCommittingFirstRejectsPendingCreateOrUpdate(boolean create) throws Exception {
        CountDownLatch removed = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger writerPid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var clearing = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    markStarted(target);
                    removeProject(target);
                    removed.countDown();
                    await(commit);
                }));
                assertThat(removed.await(10, TimeUnit.SECONDS)).isTrue();
                var writing = executor.submit(() -> {
                    try {
                        new TransactionTemplate(transactions).executeWithoutResult(status -> {
                            writerPid.set(backendPid());
                            write(create);
                        });
                        return null;
                    } catch (ApplicationException failure) { return failure.errorCode(); }
                });
                awaitDatabaseLock(writerPid, "yumpoo.project");
                commit.countDown();
                clearing.get(10, TimeUnit.SECONDS);
                assertThat(writing.get(10, TimeUnit.SECONDS)).isEqualTo(StandardErrorCode.RESOURCE_NOT_FOUND);
                assertThat(targetRows()).isZero();
                assertThat(columns.catalog(owner, source).items()).singleElement().satisfies(column -> {
                    assertThat(column.name()).isEqualTo(baseline.name());
                    assertThat(column.targets()).isEmpty();
                });
            } finally { commit.countDown(); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void writerLocksTargetBeforeCatalogAndPurgeWaitsThenDetachesItsResult(boolean create) throws Exception {
        if (!create) archiveTarget();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger writerPid = new AtomicInteger();
        AtomicInteger purgePid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(3)) {
            try {
                var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.sql("SELECT project_id FROM yumpoo.work_item_connect_column_catalog WHERE company_id=:company "
                            + "AND project_id=:source FOR UPDATE").param("company", COMPANY).param("source", source)
                            .query(UUID.class).single();
                    held.countDown();
                    await(release);
                }));
                assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
                var writing = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    writerPid.set(backendPid());
                    return write(create);
                }));
                awaitDatabaseLock(writerPid, "yumpoo.work_item_connect_column_catalog");
                var clearing = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    purgePid.set(backendPid());
                    markStarted(target);
                    removeProject(target);
                }));
                awaitDatabaseLock(purgePid, "yumpoo.project");
                release.countDown();
                blocker.get(10, TimeUnit.SECONDS);
                var saved = writing.get(10, TimeUnit.SECONDS);
                clearing.get(10, TimeUnit.SECONDS);
                assertThat(targetRows()).isZero();
                assertThat(columns.catalog(owner, source).items()).hasSize(create ? 2 : 1)
                        .allSatisfy(column -> assertThat(column.targets()).isEmpty())
                        .anySatisfy(column -> assertThat(column.name()).isEqualTo(saved.name()));
                assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.project WHERE id=:target")
                        .param("target", target).query(Long.class).single()).isZero();
            } finally { release.countDown(); }
        }
    }

    private ConnectColumnModels.Column write(boolean create) { return create ? create("迟到新建列") : update("迟到改名列"); }

    private ConnectColumnModels.Column create(String name) {
        try (var ignored = correlation()) {
            var result = columns.create(new ConnectColumnCommands.Create(owner, source, name, List.of(target), UUID.randomUUID(), hash()));
            return json.readValue(result.result().responseJson(), ConnectColumnModels.Column.class);
        }
    }

    private ConnectColumnModels.Column update(String name) {
        try (var ignored = correlation()) {
            return columns.update(new ConnectColumnCommands.Update(owner, source, baseline.id(), baseline.rowVersion(), name, List.of(target)));
        }
    }

    private UUID project(String name) {
        UUID id = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("""
                    INSERT INTO yumpoo.project(id,company_id,workspace_id,project_code,name,lifecycle,owner_user_id,
                        created_at,created_by_user_id,updated_at,updated_by_user_id)
                    SELECT :project,:company,id,:code,:name,'ACTIVE',:owner,'2026-01-01T12:00:00Z',:owner,
                        '2026-01-01T12:00:00Z',:owner FROM yumpoo.workspace WHERE company_id=:company AND code='MAIN'
                    """).param("project", id).param("company", COMPANY).param("owner", owner.userId()).param("name", name)
                    .param("code", "PURGECOL_" + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT)).update();
            jdbc.sql("""
                    INSERT INTO yumpoo.project_membership(id,company_id,project_id,user_id,status,joined_at,joined_by_user_id)
                    VALUES (:id,:company,:project,:owner,'ACTIVE',CURRENT_TIMESTAMP,:owner)
                    """).param("id", UUID.randomUUID()).param("company", COMPANY).param("project", id).param("owner", owner.userId()).update();
        });
        return id;
    }

    private void archiveTarget() {
        jdbc.sql("UPDATE yumpoo.project SET lifecycle='ARCHIVED',archived_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=:target")
                .param("target", target).update();
    }

    private void markStarted(UUID id) {
        jdbc.sql("""
                UPDATE yumpoo.project SET lifecycle='ARCHIVED',archived_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP,
                    deletion_requested_at='2026-01-02T12:00:00Z',deletion_requested_by=:owner,
                    purge_after='2026-01-03T12:00:00Z',purge_started_at=CURRENT_TIMESTAMP WHERE company_id=:company AND id=:project
                """).param("company", COMPANY).param("project", id).param("owner", owner.userId()).update();
    }

    private void removeProject(UUID id) {
        assertThat(purger.purgeBatch(COMPANY, id, 500)).isFalse();
        jdbc.sql("DELETE FROM yumpoo.project_membership WHERE company_id=:company AND project_id=:project")
                .param("company", COMPANY).param("project", id).update();
        jdbc.sql("DELETE FROM yumpoo.project WHERE company_id=:company AND id=:project")
                .param("company", COMPANY).param("project", id).update();
    }

    private long targetRows() {
        return jdbc.sql("SELECT count(*) FROM yumpoo.work_item_connect_column_target WHERE company_id=:company AND target_project_id=:target")
                .param("company", COMPANY).param("target", target).query(Long.class).single();
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
