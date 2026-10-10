package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProjectCreationIT {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ADMIN_ID = UUID.fromString("24000000-0000-4000-8000-000000000101");
    private static final UUID OWNER_ID = UUID.fromString("24000000-0000-4000-8000-000000000102");
    private static final UUID WORKSPACE_ID = UUID.fromString("a460aa25-7180-490b-ab14-f9ec09049024");

    @Autowired private ProjectCreationOrchestrator orchestrator;
    @Autowired private ProjectLifecycleGovernanceService lifecycle;
    @Autowired private com.yumpoo.platform.catalog.application.project.ProjectService projectService;
    @Autowired private com.yumpoo.platform.catalog.application.workspace.WorkspaceService workspaceService;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        cleanUp();
        insertUser(ADMIN_ID, "M2-04 Admin", "ACTIVE", "ENABLED");
        insertUser(OWNER_ID, "M2-04 Owner", "ACTIVE", "ENABLED");
    }

    @AfterEach
    void tearDown() {
        dropFailureTrigger();
        cleanUp();
    }

    @Test
    void createsActiveProjectsWithCreatorMembershipAndDefaultStructure() {
        List<IdempotencyExecutionResult> results = List.of(
                create("研发协作", "a"),
                create("门户升级", "b"),
                create("服务改善", "c"),
                create("团队计划", "d"));

        assertThat(results).allSatisfy(result -> {
            assertThat(result.result().httpStatus()).isEqualTo(201);
            assertThat(result.result().etag()).isEqualTo("\"0\"");
        });
        assertThat(count("project")).isEqualTo(4);
        assertThat(count("project_membership")).isEqualTo(4);
        assertThat(count("content")).isEqualTo(12);
        assertThat(jdbcClient.sql("""
                        SELECT code || ':' || name || ':' || color_token || ':' || sort_order || ':'
                               || active || ':' || protected_content
                          FROM yumpoo.content
                         WHERE project_id = :projectId
                         ORDER BY code
                        """).param("projectId", results.getFirst().result().resourceId())
                .query(String.class).list()).containsExactly(
                        "DEFECTS:缺陷:DARK_RED:30:true:true",
                        "REQUIREMENTS:需求:BRIGHT_BLUE:10:true:true",
                        "TASKS:任务:BRIGHT_GREEN:20:true:true");
        UUID firstProjectId = results.getFirst().result().resourceId();
        assertThat(jdbcClient.sql("SELECT lifecycle || ':' || owner_user_id FROM yumpoo.project WHERE id=:id")
                .param("id", firstProjectId).query(String.class).single()).isEqualTo("ACTIVE:" + OWNER_ID);
        assertThat(jdbcClient.sql("""
                SELECT status_code || ':' || display_name || ':' || color_token || ':' || status_category || ':'
                       || sort_order || ':' || active || ':' || protected_label
                  FROM yumpoo.project_work_item_status_label WHERE project_id=:id ORDER BY sort_order
                """).param("id", firstProjectId).query(String.class).list()).containsExactly(
                "NOT_STARTED:未开始:GRAY:TODO:0:true:true", "IN_PROGRESS:进行中:ORANGE:IN_PROGRESS:10:true:false",
                "STUCK:卡住:RED:IN_PROGRESS:20:true:false", "DONE:已完成:GREEN:DONE:30:true:false",
                "CANCELED:已取消:AMERICAN_GRAY:CANCELED:40:true:false");
        assertThat(jdbcClient.sql("""
                SELECT priority_code || ':' || display_name || ':' || color_token || ':' || sort_order || ':' || active
                  FROM yumpoo.project_work_item_priority_label WHERE project_id=:id ORDER BY sort_order
                """).param("id", firstProjectId).query(String.class).list()).containsExactly(
                "LOW:低:BLUE:10:true", "MEDIUM:中:TEAL:20:true", "HIGH:高:ORANGE:30:true", "URGENT:紧急:RED:40:true");
        assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.platform_role_assignment WHERE role_code = 'PROJECT_OWNER'")
                .query(Integer.class).single()).isZero();

        String payloads = jdbcClient.sql("""
                        SELECT string_agg(payload_json::text, ' ')
                          FROM yumpoo.outbox_event
                         WHERE event_type='catalog.project_created' AND event_version=2
                        """).query(String.class).single();
        assertThat(payloads).doesNotContain("description", "customerName", "contactNote");
    }

    @Test
    void queryAndWorkspaceCountsUseTheSameDatabaseVisibilityPredicateAndPatchIsNoOpAware() {
        UUID first = create("QUERY_ALPHA", "7")
                .result().resourceId();
        create("QUERY_BETA", "8");

        var ownerPage = projectService.findAll(owner(), new com.yumpoo.platform.catalog.application.project.ProjectSearchCriteria(null, null, null, null, null),
                com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest.of(0, 20));
        var appManagerPage = projectService.findAll(new CurrentActor(UUID.randomUUID(), COMPANY_ID, 0,
                        Set.of(PlatformRoleCode.APP_MANAGER)), new com.yumpoo.platform.catalog.application.project.ProjectSearchCriteria(null, null, null, null, null),
                com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest.of(0, 20));
        var workspace = workspaceService.findAll(owner(),
                com.yumpoo.platform.catalog.application.workspace.WorkspaceListStatus.ACTIVE).getFirst();

        assertThat(ownerPage.items()).extracting("code")
                .containsExactly("P001", "P002");
        assertThat(appManagerPage.totalElements()).isEqualTo(ownerPage.totalElements());
        assertThat(workspace.visibleProjectCount()).isEqualTo(ownerPage.totalElements());

        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("m206-project-update"))) {
            var updated = projectService.update(new com.yumpoo.platform.catalog.application.project.ProjectUpdateCommand(
                    owner(), first, 0, "Query Alpha Updated", "private description"));
            var noOp = projectService.update(new com.yumpoo.platform.catalog.application.project.ProjectUpdateCommand(
                    owner(), first, 1, updated.name(), updated.description()));
            assertThat(updated.rowVersion()).isOne();
            assertThat(noOp.rowVersion()).isOne();
        }
        assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE event_type='catalog.project_updated' AND aggregate_id=:id")
                .param("id", first).query(Integer.class).single()).isOne();
        assertThat(jdbcClient.sql("SELECT payload_json::text FROM yumpoo.outbox_event WHERE event_type='catalog.project_updated' AND aggregate_id=:id")
                .param("id", first).query(String.class).single())
                .doesNotContain("private description", "private contact");
    }

    @Test
    void projectManagementQueryCombinesSearchFiltersDatesOwnersAndAccessWithoutCountDrift() {
        UUID alpha = create("FILTER_ALPHA", "a")
                .result().resourceId();
        UUID beta = create("FILTER_BETA", "b")
                .result().resourceId();
        java.time.Instant threshold = java.time.Instant.now().minus(java.time.Duration.ofDays(1));
        jdbcClient.sql("UPDATE yumpoo.project SET created_at=:old, updated_at=:old WHERE id=:id")
                .param("old", java.time.OffsetDateTime.ofInstant(
                        threshold.minus(java.time.Duration.ofDays(30)), java.time.ZoneOffset.UTC))
                .param("id", alpha).update();

        var criteria = new com.yumpoo.platform.catalog.application.project.ProjectSearchCriteria(
                "filter",
                List.of(OWNER_ID),
                List.of(com.yumpoo.platform.catalog.application.project.ProjectActorAccess.OWNER),
                threshold,
                com.yumpoo.platform.catalog.application.project.ProjectLifecycleFilter.ALL);
        var result = projectService.findAll(owner(), criteria,
                com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest.of(0, 1));

        assertThat(result.items()).singleElement().satisfies(project -> {
            assertThat(project.id()).isEqualTo(beta);
            assertThat(project.createdAt()).isNotNull();
            assertThat(project.updatedAt()).isAfter(threshold);
        });
        assertThat(result.totalElements()).isOne();
        assertThat(projectService.findOwnerOptions(owner())).singleElement()
                .satisfies(option -> {
                    assertThat(option.userId()).isEqualTo(OWNER_ID);
                    assertThat(option.displayName()).isEqualTo("M2-04 Owner");
                });
    }

    @Test
    void ownerAndAdministratorCanArchiveAndRestoreWithoutTemplateDependencies() {
        UUID projectId = create("UP2_LIFECYCLE", "b")
                .result().resourceId();
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("up1-project-lifecycle"))) {
            CurrentActor ordinary = new CurrentActor(ADMIN_ID, COMPANY_ID, 0, Set.of());
            assertThatThrownBy(() -> lifecycle.archive(new ProjectArchiveOperationCommand(ordinary, projectId,
                    0, UUID.randomUUID(), new RequestHash("c".repeat(64)))))
                    .isInstanceOfSatisfying(ApplicationException.class, error ->
                            assertThat(error.errorCode()).isEqualTo(StandardErrorCode.ACCESS_DENIED));
            var archived = lifecycle.archive(new ProjectArchiveOperationCommand(owner(), projectId,
                    0, UUID.randomUUID(), new RequestHash("d".repeat(64))));
            assertThat(archived.result().responseJson()).contains("ARCHIVED");
            var restored = lifecycle.restore(new ProjectRestoreOperationCommand(admin(), projectId,
                    1, UUID.randomUUID(), new RequestHash("e".repeat(64))));
            assertThat(restored.result().responseJson()).contains("ACTIVE");
            assertThat(projectService.findVisible(admin(), projectId).capabilities().canArchive()).isTrue();
            lifecycle.archive(new ProjectArchiveOperationCommand(admin(), projectId,
                    2, UUID.randomUUID(), new RequestHash("f".repeat(64))));
            assertThat(projectService.findVisible(owner(), projectId).capabilities().canRestore()).isTrue();
            var ownerRestored = lifecycle.restore(new ProjectRestoreOperationCommand(owner(), projectId,
                    3, UUID.randomUUID(), new RequestHash("1".repeat(64))));
            assertThat(ownerRestored.result().responseJson()).contains("ACTIVE");
        }
        assertThat(jdbcClient.sql("SELECT row_version FROM yumpoo.project WHERE id=:id")
                .param("id", projectId).query(Long.class).single()).isEqualTo(4);
    }

    @Test
    void replayIsIdenticalAndFiveConcurrentCreationsAllGetUniqueCodes() throws Exception {
        var replayCommand = command("REPLAY", UUID.randomUUID(), "e".repeat(64));
        var first = execute("up2-first", replayCommand);
        var replay = execute("up2-replay", replayCommand);
        assertThat(replay.result()).isEqualTo(first.result());
        assertThat(replay.replayed()).isTrue();
        assertThat(countByCode("P001")).isOne();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(5)) {
            List<Future<UUID>> futures = new ArrayList<>();
            for (int index=0; index<5; index++) {
                int caller=index;
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return execute("up2-race-"+caller, command("Concurrent "+caller, UUID.randomUUID(), String.valueOf(caller+1).repeat(64))).result().resourceId();
                }));
            }
            start.countDown();
            Set<UUID> ids = new java.util.HashSet<>();
            for (var future : futures) ids.add(future.get(20, TimeUnit.SECONDS));
            assertThat(ids).hasSize(5);
        }
        assertThat(jdbcClient.sql("SELECT project_code FROM yumpoo.project ORDER BY project_code")
                .query(String.class).list()).containsExactly("P001","P002","P003","P004","P005","P006");
    }

    @Test
    void generatedCodeIgnoresManualCodesAndContinuesTheLargestNumericCode() {
        UUID numeric=create("Numeric", "1").result().resourceId();
        UUID manual=create("Manual", "2").result().resourceId();
        jdbcClient.sql("UPDATE yumpoo.project SET project_code='P007' WHERE id=:id").param("id",numeric).update();
        jdbcClient.sql("UPDATE yumpoo.project SET project_code='RND_PORTAL' WHERE id=:id").param("id",manual).update();
        UUID next=create("Next", "3").result().resourceId();
        assertThat(jdbcClient.sql("SELECT project_code FROM yumpoo.project WHERE id=:id")
                .param("id",next).query(String.class).single()).isEqualTo("P008");
    }

    @Test
    void disabledOrDepartedCreatorsCannotCreate() {
        for (String column : List.of("account_status", "employment_status")) {
            String unavailable=column.equals("account_status") ? "DISABLED" : "LEFT";
            jdbcClient.sql("UPDATE yumpoo.identity_user SET "+column+"=:value, updated_at=transaction_timestamp(), "
                    +(column.equals("account_status") ? "account_disabled_at=now(), account_disabled_by_user_id=:admin, account_disabled_reason='UP2 test'" : "left_at=now(), left_reason='UP2 test'")+" WHERE id=:id")
                    .param("admin",ADMIN_ID).param("value",unavailable).param("id",OWNER_ID).update();
            assertThatThrownBy(() -> create("Unavailable", "a"))
                    .isInstanceOfSatisfying(ApplicationException.class,e ->
                            assertThat(e.errorCode()).isEqualTo(StandardErrorCode.ACCESS_DENIED));
            jdbcClient.sql("UPDATE yumpoo.identity_user SET "+column+"=:value, updated_at=transaction_timestamp(), "
                    +(column.equals("account_status") ? "account_disabled_at=NULL, account_disabled_by_user_id=NULL, account_disabled_reason=NULL" : "left_at=NULL, left_reason=NULL")+" WHERE id=:id")
                    .param("value",column.equals("account_status") ? "ENABLED" : "ACTIVE")
                    .param("id",OWNER_ID).update();
        }
        assertThat(count("project")).isZero();
    }

    @Test
    void secondContentAuditAndOutboxFailuresRollBackEveryFact() {
        for (FailurePoint point : FailurePoint.values()) {
            installFailureTrigger(point);
            String name = "FAIL_" + point.name();
            assertThatThrownBy(() -> create(name, Integer.toString(point.ordinal() + 1)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count("project")).isZero();
            assertThat(count("content")).isZero();
            assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.project_membership membership JOIN yumpoo.project project ON project.id = membership.project_id WHERE project.name = :name")
                    .param("name", name).query(Integer.class).single()).isZero();
            assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.idempotency_record WHERE route_key = 'createProject' AND request_hash = :hash")
                    .param("hash", Integer.toString(point.ordinal() + 1).repeat(64))
                    .query(Integer.class).single()).isZero();
            dropFailureTrigger();
        }
    }

    @Test
    void deferredConstraintRejectsOwnerWithoutActiveMembership() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        UUID projectId = UUID.randomUUID();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> jdbcClient.sql("""
                        INSERT INTO yumpoo.project (
                            id, company_id, workspace_id, project_code, name, lifecycle,
                            owner_user_id, row_version,
                            created_at, created_by_user_id, updated_at, updated_by_user_id
                        ) VALUES (:id, :companyId, :workspaceId, 'NO_MEMBERSHIP', 'No membership',
                            'ACTIVE', :ownerId, 0,
                            transaction_timestamp(), :adminId, transaction_timestamp(), :adminId)
                        """).param("id", projectId).param("companyId", COMPANY_ID)
                .param("workspaceId", WORKSPACE_ID).param("ownerId", OWNER_ID)
                .param("adminId", ADMIN_ID).update()))
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .rootCause().hasMessageContaining("project owner must have an active membership");
        assertThat(countByCode("NO_MEMBERSHIP")).isZero();
    }

    private IdempotencyExecutionResult create(String name, String hashSeed) {
        return execute("up2-create-" + UUID.randomUUID(), command(name, UUID.randomUUID(), hashSeed.repeat(64)));
    }

    private ProjectCreationCommand command(
            String name, UUID key, String hash
    ) {
        return new ProjectCreationCommand(owner(), "  " + name + "  ",
                "  private description  ", key, new RequestHash(hash),
                "WEB", "m2-04-test");
    }

    private IdempotencyExecutionResult execute(String requestId, ProjectCreationCommand command) {
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root(requestId))) {
            return orchestrator.create(command);
        }
    }

    private void installFailureTrigger(FailurePoint point) {
        dropFailureTrigger();
        jdbcClient.sql("""
                CREATE OR REPLACE FUNCTION yumpoo.m204_fail_write()
                RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''M2-04 injected failure''; END'
                """).update();
        jdbcClient.sql(point.triggerSql()).update();
    }

    private void dropFailureTrigger() {
        jdbcClient.sql("DROP TRIGGER IF EXISTS m204_fail_write ON yumpoo.content").update();
        jdbcClient.sql("DROP TRIGGER IF EXISTS m204_fail_write ON yumpoo.security_audit_event").update();
        jdbcClient.sql("DROP TRIGGER IF EXISTS m204_fail_write ON yumpoo.outbox_event").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS yumpoo.m204_fail_write()").update();
    }

    private void insertUser(UUID id, String name, String employment, String account) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.identity_user (
                            id, company_id, employment_status, account_status, display_name,
                            directory_synced_at, authorization_version, row_version, created_at, updated_at
                        ) VALUES (:id, :companyId, :employment, :account, :name,
                            transaction_timestamp(), 0, 0, transaction_timestamp(), transaction_timestamp())
                        """).param("id", id).param("companyId", COMPANY_ID)
                .param("employment", employment).param("account", account).param("name", name).update();
    }

    private int count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM yumpoo." + table).query(Integer.class).single();
    }

    private int countByCode(String code) {
        return jdbcClient.sql("SELECT count(*) FROM yumpoo.project WHERE project_code = :code")
                .param("code", code).query(Integer.class).single();
    }

    private int contentCountByCode(String code) {
        return jdbcClient.sql("SELECT count(*) FROM yumpoo.content content JOIN yumpoo.project project ON project.id = content.project_id WHERE project.project_code = :code")
                .param("code", code).query(Integer.class).single();
    }

    private void cleanUp() {
        jdbcClient.sql("DELETE FROM yumpoo.content_catalog_version WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.content WHERE company_id = :companyId").param("companyId", COMPANY_ID).update();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcClient.sql("DELETE FROM yumpoo.project_membership WHERE company_id = :companyId").param("companyId", COMPANY_ID).update();
            jdbcClient.sql("DELETE FROM yumpoo.project WHERE company_id = :companyId").param("companyId", COMPANY_ID).update();
        });
        jdbcClient.sql("DELETE FROM yumpoo.security_audit_event WHERE target_type = 'PROJECT'").update();
        jdbcClient.sql("DELETE FROM yumpoo.outbox_consumer_receipt WHERE event_id IN (SELECT event_id FROM yumpoo.outbox_event WHERE company_id = :companyId)")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.outbox_event WHERE company_id = :companyId AND aggregate_type = 'Project'")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.idempotency_record WHERE actor_user_id IN (:adminId, :ownerId)")
                .param("adminId", ADMIN_ID).param("ownerId", OWNER_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.identity_user WHERE id IN (:adminId, :ownerId)")
                .param("adminId", ADMIN_ID).param("ownerId", OWNER_ID).update();
    }

    private static CurrentActor admin() {
        return new CurrentActor(ADMIN_ID, COMPANY_ID, 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));
    }

    private static CurrentActor owner() {
        return new CurrentActor(OWNER_ID, COMPANY_ID, 0, Set.of());
    }

    private enum FailurePoint {
        SECOND_CONTENT("CREATE TRIGGER m204_fail_write BEFORE INSERT ON yumpoo.content FOR EACH ROW WHEN (NEW.code = 'TASKS') EXECUTE FUNCTION yumpoo.m204_fail_write()"),
        SECURITY_AUDIT("CREATE TRIGGER m204_fail_write BEFORE INSERT ON yumpoo.security_audit_event FOR EACH ROW EXECUTE FUNCTION yumpoo.m204_fail_write()"),
        PROJECT_CREATED_OUTBOX("CREATE TRIGGER m204_fail_write BEFORE INSERT ON yumpoo.outbox_event FOR EACH ROW WHEN (NEW.event_type = 'catalog.project_created') EXECUTE FUNCTION yumpoo.m204_fail_write()");

        private final String triggerSql;

        FailurePoint(String triggerSql) {
            this.triggerSql = triggerSql;
        }

        String triggerSql() {
            return triggerSql;
        }
    }
}
