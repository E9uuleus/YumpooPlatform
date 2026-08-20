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
    private static final UUID WORKSPACE_ID = UUID.fromString("24000000-0000-4000-8000-000000000103");

    @Autowired private ProjectCreationOrchestrator orchestrator;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        cleanUp();
        insertUser(ADMIN_ID, "M2-04 Admin", "ACTIVE", "ENABLED");
        insertUser(OWNER_ID, "M2-04 Owner", "ACTIVE", "ENABLED");
        jdbcClient.sql("""
                        INSERT INTO yumpoo.workspace (
                            id, company_id, code, name, sort_order, status, row_version,
                            created_at, created_by_user_id, updated_at, updated_by_user_id
                        ) VALUES (:id, :companyId, 'M2_04', 'M2-04 Workspace', 10, 'ACTIVE', 0,
                            transaction_timestamp(), :adminId, transaction_timestamp(), :adminId)
                        """)
                .param("id", WORKSPACE_ID).param("companyId", COMPANY_ID)
                .param("adminId", ADMIN_ID).update();
    }

    @AfterEach
    void tearDown() {
        dropFailureTrigger();
        cleanUp();
    }

    @Test
    void createsFourTypesWithOwnerMembershipAndTemplateProvenance() {
        List<IdempotencyExecutionResult> results = List.of(
                create("RND_PROJECT", "PRODUCT_DEVELOPMENT", "RND", "  Customer  ", "a"),
                create("PRESALES_PROJECT", "PRE_SALES", "PRE_SALES", null, "b"),
                create("IMPLEMENT_PROJECT", "IMPLEMENTATION", "IMPLEMENTATION", null, "c"),
                create("HYPERCARE_PROJECT", "HYPERCARE", "HYPERCARE", null, "d"));

        assertThat(results).allSatisfy(result -> {
            assertThat(result.result().httpStatus()).isEqualTo(201);
            assertThat(result.result().etag()).isEqualTo("\"0\"");
        });
        assertThat(count("project")).isEqualTo(4);
        assertThat(count("project_membership")).isEqualTo(4);
        assertThat(count("content")).isEqualTo(12);
        assertThat(jdbcClient.sql("""
                        SELECT code || ':' || work_item_type || ':' || default_view_type || ':'
                               || view_config::text || ':' || applied_blueprint_code
                          FROM yumpoo.content
                         WHERE project_id = :projectId
                         ORDER BY code
                        """).param("projectId", results.getFirst().result().resourceId())
                .query(String.class).list()).containsExactly(
                        "DEFECTS:DEFECT:TABLE:{}:DEFECTS",
                        "REQUIREMENTS:REQUIREMENT:TABLE:{}:REQUIREMENTS",
                        "TASKS:TASK:TABLE:{}:TASKS");
        assertThat(jdbcClient.sql("SELECT customer_name FROM yumpoo.project WHERE project_code = 'RND_PROJECT'")
                .query(String.class).single()).isEqualTo("Customer");
        assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.platform_role_assignment WHERE role_code = 'PROJECT_OWNER'")
                .query(Integer.class).single()).isZero();

        String payloads = jdbcClient.sql("""
                        SELECT string_agg(payload_json::text, ' ')
                          FROM yumpoo.outbox_event
                         WHERE event_type IN ('catalog.project_created', 'catalog.project_template_applied')
                        """).query(String.class).single();
        assertThat(payloads).doesNotContain("description", "customerName", "contactNote");
    }

    @Test
    void replayIsIdenticalAndConcurrentDuplicateCodeHasOneWinner() throws Exception {
        UUID replayKey = UUID.randomUUID();
        ProjectCreationCommand replayCommand = command(
                "REPLAY", "PRE_SALES", "PRE_SALES", replayKey, "e".repeat(64));
        IdempotencyExecutionResult first = execute("m204-replay-first", replayCommand);
        IdempotencyExecutionResult replay = execute("m204-replay-second", replayCommand);
        assertThat(replay.result()).isEqualTo(first.result());
        assertThat(replay.replayed()).isTrue();
        assertThat(countByCode("REPLAY")).isOne();

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                int caller = index;
                futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        execute("m204-duplicate-" + caller,
                                command("RACE_CODE", "IMPLEMENTATION", "IMPLEMENTATION",
                                        UUID.randomUUID(), String.valueOf(caller + 1).repeat(64)));
                        return true;
                    } catch (ApplicationException exception) {
                        assertThat(exception.errorCode()).isEqualTo(StandardErrorCode.VALIDATION_FAILED);
                        return false;
                    }
                }));
            }
            start.countDown();
            assertThat(futures.get(0).get(10, TimeUnit.SECONDS)
                    ^ futures.get(1).get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(countByCode("RACE_CODE")).isOne();
        assertThat(contentCountByCode("RACE_CODE")).isEqualTo(3);
    }

    @Test
    void secondContentAuditAndBothOutboxFailuresRollBackEveryFact() {
        for (FailurePoint point : FailurePoint.values()) {
            installFailureTrigger(point);
            String code = "FAIL_" + point.name();
            assertThatThrownBy(() -> create(code, "HYPERCARE", "HYPERCARE", null,
                    Integer.toString(point.ordinal() + 1)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(countByCode(code)).isZero();
            assertThat(contentCountByCode(code)).isZero();
            assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.project_membership membership JOIN yumpoo.project project ON project.id = membership.project_id WHERE project.project_code = :code")
                    .param("code", code).query(Integer.class).single()).isZero();
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
                            id, company_id, workspace_id, project_code, name, project_type, lifecycle,
                            owner_user_id, template_key, template_version, row_version,
                            created_at, created_by_user_id, updated_at, updated_by_user_id
                        ) VALUES (:id, :companyId, :workspaceId, 'NO_MEMBERSHIP', 'No membership',
                            'PRODUCT_DEVELOPMENT', 'DRAFT', :ownerId, 'RND', 1, 0,
                            transaction_timestamp(), :adminId, transaction_timestamp(), :adminId)
                        """).param("id", projectId).param("companyId", COMPANY_ID)
                .param("workspaceId", WORKSPACE_ID).param("ownerId", OWNER_ID)
                .param("adminId", ADMIN_ID).update()))
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .rootCause().hasMessageContaining("project owner must have an active membership");
        assertThat(countByCode("NO_MEMBERSHIP")).isZero();
    }

    @Test
    void publishedTemplateShareLockSerializesRetirement() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Void> reader = executor.submit(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try (PreparedStatement statement = connection.prepareStatement("""
                            SELECT id FROM yumpoo.project_template_definition
                             WHERE template_key = 'RND' AND template_version = 1
                               AND lifecycle_status = 'PUBLISHED'
                             FOR SHARE
                            """)) {
                        assertThat(statement.executeQuery().next()).isTrue();
                    }
                    locked.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    connection.rollback();
                }
                return null;
            });
            Future<Integer> retirement = executor.submit(() -> {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE yumpoo.project_template_definition
                               SET lifecycle_status = 'RETIRED', retired_at = transaction_timestamp(),
                                   retired_by_user_id = ?, retire_reason = 'M2-04 lock verification',
                                   row_version = row_version + 1, updated_at = transaction_timestamp()
                             WHERE template_key = 'RND' AND template_version = 1
                            """)) {
                        statement.setObject(1, ADMIN_ID);
                        int updated = statement.executeUpdate();
                        connection.rollback();
                        return updated;
                    }
                }
            });
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> retirement.get(Duration.ofMillis(200).toMillis(), TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThat(retirement.get(5, TimeUnit.SECONDS)).isOne();
            reader.get(5, TimeUnit.SECONDS);
        }
        assertThat(jdbcClient.sql("SELECT lifecycle_status FROM yumpoo.project_template_definition WHERE template_key = 'RND' AND template_version = 1")
                .query(String.class).single()).isEqualTo("PUBLISHED");
    }

    private IdempotencyExecutionResult create(
            String code, String projectType, String templateKey, String customerName, String hashSeed
    ) {
        ProjectCreationCommand command = command(code, projectType, templateKey,
                UUID.randomUUID(), hashSeed.repeat(64));
        if (customerName != null) {
            command = new ProjectCreationCommand(command.actor(), command.workspaceId(), command.code(),
                    command.name(), command.description(), command.projectType(), command.ownerUserId(),
                    command.templateKey(), command.templateVersion(), customerName,
                    command.customerReference(), command.deliverySite(), command.contactNote(),
                    command.idempotencyKey(), command.requestHash(), command.clientType(), command.clientVersion());
        }
        return execute("m204-create-" + code, command);
    }

    private ProjectCreationCommand command(
            String code, String projectType, String templateKey, UUID key, String hash
    ) {
        return new ProjectCreationCommand(admin(), WORKSPACE_ID, code, "  " + code + "  ",
                "  private description  ", projectType, OWNER_ID, templateKey, 1,
                null, " ", " ", " private contact ", key, new RequestHash(hash),
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
        jdbcClient.sql("DELETE FROM yumpoo.content WHERE company_id = :companyId").param("companyId", COMPANY_ID).update();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcClient.sql("DELETE FROM yumpoo.project_membership WHERE company_id = :companyId").param("companyId", COMPANY_ID).update();
            jdbcClient.sql("DELETE FROM yumpoo.project WHERE company_id = :companyId").param("companyId", COMPANY_ID).update();
        });
        jdbcClient.sql("DELETE FROM yumpoo.workspace WHERE id = :id").param("id", WORKSPACE_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.security_audit_event WHERE target_type = 'PROJECT'").update();
        jdbcClient.sql("DELETE FROM yumpoo.outbox_consumer_receipt WHERE event_id IN (SELECT event_id FROM yumpoo.outbox_event WHERE company_id = :companyId)")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.outbox_event WHERE company_id = :companyId AND aggregate_type = 'Project'")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.idempotency_record WHERE actor_user_id = :adminId AND route_key = 'createProject'")
                .param("adminId", ADMIN_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.identity_user WHERE id IN (:adminId, :ownerId)")
                .param("adminId", ADMIN_ID).param("ownerId", OWNER_ID).update();
    }

    private static CurrentActor admin() {
        return new CurrentActor(ADMIN_ID, COMPANY_ID, 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));
    }

    private enum FailurePoint {
        SECOND_CONTENT("CREATE TRIGGER m204_fail_write BEFORE INSERT ON yumpoo.content FOR EACH ROW WHEN (NEW.code = 'TASKS') EXECUTE FUNCTION yumpoo.m204_fail_write()"),
        SECURITY_AUDIT("CREATE TRIGGER m204_fail_write BEFORE INSERT ON yumpoo.security_audit_event FOR EACH ROW EXECUTE FUNCTION yumpoo.m204_fail_write()"),
        PROJECT_CREATED_OUTBOX("CREATE TRIGGER m204_fail_write BEFORE INSERT ON yumpoo.outbox_event FOR EACH ROW WHEN (NEW.event_type = 'catalog.project_created') EXECUTE FUNCTION yumpoo.m204_fail_write()"),
        TEMPLATE_APPLIED_OUTBOX("CREATE TRIGGER m204_fail_write BEFORE INSERT ON yumpoo.outbox_event FOR EACH ROW WHEN (NEW.event_type = 'catalog.project_template_applied') EXECUTE FUNCTION yumpoo.m204_fail_write()");

        private final String triggerSql;

        FailurePoint(String triggerSql) {
            this.triggerSql = triggerSql;
        }

        String triggerSql() {
            return triggerSql;
        }
    }
}
