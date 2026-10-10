package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.catalog.api.ProjectPurgeQueue;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.filestorage.application.PublishedBlob;
import com.yumpoo.platform.filestorage.application.QuarantineStorage;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import com.yumpoo.platform.workitem.application.ConnectColumnCommands;
import com.yumpoo.platform.workitem.application.ConnectColumnService;
import com.yumpoo.platform.workitem.application.DueTimeChange;
import com.yumpoo.platform.workitem.application.WorkItemCommands;
import com.yumpoo.platform.workitem.application.WorkItemConnectionCommands;
import com.yumpoo.platform.workitem.application.WorkItemConnectionService;
import com.yumpoo.platform.workitem.application.WorkItemService;
import com.yumpoo.platform.workitem.application.WorkItemUpdateCommands;
import com.yumpoo.platform.workitem.application.WorkItemUpdateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.yumpoo.platform.reporting.application.DashboardModels;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@Import({PostgreSqlTestContainerConfiguration.class, ProjectPurgeIT.TestClockConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "yumpoo.outbox.enabled=false", "yumpoo.projects.deletion.purge-poll-delay=24h",
        "yumpoo.attachments.scan-enabled=false", "yumpoo.attachments.maintenance-initial-delay=24h",
        "yumpoo.projects.deletion.purge-batch-size=500"})
class ProjectPurgeIT {
    private static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final Instant BASE = Instant.parse("2026-01-01T12:00:00Z");
    private static final int LARGE = 620;
    private static final List<String> STAGES = List.of("NOTIFICATION", "AUDIT", "FILESTORAGE", "OUTBOX", "WORKITEM", "REPORTING", "CATALOG");
    private static final List<String> ITEM_TABLES = List.of("work_item_time_session", "work_item_time_revision",
            "work_item_update", "work_item_assignee", "work_item_connect_column", "work_item_connect_column_catalog",
            "work_item_table_settings", "work_item", "project_work_item_label_catalog", "work_item_project_order",
            "work_item_rank_lane", "work_item_project_counter", "content", "content_catalog_version");
    private static final String OUTBOX_SCOPE = "e.company_id=:company AND (e.aggregate_id=:project OR "
            + "e.payload_json->>'projectId'=CAST(:project AS text) OR e.payload_json->>'sourceProjectId'=CAST(:project AS text) "
            + "OR e.payload_json->>'targetProjectId'=CAST(:project AS text) OR e.payload_json->>'leftProjectId'=CAST(:project AS text) "
            + "OR e.payload_json->>'rightProjectId'=CAST(:project AS text))";

    @Autowired private JdbcClient jdbc;
    @Autowired private ProjectPurgeService purge;
    @Autowired private ProjectPurgeQueue queue;
    @Autowired private ProjectCreationOrchestrator creation;
    @Autowired private ProjectDeletionOperations deletion;
    @Autowired private WorkItemService items;
    @Autowired private WorkItemUpdateService discussions;
    @Autowired private ConnectColumnService columns;
    @Autowired private WorkItemConnectionService connections;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ObjectMapper json;
    @Autowired private MutableClock clock;
    @Autowired private QuarantineStorage storage;
    @TempDir static Path directory;
    private CurrentActor firstAdmin;
    private CurrentActor secondAdmin;

    record Project(UUID id, String code, UUID contentId, UUID itemId) {}

    @DynamicPropertySource
    static void storageRoots(DynamicPropertyRegistry properties) {
        properties.add("yumpoo.attachments.attachment-root", () -> directory.resolve("blobs").toString());
        properties.add("yumpoo.attachments.upload-temp-root", () -> directory.resolve("quarantine").toString());
    }

    @BeforeEach
    void prepareIsolatedDatabase() {
        clock.set(BASE);
        jdbc.sql("TRUNCATE yumpoo.project_purge_run, yumpoo.project, yumpoo.personal_dashboard, "
                + "yumpoo.notification_event, yumpoo.activity_event, yumpoo.work_item_cell_activity, "
                + "yumpoo.outbox_event, yumpoo.security_audit_event, yumpoo.admin_override, "
                + "yumpoo.attachment_project_purge, yumpoo.attachment, yumpoo.attachment_blob, "
                + "yumpoo.attachment_quota_usage CASCADE").update();
        firstAdmin = administrator("清除管理员甲");
        secondAdmin = administrator("清除管理员乙");
    }

    @Test
    void boundedStagesRemoveAllProjectBusinessDataButPreservePeersSharedStateAndSecurityHistory() throws IOException {
        var target = project(firstAdmin, "待清除项目");
        var peer = project(secondAdmin, "保留项目");
        var third = project(secondAdmin, "保留第三项目");
        member(target.id(), secondAdmin);
        member(peer.id(), firstAdmin);
        member(third.id(), firstAdmin);
        UUID outward = column(firstAdmin, target, "待清除出站", List.of(peer.id(), third.id()));
        UUID inward = column(secondAdmin, peer, "保留连接列", List.of(target.id(), third.id()));
        UUID singleTarget = column(secondAdmin, peer, "唯一目标连接列", List.of(target.id()));
        link(firstAdmin, target.itemId(), outward, peer.itemId());
        link(firstAdmin, peer.itemId(), inward, target.itemId());
        UUID preservedConnection = link(firstAdmin, peer.itemId(), inward, third.itemId());
        UUID removedRelation = relation(target, peer);
        UUID preservedRelation = relation(peer, third);
        var privateBlob = blob();
        var sharedBlob = blob();
        attachment(target, privateBlob);
        attachment(target, sharedBlob);
        UUID retainedAttachment = attachment(peer, sharedBlob);
        var retainedAttachmentBefore = row("attachment", retainedAttachment);
        long peerCatalogBefore = catalogVersion(peer);
        seedLargeWorkFacts(target);
        seedNotifications(target, LARGE);
        seedActivity(target, LARGE);
        seedActivity(peer, 1);
        seedOutbox(target, LARGE);
        seedDashboards(target, peer, LARGE);
        seedMemberships(target, LARGE);
        seedTimers(target, peer);
        UUID override = governanceHistory(target);
        var peerBefore = row("project", peer.id());
        var thirdBefore = row("project", third.id());
        var peerItemBefore = row("work_item", peer.itemId());
        var thirdItemBefore = row("work_item", third.itemId());
        scheduleDue(target);
        terminalOutbox();
        var securityBefore = jdbc.sql("SELECT * FROM yumpoo.security_audit_event WHERE target_id=:id ORDER BY id")
                .param("id", target.id().toString()).query().listOfRows();
        var overrideBefore = row("admin_override", override);
        Map<String, Integer> batches = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();

        for (int attempt = 0; attempt < 100 && exists("project", target.id()); attempt++) {
            var lease = purge.claim("bounded-worker").orElseThrow();
            assertThat(lease.projectId()).isEqualTo(target.id());
            seen.add(lease.stage());
            batches.merge(lease.stage(), 1, Integer::sum);
            long before = businessRows(lease.stage(), target);
            assertThat(correlated(() -> purge.process(lease))).isTrue();
            long after = businessRows(lease.stage(), target);
            assertThat(before - after).as("%s 本批实际删除或修改行数", lease.stage()).isBetween(0L, 500L);
            if ("CATALOG".equals(lease.stage())) correlated(() -> purge.complete(lease));
        }

        assertThat(exists("project", target.id())).isFalse();
        assertThat(seen).containsExactlyElementsOf(STAGES);
        for (String stage : List.of("NOTIFICATION", "AUDIT", "OUTBOX", "WORKITEM", "REPORTING", "CATALOG"))
            assertThat(batches.get(stage)).as(stage).isGreaterThan(1);
        for (String stage : STAGES) assertThat(businessRows(stage, target)).as(stage).isZero();
        assertThat(row("project", peer.id())).isEqualTo(peerBefore);
        assertThat(row("project", third.id())).isEqualTo(thirdBefore);
        assertThat(row("work_item", peer.itemId())).isEqualTo(peerItemBefore);
        assertThat(row("work_item", third.itemId())).isEqualTo(thirdItemBefore);
        assertThat(exists("work_item_connection", preservedConnection)).isTrue();
        assertThat(exists("work_item_relation", preservedRelation)).isTrue();
        assertThat(exists("work_item_relation", removedRelation)).isFalse();
        assertThat(jdbc.sql("SELECT target_project_id FROM yumpoo.work_item_connect_column_target WHERE column_id=:id")
                .param("id", inward).query(UUID.class).list()).containsExactly(third.id());
        var retainedColumns = columns.catalog(secondAdmin, peer.id()).items();
        assertThat(retainedColumns).filteredOn(column -> column.id().equals(singleTarget)).singleElement().satisfies(column -> {
            assertThat(column.targets()).isEmpty();
            assertThat(column.rowVersion()).isOne();
        });
        assertThat(retainedColumns).filteredOn(column -> column.id().equals(inward)).singleElement().satisfies(column -> {
            assertThat(column.targets()).extracting(value -> value.projectId()).containsExactly(third.id());
            assertThat(column.rowVersion()).isOne();
        });
        assertThat(catalogVersion(peer)).isGreaterThan(peerCatalogBefore);
        assertThat(Files.exists(directory.resolve("blobs").resolve(privateBlob.storageKey()))).isFalse();
        assertThat(jdbc.sql("SELECT presence_status FROM yumpoo.attachment_blob WHERE storage_key=:key")
                .param("key", privateBlob.storageKey()).query(String.class).single()).isEqualTo("DELETED");
        assertThat(storage.verify(sharedBlob)).isTrue();
        assertThat(row("attachment", retainedAttachment)).isEqualTo(retainedAttachmentBefore);
        assertThat(jdbc.sql("SELECT available_bytes FROM yumpoo.attachment_quota_usage WHERE scope_type='COMPANY' AND company_id=:company")
                .param("company", COMPANY).query(Long.class).single()).isEqualTo(sharedBlob.sizeBytes());
        assertThat(jdbc.sql("SELECT reserved_bytes FROM yumpoo.attachment_quota_usage WHERE scope_type='COMPANY' AND company_id=:company")
                .param("company", COMPANY).query(Long.class).single()).isZero();
        assertThat(count("SELECT count(*) FROM yumpoo.work_item_timer_state WHERE user_id=:user", firstAdmin.userId())).isOne();
        assertThat(count("SELECT count(*) FROM yumpoo.work_item_timer_state WHERE user_id=:user", secondAdmin.userId())).isZero();
        assertThat(jdbc.sql("SELECT row_version FROM yumpoo.work_item_timer_state WHERE user_id=:user")
                .param("user", firstAdmin.userId()).query(Long.class).single()).isEqualTo(7);
        assertThat(scopeCount("SELECT count(*) FROM yumpoo.work_item_time_session WHERE project_id=:project", peer)).isOne();
        assertThat(scopeCount("SELECT count(*) FROM yumpoo.activity_event WHERE scope_id=:project", peer)).isOne();
        var dashboards = jdbc.sql("SELECT configuration::text,row_version FROM yumpoo.personal_dashboard ORDER BY id").query().listOfRows();
        assertThat(dashboards).hasSize(LARGE).allSatisfy(row -> {
            var configuration = json.readTree((String) row.get("configuration"));
            assertThat(configuration.path("projectIds").toString()).isEqualTo("[\"" + peer.id() + "\"]");
            for (String pointer : List.of("/filters", "/widgets/0/chart", "/widgets/0/chart/filters"))
                assertThat(configuration.at(pointer).path("projectIds").toString()).isEqualTo("[\"" + peer.id() + "\"]");
            assertThat(configuration.path("widgets").get(0).path("title").asText()).isEqualTo("保留图表标题");
            assertThat(row.get("row_version")).isEqualTo(1L);
        });
        assertThat(row("admin_override", override)).isEqualTo(overrideBefore);
        assertThat(jdbc.sql("SELECT * FROM yumpoo.security_audit_event WHERE target_id=:id ORDER BY id")
                .param("id", target.id().toString()).query().listOfRows()).containsAll(securityBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.security_audit_event WHERE action='PROJECT_PURGED' AND target_id=:id")
                .param("id", target.id().toString()).query(Long.class).single()).isOne();
        var run = jdbc.sql("SELECT * FROM yumpoo.project_purge_run WHERE project_id=:project")
                .param("project", target.id()).query().singleRow();
        assertThat(run.get("stage")).isEqualTo("COMPLETED");
        assertThat(run.get("completed_at")).isNotNull();
        for (String field : List.of("cursor_value", "lease_owner", "lease_token", "lease_until")) assertThat(run.get(field)).isNull();
        assertThat(run.get("counts").toString()).isEqualTo("{}");
        assertThat(purge.claim("after-completion")).isEmpty();
    }

    @Test
    void expiredLeasesResumeAtDurableStageWithNewTokenAndRejectOldWrites() {
        var target = project(firstAdmin, "过期租约");
        seedNotifications(target, LARGE);
        scheduleDue(target);
        var old = purge.claim("crashed-worker").orElseThrow();
        long before = businessRows("NOTIFICATION", target);
        clock.set(old.until());
        assertThat(purge.process(old)).isFalse();
        assertThat(businessRows("NOTIFICATION", target)).isEqualTo(before);
        var resumed = purge.claim("restarted-worker").orElseThrow();
        assertThat(resumed.projectId()).isEqualTo(old.projectId());
        assertThat(resumed.stage()).isEqualTo(old.stage());
        assertThat(resumed.token()).isNotEqualTo(old.token());
        assertThat(queue.release(old, clock.instant())).isFalse();
        assertThat(queue.advance(old, "AUDIT", clock.instant())).isFalse();
        assertThat(purge.process(resumed)).isTrue();
        assertThat(before - businessRows("NOTIFICATION", target)).isEqualTo(500);
        var next = purge.claim("second-restart").orElseThrow();
        assertThat(next.stage()).isEqualTo("NOTIFICATION");
        assertThat(next.token()).isNotEqualTo(resumed.token());
    }

    @Test
    void releasedTokenCannotDeleteBusinessRowsAfterAnotherWorkerClaimsTheRun() {
        var target = project(firstAdmin, "陈旧令牌");
        seedNotifications(target, LARGE);
        scheduleDue(target);
        var old = purge.claim("first-worker").orElseThrow();
        assertThat(queue.release(old, clock.instant())).isTrue();
        var replacement = purge.claim("second-worker").orElseThrow();
        long before = businessRows("NOTIFICATION", target);
        assertThat(clock.instant()).isBefore(old.until());
        assertThat(replacement.token()).isNotEqualTo(old.token());
        assertThat(purge.process(old)).isFalse();
        assertThat(businessRows("NOTIFICATION", target)).isEqualTo(before);
        assertThat(jdbc.sql("SELECT lease_token FROM yumpoo.project_purge_run WHERE project_id=:project")
                .param("project", target.id()).query(UUID.class).single()).isEqualTo(replacement.token());
    }

    @Test
    void twoAdministratorsCanOnlyClaimOneLeaseForTheSameProject() throws Exception {
        var target = project(firstAdmin, "竞争领取");
        scheduleDue(target);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { await(start); return purge.claim(firstAdmin.userId().toString()); });
            var second = pool.submit(() -> { await(start); return purge.claim(secondAdmin.userId().toString()); });
            start.countDown();
            List<Optional<ProjectPurgeQueue.Lease>> claims = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(claims.stream().filter(Optional::isPresent)).hasSize(1);
            assertThat(scopeCount("SELECT count(*) FROM yumpoo.project_purge_run WHERE project_id=:project", target)).isOne();
            assertThat(jdbc.sql("SELECT purge_started_at FROM yumpoo.project WHERE id=:project")
                    .param("project", target.id()).query((rs, row) -> rs.getTimestamp(1).toInstant()).single()).isEqualTo(clock.instant());
        }
    }

    @Test
    void competingAdministratorCancellationsProduceOneCancellationFact() throws Exception {
        var target = project(firstAdmin, "竞争撤销");
        long version = scheduleDue(target);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { await(start); return cancelResult(firstAdmin, target, version); });
            var second = pool.submit(() -> { await(start); return cancelResult(secondAdmin, target, version); });
            start.countDown();
            var outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("CANCELLED", "VERSION_CONFLICT");
        }
        assertThat(scopeCount("SELECT count(*) FROM yumpoo.project_purge_run WHERE project_id=:project", target)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.security_audit_event WHERE action='PROJECT_DELETION_CANCELLED' AND target_id=:id")
                .param("id", target.id().toString()).query(Long.class).single()).isOne();
        assertThat(purge.claim("cancelled-project")).isEmpty();
    }

    @Test
    void cancellationHoldingProjectLockMakesPurgeSkipUntilCancellationCommits() throws Exception {
        var target = project(firstAdmin, "撤销优先");
        long version = scheduleDue(target);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var cancelled = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                jdbc.sql("SELECT id FROM yumpoo.project WHERE id=:project FOR UPDATE").param("project", target.id()).query(UUID.class).single();
                locked.countDown();
                await(release);
                return cancelResult(secondAdmin, target, version);
            }));
            try {
                await(locked);
                assertThat(purge.claim("purge-while-cancelling")).isEmpty();
            } finally { release.countDown(); }
            assertThat(cancelled.get(15, TimeUnit.SECONDS)).isEqualTo("CANCELLED");
        }
        assertThat(purge.claim("after-cancel")).isEmpty();
    }

    @Test
    void purgeStartHoldingProjectLockPreventsCancellationFromRestoringDeletionState() throws Exception {
        var target = project(firstAdmin, "清除优先");
        long version = scheduleDue(target);
        var claimed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelEntered = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var claim = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var lease = purge.claim("purge-start-winner").orElseThrow();
                claimed.countDown();
                await(release);
                return lease;
            }));
            await(claimed);
            var cancel = pool.submit(() -> { cancelEntered.countDown(); return cancelResult(secondAdmin, target, version); });
            try {
                await(cancelEntered);
                awaitBlockedProjectLock();
                assertThat(cancel.isDone()).isFalse();
            } finally { release.countDown(); }
            assertThat(claim.get(15, TimeUnit.SECONDS).projectId()).isEqualTo(target.id());
            assertThat(cancel.get(15, TimeUnit.SECONDS)).isEqualTo("RESOURCE_NOT_FOUND");
        }
        assertThat(scopeCount("SELECT count(*) FROM yumpoo.project WHERE id=:project AND purge_started_at IS NOT NULL AND deletion_requested_at IS NOT NULL", target)).isOne();
    }

    @Test
    void pendingOutboxRunDoesNotStarveAnotherNewlyDueProject() {
        var blocked = project(firstAdmin, "待投影阻塞");
        scheduleDue(blocked);
        for (int i = 0; i < 12; i++) {
            var lease = purge.claim("blocked-worker").orElseThrow();
            assertThat(purge.process(lease)).isTrue();
            if ("OUTBOX".equals(lease.stage())) break;
        }
        assertThat(jdbc.sql("SELECT stage FROM yumpoo.project_purge_run WHERE project_id=:project")
                .param("project", blocked.id()).query(String.class).single()).isEqualTo("OUTBOX");
        var next = project(secondAdmin, "新到期项目");
        scheduleDue(next);
        List<UUID> claimed = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            var lease = purge.claim("fair-worker").orElseThrow();
            claimed.add(lease.projectId());
            purge.process(lease);
            if (lease.projectId().equals(next.id())) break;
        }
        assertThat(claimed).contains(next.id());
        assertThat(jdbc.sql("SELECT stage FROM yumpoo.project_purge_run WHERE project_id=:project")
                .param("project", blocked.id()).query(String.class).single()).isEqualTo("OUTBOX");
    }

    @Test
    void connectColumnCannotRetainPurgingTargetWhenUpdatingItsName() {
        var target = project(firstAdmin, "将清除目标");
        var peer = project(secondAdmin, "活动来源项目");
        UUID column = column(secondAdmin, peer, "现有连接", List.of(target.id()));
        scheduleDue(target);
        assertThat(purge.claim("connection-guard-worker")).isPresent();

        assertThatThrownBy(() -> columns.update(new ConnectColumnCommands.Update(secondAdmin, peer.id(), column, 0,
                "修改列名称", List.of(target.id())))).isInstanceOfSatisfying(ApplicationException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(StandardErrorCode.RESOURCE_NOT_FOUND));
        assertThat(row("work_item_connect_column", column).get("row_version")).isEqualTo(0L);
        assertThat(row("work_item_connect_column", column).get("name")).isEqualTo("现有连接");
    }

    private CurrentActor administrator(String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO yumpoo.identity_user(id,company_id,employment_status,account_status,display_name,directory_synced_at,row_version,created_at,updated_at)
                VALUES (:id,:company,'ACTIVE','ENABLED',:name,:now,0,:now,:now)
                """).param("id", id).param("company", COMPANY).param("name", name).param("now", utc(clock.instant())).update();
        jdbc.sql("""
                INSERT INTO yumpoo.platform_role_assignment(id,company_id,user_id,role_code,scope_type,scope_id,status,
                    granted_by_actor_type,granted_by_system_code,grant_reason,granted_at,created_at,updated_at)
                VALUES (:id,:company,:user,'COMPANY_ADMIN','COMPANY',:company,'ACTIVE','SYSTEM','TEST_FIXTURE','purge test',:now,:now,:now)
                """).param("id", UUID.randomUUID()).param("company", COMPANY).param("user", id).param("now", utc(clock.instant())).update();
        return new CurrentActor(id, COMPANY, 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));
    }

    private Project project(CurrentActor owner, String name) {
        UUID id = correlated(() -> creation.create(new ProjectCreationCommand(owner, name, null, UUID.randomUUID(), hash(), "WEB", "test"))).result().resourceId();
        String code = jdbc.sql("SELECT project_code FROM yumpoo.project WHERE id=:project").param("project", id).query(String.class).single();
        UUID content = jdbc.sql("SELECT id FROM yumpoo.content WHERE project_id=:project AND code='TASKS'").param("project", id).query(UUID.class).single();
        UUID item = correlated(() -> items.create(new WorkItemCommands.Create(owner, id, content, name + "工作项", null,
                owner.userId(), null, null, null, null, null, UUID.randomUUID(), hash(), DueTimeChange.unchanged()))).result().resourceId();
        return new Project(id, code, content, item);
    }

    private void member(UUID projectId, CurrentActor actor) {
        jdbc.sql("""
                INSERT INTO yumpoo.project_membership(id,company_id,project_id,user_id,status,joined_at,joined_by_user_id,row_version)
                VALUES (:id,:company,:project,:user,'ACTIVE',:now,:user,0) ON CONFLICT DO NOTHING
                """).param("id", UUID.randomUUID()).param("company", COMPANY).param("project", projectId)
                .param("user", actor.userId()).param("now", utc(clock.instant())).update();
    }

    private UUID column(CurrentActor actor, Project project, String name, List<UUID> targets) {
        return correlated(() -> columns.create(new ConnectColumnCommands.Create(actor, project.id(), name, targets, UUID.randomUUID(), hash())))
                .result().resourceId();
    }

    private long catalogVersion(Project project) {
        return jdbc.sql("SELECT row_version FROM yumpoo.work_item_connect_column_catalog WHERE project_id=:project")
                .param("project", project.id()).query(Long.class).single();
    }

    private PublishedBlob blob() throws IOException {
        byte[] bytes = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
        var sealed = storage.receive(UUID.randomUUID(), new ByteArrayInputStream(bytes), OptionalLong.of(bytes.length));
        var blob = storage.publish(sealed);
        jdbc.sql("INSERT INTO yumpoo.attachment_blob(storage_key,sha256,size_bytes,created_at,updated_at) VALUES (:key,:sha,:size,:now,:now)")
                .param("key", blob.storageKey()).param("sha", blob.sha256()).param("size", blob.sizeBytes())
                .param("now", utc(clock.instant())).update();
        return blob;
    }

    private UUID attachment(Project project, PublishedBlob blob) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO yumpoo.attachment(id,company_id,quota_project_id,owner_type,owner_id,original_file_name,
                    file_extension,declared_mime,detected_mime,size_bytes,sha256,storage_key,status,reserved_bytes,
                    uploaded_by_user_id,intent_expires_at,available_at,created_at,updated_at)
                VALUES (:id,:company,:project,'WORK_ITEM',:item,'purge-evidence.txt','txt','text/plain','text/plain',
                    :size,:sha,:key,'AVAILABLE',0,:user,:expires,:now,:now,:now)
                """).param("id", id).param("company", COMPANY).param("project", project.id()).param("item", project.itemId())
                .param("size", blob.sizeBytes()).param("sha", blob.sha256()).param("key", blob.storageKey())
                .param("user", firstAdmin.userId()).param("expires", utc(clock.instant().plusSeconds(3600)))
                .param("now", utc(clock.instant())).update();
        for (String scope : List.of("COMPANY", "PROJECT")) {
            jdbc.sql("""
                    INSERT INTO yumpoo.attachment_quota_usage(company_id,scope_type,scope_id,available_bytes,updated_at)
                    VALUES (:company,:scope,:id,:size,:now) ON CONFLICT(company_id,scope_type,scope_id)
                    DO UPDATE SET available_bytes=yumpoo.attachment_quota_usage.available_bytes+:size
                    """).param("company", COMPANY).param("scope", scope).param("id", "COMPANY".equals(scope) ? COMPANY : project.id())
                    .param("size", blob.sizeBytes()).param("now", utc(clock.instant())).update();
        }
        return id;
    }

    private UUID link(CurrentActor actor, UUID source, UUID column, UUID target) {
        return correlated(() -> connections.link(new WorkItemConnectionCommands.Link(actor, source, column, target, UUID.randomUUID(), hash())))
                .result().resourceId();
    }

    private UUID relation(Project left, Project right) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO yumpoo.work_item_relation(id,company_id,relation_type,left_work_item_id,right_work_item_id,
                    left_project_id,right_project_id,created_by_user_id,created_at)
                VALUES (:id,:company,'RELATED',:left,:right,:leftProject,:rightProject,:actor,:now)
                """).param("id", id).param("company", COMPANY).param("left", left.itemId()).param("right", right.itemId())
                .param("leftProject", left.id()).param("rightProject", right.id()).param("actor", firstAdmin.userId())
                .param("now", utc(clock.instant())).update();
        return id;
    }

    private long scheduleDue(Project project) {
        jdbc.sql("UPDATE yumpoo.project SET lifecycle='ARCHIVED',archived_at=:now,updated_at=:now WHERE id=:project")
                .param("project", project.id()).param("now", utc(clock.instant())).update();
        long version = jdbc.sql("SELECT row_version FROM yumpoo.project WHERE id=:project").param("project", project.id()).query(Long.class).single();
        var result = correlated(() -> deletion.schedule(new ProjectDeletionOperation(firstAdmin, project.id(), version, UUID.randomUUID(), hash(), project.code())));
        clock.set(clock.instant().plus(Duration.ofDays(31)));
        return json.readTree(result.responseJson()).path("rowVersion").asLong();
    }

    private String cancelResult(CurrentActor actor, Project target, long version) {
        try {
            correlated(() -> deletion.cancel(new ProjectDeletionOperation(actor, target.id(), version, UUID.randomUUID(), hash(), null)));
            return "CANCELLED";
        } catch (ApplicationException failure) { return failure.errorCode().name(); }
    }

    private void seedLargeWorkFacts(Project project) {
        jdbc.sql("""
                INSERT INTO yumpoo.work_item(id,company_id,project_id,content_id,item_sequence,item_no,title,status_code,status_category,
                    priority,assignee_user_id,reporter_user_id,rank,project_sort_key,created_at,created_by_user_id,updated_at,updated_by_user_id)
                SELECT gen_random_uuid(),w.company_id,w.project_id,w.content_id,g+1,:code||'-'||(g+1),'分批事项'||g,w.status_code,w.status_category,
                    w.priority,w.assignee_user_id,w.reporter_user_id,lpad(g::text,39,'0'),lpad(g::text,39,'0'),w.created_at,w.created_by_user_id,w.updated_at,w.updated_by_user_id
                FROM yumpoo.work_item w CROSS JOIN generate_series(1,:large) g WHERE w.id=:item
                """).param("code", project.code()).param("large", LARGE).param("item", project.itemId()).update();
        jdbc.sql("UPDATE yumpoo.work_item_project_counter SET last_sequence=:last WHERE project_id=:project")
                .param("last", LARGE + 1).param("project", project.id()).update();
        jdbc.sql("""
                INSERT INTO yumpoo.work_item_assignee(company_id,project_id,work_item_id,user_id,position)
                SELECT company_id,project_id,id,assignee_user_id,0 FROM yumpoo.work_item WHERE project_id=:project ON CONFLICT DO NOTHING
                """).param("project", project.id()).update();
        UUID root = correlated(() -> discussions.publish(new WorkItemUpdateCommands.Publish(firstAdmin, project.itemId(), "<p>主评论</p>", null, UUID.randomUUID(), hash())))
                .result().resourceId();
        jdbc.sql("""
                INSERT INTO yumpoo.work_item_update(id,company_id,project_id,work_item_id,author_user_id,author_display_name,
                    body_html,body_text,status,created_at,parent_update_id)
                SELECT gen_random_uuid(),company_id,project_id,work_item_id,author_user_id,author_display_name,
                    '<p>回复</p>','回复','PUBLISHED',created_at,:root FROM yumpoo.work_item_update CROSS JOIN generate_series(1,:large) g WHERE id=:root
                """).param("root", root).param("large", LARGE).update();
        jdbc.sql("""
                INSERT INTO yumpoo.work_item_update_mention(update_id,company_id,mentioned_user_id,mentioned_display_name,created_at)
                SELECT id,company_id,:user,'被提及管理员',created_at FROM yumpoo.work_item_update WHERE parent_update_id=:root
                """).param("root", root).param("user", secondAdmin.userId()).update();
    }

    private void seedNotifications(Project project, int recipients) {
        UUID event = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO yumpoo.notification_event(id,company_id,source_event_id,event_type,payload_schema_version,target_kind,project_id,actor_user_id,occurred_at)
                VALUES (:id,:company,:source,'catalog.project_deletion_scheduled',1,'PROJECT',:project,:actor,:now)
                """).param("id", event).param("company", COMPANY).param("source", UUID.randomUUID()).param("project", project.id())
                .param("actor", firstAdmin.userId()).param("now", utc(clock.instant())).update();
        jdbc.sql("""
                INSERT INTO yumpoo.user_notification(id,company_id,notification_event_id,recipient_user_id,reason)
                SELECT gen_random_uuid(),:company,:event,gen_random_uuid(),'PROJECT_DELETION_SCHEDULED' FROM generate_series(1,:large)
                """).param("company", COMPANY).param("event", event).param("large", recipients).update();
        jdbc.sql("""
                INSERT INTO yumpoo.project_notification_preference(company_id,project_id,user_id,mode,notify_mention,notify_comment,notify_assigned,notify_connection_created)
                VALUES (:company,:project,:user,'ALL',true,true,true,true)
                """).param("company", COMPANY).param("project", project.id()).param("user", firstAdmin.userId()).update();
    }

    private void seedActivity(Project project, int count) {
        jdbc.sql("""
                INSERT INTO yumpoo.activity_event(id,event_id,projection_code,company_id,scope_type,scope_id,entity_type,entity_id,
                    event_type,actor_type,actor_user_id,actor_display_name,occurred_at,template_code,entity_version,request_id,correlation_id,primary_work_item_id)
                SELECT gen_random_uuid(),gen_random_uuid(),'ACTIVITY_V1',:company,'PROJECT',:project,'WORK_ITEM',:item,
                    'workitem.work_item_created','USER',:actor,'测试管理员',:now,'WORK_ITEM_CREATED',0,'purge-it','purge-it',:item FROM generate_series(1,:count)
                """).param("company", COMPANY).param("project", project.id()).param("item", project.itemId())
                .param("actor", firstAdmin.userId()).param("now", utc(clock.instant())).param("count", count).update();
        jdbc.sql("""
                INSERT INTO yumpoo.work_item_cell_activity(id,event_id,company_id,project_id,work_item_id,content_id,content_display_name,
                    event_type,column_code,change_type,actor_type,actor_user_id,actor_display_name,occurred_at,request_id,correlation_id)
                VALUES (gen_random_uuid(),gen_random_uuid(),:company,:project,:item,:content,'任务','workitem.work_item_created',
                    'WORK_ITEM_NAME','CREATED','USER',:actor,'测试管理员',:now,'purge-it','purge-it')
                """).param("company", COMPANY).param("project", project.id()).param("item", project.itemId()).param("content", project.contentId())
                .param("actor", firstAdmin.userId()).param("now", utc(clock.instant())).update();
    }

    private void seedOutbox(Project project, int count) {
        jdbc.sql("""
                INSERT INTO yumpoo.outbox_event(event_id,event_type,event_version,aggregate_type,aggregate_id,aggregate_version,company_id,
                    actor_type,actor_user_id,occurred_at,request_id,correlation_id,payload_json,status,created_at,completed_at)
                SELECT gen_random_uuid(),'test.project_purge_seed',1,'Project',:project,g,:company,'USER',:actor,:now,
                    'purge-it','purge-it',jsonb_build_object('projectId',CAST(:project AS text)),'COMPLETED',:now,:now FROM generate_series(1,:count) g
                """).param("project", project.id()).param("company", COMPANY).param("actor", firstAdmin.userId())
                .param("now", utc(clock.instant())).param("count", count).update();
        jdbc.sql("""
                INSERT INTO yumpoo.outbox_consumer_receipt(consumer_name,event_id,completed_at)
                SELECT 'purge_fixture',event_id,completed_at FROM yumpoo.outbox_event WHERE event_type='test.project_purge_seed'
                    AND aggregate_id=:project
                """).param("project", project.id()).update();
    }

    @Test
    void purgeFindsProjectReferencesPresentOnlyInNestedDashboardFiltersAndCharts() {
        var target = project(firstAdmin, "嵌套待清除项目");
        var peer = project(firstAdmin, "嵌套保留项目");
        for (String selected : List.of("/filters", "/widgets/0/chart", "/widgets/0/chart/filters")) {
            var configuration = dashboardConfiguration(target, peer);
            configuration.set("projectIds", json.valueToTree(List.of(peer.id())));
            for (String pointer : List.of("/filters", "/widgets/0/chart", "/widgets/0/chart/filters")) {
                ((ObjectNode) configuration.at(pointer)).set("projectIds", json.valueToTree(
                        pointer.equals(selected) ? List.of(target.id(), peer.id()) : List.of(peer.id())));
            }
            assertThat(json.treeToValue(configuration, DashboardModels.Configuration.class).widgets()).hasSize(1);
            insertDashboards(configuration, 1);
        }
        scheduleDue(target);
        terminalOutbox();
        for (int attempt = 0; attempt < 40 && exists("project", target.id()); attempt++) {
            var lease = purge.claim("nested-dashboard-worker").orElseThrow();
            correlated(() -> purge.process(lease));
            if ("CATALOG".equals(lease.stage())) correlated(() -> purge.complete(lease));
        }
        assertThat(exists("project", target.id())).isFalse();
        var rows = jdbc.sql("SELECT configuration::text,row_version FROM yumpoo.personal_dashboard").query().listOfRows();
        assertThat(rows).hasSize(3).allSatisfy(row -> {
            assertThat((String) row.get("configuration")).doesNotContain(target.id().toString()).contains(peer.id().toString());
            assertThat(row.get("row_version")).isEqualTo(1L);
            assertThat(json.readTree((String) row.get("configuration")).path("widgets").get(0).path("title").asText())
                    .isEqualTo("保留图表标题");
        });
    }

    private ObjectNode dashboardConfiguration(Project target, Project peer) {
        var configuration = (ObjectNode) json.readTree("""
                {"projectIds":[],"filters":{"projectIds":[],"includeArchived":false,"hasTime":false},
                 "widgets":[{"id":"%s","kind":"CHART","title":"保留图表标题","metric":"TOTAL",
                    "grouping":"STATUS","sort":"DESC","showLegend":true,"showValues":true,
                    "wide":{"x":0,"y":0,"w":2,"h":3},"medium":{"x":0,"y":0,"w":2,"h":3},
                    "chart":{"type":"COLUMN","dimension":"STATUS","series":"NONE","dateInterval":"MONTH",
                        "timezone":"Asia/Shanghai","measure":{"metric":"TOTAL","calculation":"SUM"},
                        "stacked":false,"showLegend":true,"showValues":true,"valueFormat":"VALUE","sort":"VALUE_DESC",
                        "limit":0,"showEmpty":true,"projectIds":[],"filters":{"projectIds":[],"includeArchived":false,
                            "hasTime":false},"labels":[],"detailColumns":["status"]}}]}
                """.formatted(UUID.randomUUID()));
        for (String pointer : List.of("", "/filters", "/widgets/0/chart", "/widgets/0/chart/filters"))
            ((ObjectNode) configuration.at(pointer)).set("projectIds", json.valueToTree(List.of(target.id(), peer.id())));
        assertThat(json.treeToValue(configuration, DashboardModels.Configuration.class).widgets()).hasSize(1);
        return configuration;
    }

    private void seedDashboards(Project target, Project peer, int count) {
        insertDashboards(dashboardConfiguration(target, peer), count);
    }

    private void insertDashboards(ObjectNode configuration, int count) {
        jdbc.sql("""
                INSERT INTO yumpoo.personal_dashboard(id,company_id,owner_user_id,name,configuration,created_at,updated_at)
                SELECT gen_random_uuid(),:company,:owner,'保留私人看板',CAST(:configuration AS jsonb),:now,:now
                    FROM generate_series(1,:count)
                """).param("company", COMPANY).param("owner", firstAdmin.userId())
                .param("configuration", json.writeValueAsString(configuration)).param("count", count).param("now", utc(clock.instant())).update();
    }

    private void seedMemberships(Project project, int count) {
        jdbc.sql("""
                WITH people AS (
                    INSERT INTO yumpoo.identity_user(id,company_id,employment_status,account_status,display_name,directory_synced_at,row_version,created_at,updated_at)
                    SELECT gen_random_uuid(),:company,'ACTIVE','ENABLED','分批成员'||g,:now,0,:now,:now FROM generate_series(1,:count) g RETURNING id)
                INSERT INTO yumpoo.project_membership(id,company_id,project_id,user_id,status,joined_at,joined_by_user_id,row_version)
                SELECT gen_random_uuid(),:company,:project,id,'ACTIVE',:now,:owner,0 FROM people
                """).param("company", COMPANY).param("project", project.id()).param("owner", firstAdmin.userId())
                .param("count", count).param("now", utc(clock.instant())).update();
    }

    private void seedTimers(Project target, Project peer) {
        jdbc.sql("INSERT INTO yumpoo.work_item_timer_state(company_id,user_id,row_version) VALUES (:company,:user,7),(:company,:exclusive,11)")
                .param("company", COMPANY).param("user", firstAdmin.userId()).param("exclusive", secondAdmin.userId()).update();
        timer(target, firstAdmin.userId(), false);
        timer(peer, firstAdmin.userId(), true);
        timer(target, secondAdmin.userId(), true);
        jdbc.sql("INSERT INTO yumpoo.work_item_time_revision(company_id,project_id,revision) VALUES (:company,:target,3),(:company,:peer,4)")
                .param("company", COMPANY).param("target", target.id()).param("peer", peer.id()).update();
    }

    private void timer(Project project, UUID user, boolean stopped) {
        jdbc.sql("""
                INSERT INTO yumpoo.work_item_time_session(id,company_id,project_id,work_item_id,user_id,started_at,stopped_at,source)
                VALUES (gen_random_uuid(),:company,:project,:item,:user,:start,:stop,'TIMER')
                """).param("company", COMPANY).param("project", project.id()).param("item", project.itemId()).param("user", user)
                .param("start", utc(clock.instant().minusSeconds(3600))).param("stop", stopped ? utc(clock.instant()) : null).update();
    }

    private UUID governanceHistory(Project project) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO yumpoo.admin_override(id,company_id,action,target_type,target_id,reason,request_hash,idempotency_key,
                    actor_user_id,before_snapshot,after_snapshot,blocker_counts,result,occurred_at)
                VALUES (:id,:company,'PROJECT_ARCHIVE_WITH_OPEN_ITEMS','PROJECT',:project,'历史开放工作项归档治理原因',:hash,:key,:actor,
                    '{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'SUCCEEDED',:now)
                """).param("id", id).param("company", COMPANY).param("project", project.id()).param("hash", "a".repeat(64))
                .param("key", UUID.randomUUID()).param("actor", firstAdmin.userId()).param("now", utc(clock.instant())).update();
        return id;
    }

    private void terminalOutbox() {
        jdbc.sql("UPDATE yumpoo.outbox_event SET status='COMPLETED',next_attempt_at=NULL,completed_at=:now WHERE status='PENDING'")
                .param("now", utc(clock.instant())).update();
    }

    private long businessRows(String stage, Project target) {
        return switch (stage) {
            case "NOTIFICATION" -> scopeCount("SELECT count(*) FROM yumpoo.notification_event WHERE project_id=:project", target)
                    + scopeCount("SELECT count(*) FROM yumpoo.user_notification n JOIN yumpoo.notification_event e ON e.id=n.notification_event_id WHERE e.project_id=:project", target)
                    + scopeCount("SELECT count(*) FROM yumpoo.project_notification_preference WHERE project_id=:project", target);
            case "AUDIT" -> scopeCount("SELECT count(*) FROM yumpoo.activity_event WHERE scope_id=:project OR primary_work_item_id IN (SELECT id FROM yumpoo.work_item WHERE project_id=:project) OR secondary_work_item_id IN (SELECT id FROM yumpoo.work_item WHERE project_id=:project)", target)
                    + scopeCount("SELECT count(*) FROM yumpoo.work_item_cell_activity WHERE project_id=:project", target);
            case "FILESTORAGE" -> scopeCount("SELECT count(*) FROM yumpoo.attachment WHERE quota_project_id=:project", target)
                    + scopeCount("SELECT count(*) FROM yumpoo.attachment_quota_usage WHERE scope_type='PROJECT' AND scope_id=:project", target);
            case "OUTBOX" -> scopeCount("SELECT count(*) FROM yumpoo.outbox_event e WHERE " + OUTBOX_SCOPE + " AND e.event_type<>'catalog.project_purged'", target)
                    + scopeCount("SELECT count(*) FROM yumpoo.outbox_consumer_receipt r JOIN yumpoo.outbox_event e ON e.event_id=r.event_id WHERE " + OUTBOX_SCOPE, target);
            case "WORKITEM" -> workRows(target);
            case "REPORTING" -> scopeCount("SELECT count(*) FROM yumpoo.personal_dashboard WHERE jsonb_path_exists(configuration, '$.**.projectIds[*] ? (@ == $project)', jsonb_build_object('project', CAST(:project AS text)))", target);
            case "CATALOG" -> scopeCount("SELECT count(*) FROM yumpoo.project_membership WHERE project_id=:project", target);
            default -> throw new IllegalArgumentException(stage);
        };
    }

    private long workRows(Project target) {
        long rows = 0;
        for (String table : ITEM_TABLES) rows += scopeCount("SELECT count(*) FROM yumpoo." + table + " WHERE project_id=:project", target);
        rows += scopeCount("SELECT count(*) FROM yumpoo.work_item_update_mention WHERE update_id IN (SELECT id FROM yumpoo.work_item_update WHERE project_id=:project)", target);
        rows += scopeCount("SELECT count(*) FROM yumpoo.work_item_relation WHERE left_project_id=:project OR right_project_id=:project", target);
        rows += scopeCount("SELECT count(*) FROM yumpoo.work_item_connection WHERE source_project_id=:project OR target_project_id=:project", target);
        rows += scopeCount("SELECT count(*) FROM yumpoo.work_item_connect_column_target WHERE target_project_id=:project OR column_id IN (SELECT id FROM yumpoo.work_item_connect_column WHERE project_id=:project)", target);
        rows += scopeCount("SELECT count(*) FROM yumpoo.project_work_item_status_label WHERE project_id=:project", target);
        rows += scopeCount("SELECT count(*) FROM yumpoo.project_work_item_priority_label WHERE project_id=:project", target);
        rows += count("SELECT count(*) FROM yumpoo.work_item_timer_state WHERE user_id=:user", secondAdmin.userId());
        return rows;
    }

    private Map<String, Object> row(String table, UUID id) {
        return jdbc.sql("SELECT * FROM yumpoo." + table + " WHERE id=:id").param("id", id).query().singleRow();
    }
    private boolean exists(String table, UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM yumpoo." + table + " WHERE id=:id)").param("id", id).query(Boolean.class).single();
    }
    private long scopeCount(String sql, Project project) {
        return jdbc.sql(sql).param("company", COMPANY).param("project", project.id()).query(Long.class).single();
    }
    private long count(String sql, UUID user) { return jdbc.sql(sql).param("user", user).query(Long.class).single(); }
    private static RequestHash hash() { return new RequestHash("0".repeat(64)); }
    private static java.time.OffsetDateTime utc(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static <T> T correlated(Supplier<T> command) {
        try (var ignored = RequestCorrelationContext.open(RequestCorrelation.root("purge-it-" + UUID.randomUUID()))) { return command.get(); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("purge test latch timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private void awaitBlockedProjectLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%yumpoo.project%' AND query ILIKE '%FOR UPDATE%')")
                    .query(Boolean.class).single()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("cancellation did not wait for the project row lock");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean @Primary MutableClock projectPurgeTestClock() { return new MutableClock(); }
    }
    static final class MutableClock extends Clock {
        private volatile Instant instant = BASE;
        void set(Instant value) { instant = value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
}
