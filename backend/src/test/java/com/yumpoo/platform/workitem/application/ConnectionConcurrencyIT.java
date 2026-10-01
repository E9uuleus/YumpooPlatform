package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.yumpoo.platform.workitem.application.ConnectionFixture.*;
import static com.yumpoo.platform.workitem.application.WorkItemConnectionCommands.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "yumpoo.outbox.enabled=false")
class ConnectionConcurrencyIT {
    @Autowired private JdbcClient jdbc;
    @Autowired private WorkItemService items;
    @Autowired private ConnectColumnService columns;
    @Autowired private WorkItemConnectionService connections;
    @Autowired private ContentRepository contents;
    @Autowired private WorkItemLabelRepository labels;
    @Autowired private ObjectMapper json;
    @Autowired private PlatformTransactionManager transactionManager;
    private ConnectionFixture fixture;
    private TransactionTemplate tx;
    private CurrentActor actor;
    private Project first;
    private Project second;
    private WorkItemModels.WorkItemDetail left;
    private WorkItemModels.WorkItemDetail right;
    private ConnectColumnModels.Column forward;
    private ConnectColumnModels.Column backward;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        fixture = new ConnectionFixture(jdbc, items, columns, contents, labels, json);
        transaction(() -> {
            actor = fixture.user("并发操作者");
            first = fixture.project(actor, "项目 A");
            second = fixture.project(actor, "项目 B");
            left = fixture.item(actor, first, "来源 A");
            right = fixture.item(actor, second, "来源 B");
            forward = fixture.column(actor, first, "A 到 B", second.id());
            backward = fixture.column(actor, second, "B 到 A", first.id());
            return true;
        });
    }

    @Test
    void oppositeDirectionLinksAcquireProjectAndItemLocksInSameOrder() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier start = new CyclicBarrier(2);
            var a = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return transaction(() -> link(left.id(), forward.id(), right.id())); });
            var b = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return transaction(() -> link(right.id(), backward.id(), left.id())); });
            assertThat(a.get(15, TimeUnit.SECONDS).httpStatus()).isEqualTo(201);
            assertThat(b.get(15, TimeUnit.SECONDS).httpStatus()).isEqualTo(201);
            assertThat(activeConnections()).isEqualTo(2);
        }
    }

    @Test
    void deletingColumnAndCreatingConnectionNeverLeaveActiveConnectionsOnDeletedColumn() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier start = new CyclicBarrier(2);
            var creating = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try { return transaction(() -> link(left.id(), forward.id(), right.id())).httpStatus(); }
                catch (ApplicationException error) {
                    assertThat(error.errorCode().name()).isEqualTo("RESOURCE_NOT_FOUND");
                    return 404;
                }
            });
            var deleting = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return transaction(() -> columns.delete(new ConnectColumnCommands.Delete(actor, first.id(), forward.id(), 0,
                        UUID.randomUUID(), hash())).result());
            });
            assertThat(creating.get(15, TimeUnit.SECONDS)).isIn(201, 404);
            assertThat(deleting.get(15, TimeUnit.SECONDS).httpStatus()).isEqualTo(200);
            assertThat(activeConnections()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_connection WHERE column_id=:id AND delete_reason<>'COLUMN_DELETED'")
                    .param("id", forward.id()).query(Long.class).single()).isZero();
        }
    }

    @Test
    void archiveCommittedFirstIsRecheckedAfterWaitingForProjectLock() throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            var result = transaction(() -> {
                jdbc.sql("SELECT id FROM yumpoo.project WHERE id=:id FOR UPDATE").param("id", first.id()).query(UUID.class).single();
                var pending = pool.submit(() -> {
                    try { transaction(() -> link(left.id(), forward.id(), right.id())); return "created"; }
                    catch (ApplicationException error) { return error.reason(); }
                });
                awaitDatabaseLock("yumpoo.project");
                fixture.archive(first);
                return pending;
            });
            assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo("PROJECT_ARCHIVED");
            assertThat(activeConnections()).isZero();
        }
    }

    @Test
    void archiveWaitsForEarlierConnectionAndObservesCommittedFact() throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            var archive = transaction(() -> {
                link(left.id(), forward.id(), right.id());
                var pending = pool.submit(() -> transaction(() -> {
                    jdbc.sql("SELECT id FROM yumpoo.project WHERE id=:id FOR UPDATE").param("id", first.id()).query(UUID.class).single();
                    long count = activeConnections();
                    fixture.archive(first);
                    return count;
                }));
                awaitDatabaseLock("yumpoo.project");
                return pending;
            });
            assertThat(archive.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        }
    }

    @Test
    void targetListIsReloadedAfterWaitingForColumnUpdate() throws Exception {
        var third = transaction(() -> fixture.project(actor, "备用项目"));
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            var result = transaction(() -> {
                columns.update(new ConnectColumnCommands.Update(actor, first.id(), forward.id(), 0,
                        forward.name(), List.of(third.id())));
                var pending = pool.submit(() -> {
                    try { transaction(() -> link(left.id(), forward.id(), right.id())); return "created"; }
                    catch (ApplicationException error) { return error.fieldViolations().getFirst().code(); }
                });
                awaitDatabaseLock("work_item_connect_column");
                return pending;
            });
            assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo("CONNECT_TARGET_NOT_IN_COLUMN");
            assertThat(activeConnections()).isZero();
        }
    }

    @Test
    void membershipRevokedWhileWaitingIsRecheckedInsideTheCommandTransaction() throws Exception {
        var member = transaction(() -> {
            var user = fixture.user("即将移除的成员");
            fixture.member(first.id(), user);
            fixture.member(second.id(), user);
            return user;
        });
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            var result = transaction(() -> {
                jdbc.sql("SELECT id FROM yumpoo.project WHERE id=:id FOR UPDATE").param("id", first.id()).query(UUID.class).single();
                var pending = pool.submit(() -> {
                    try {
                        transaction(() -> connections.link(new Link(member, left.id(), forward.id(), right.id(), UUID.randomUUID(), hash())));
                        return "created";
                    } catch (ApplicationException error) { return error.errorCode().name(); }
                });
                awaitDatabaseLock("yumpoo.project");
                jdbc.sql("DELETE FROM yumpoo.project_membership WHERE project_id=:project AND user_id=:user")
                        .param("project", first.id()).param("user", member.userId()).update();
                return pending;
            });
            assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(activeConnections()).isZero();
        }
    }

    @Test
    void oppositeDirectionCreateAndConnectReuseTheCreationKernelWithoutDeadlock() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier start = new CyclicBarrier(2);
            var a = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return transaction(() -> connections.createConnected(new CreateConnected(actor, left.id(), forward.id(),
                        second.id(), "在 B 新建", null, UUID.randomUUID(), hash())).result());
            });
            var b = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return transaction(() -> connections.createConnected(new CreateConnected(actor, right.id(), backward.id(),
                        first.id(), "在 A 新建", null, UUID.randomUUID(), hash())).result());
            });
            assertThat(a.get(15, TimeUnit.SECONDS).httpStatus()).isEqualTo(201);
            assertThat(b.get(15, TimeUnit.SECONDS).httpStatus()).isEqualTo(201);
            assertThat(activeConnections()).isEqualTo(2);
        }
    }

    @Test
    void failedCreateAndConnectRollsBackTheNewItemEventAndIdempotencyRecord() {
        transaction(() -> {
            for (int i = 0; i < 50; i++) link(left.id(), forward.id(), fixture.item(actor, second, "填满连接" + i).id());
            return true;
        });
        long beforeItems = targetItemCount();
        long beforeEvents = fixture.eventCount("workitem.work_item_created");
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> transaction(() -> connections.createConnected(new CreateConnected(actor, left.id(),
                forward.id(), second.id(), "必须整体回滚", null, key, hash()))))
                .isInstanceOfSatisfying(ApplicationException.class, error -> assertThat(error.reason()).isEqualTo("CONNECTION_LIMIT"));
        assertThat(targetItemCount()).isEqualTo(beforeItems);
        assertThat(fixture.eventCount("workitem.work_item_created")).isEqualTo(beforeEvents);
        assertThat(activeConnections()).isEqualTo(50);
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.idempotency_record WHERE actor_user_id=:actor AND idempotency_key=:key")
                .param("actor", actor.userId()).param("key", key).query(Long.class).single()).isZero();
    }

    private StoredCommandResult link(UUID sourceId, UUID columnId, UUID targetId) {
        return connections.link(new Link(actor, sourceId, columnId, targetId, UUID.randomUUID(), hash())).result();
    }

    private <T> T transaction(Supplier<T> action) {
        try (var ignored = correlation()) {
            return tx.execute(status -> {
                jdbc.sql("SET LOCAL lock_timeout='8s'").update();
                return action.get();
            });
        }
    }

    private long activeConnections() {
        return jdbc.sql("SELECT count(*) FROM yumpoo.work_item_connection WHERE column_id IN (:ids) AND deleted_at IS NULL")
                .param("ids", List.of(forward.id(), backward.id())).query(Long.class).single();
    }

    private long targetItemCount() {
        return jdbc.sql("SELECT count(*) FROM yumpoo.work_item WHERE project_id=:id").param("id", second.id()).query(Long.class).single();
    }

    private void awaitDatabaseLock(String table) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            jdbc.sql("SELECT pg_stat_clear_snapshot()").query().singleRow();
            boolean waiting = jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid<>pg_backend_pid()
                        AND wait_event_type='Lock' AND query ILIKE :query)
                    """).param("query", "%" + table + "%").query(Boolean.class).single();
            if (waiting) return;
            try { Thread.sleep(10); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        }
        throw new AssertionError("concurrent command did not reach the expected database lock: " + table);
    }
}
