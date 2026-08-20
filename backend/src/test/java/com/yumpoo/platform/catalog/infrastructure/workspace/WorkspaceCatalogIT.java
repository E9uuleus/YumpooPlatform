package com.yumpoo.platform.catalog.infrastructure.workspace;

import com.yumpoo.platform.catalog.api.WorkspaceSnapshotQuery;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceCreateCommand;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceLifecycleCommand;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceListStatus;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceService;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceUpdateCommand;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceView;
import com.yumpoo.platform.catalog.domain.workspace.WorkspaceStatus;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkspaceCatalogIT {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000099");
    private static final UUID ACTOR_ID = UUID.fromString("17000000-0000-4000-8000-000000000001");

    @Autowired
    private WorkspaceService service;
    @Autowired
    private WorkspaceSnapshotQuery snapshotQuery;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void insertActor() {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.identity_user (
                            id, company_id, employment_status, account_status,
                            display_name, directory_synced_at, authorization_version,
                            row_version, created_at, updated_at
                        ) VALUES (
                            :id, :companyId, 'ACTIVE', 'ENABLED', 'M2-02 Workspace Admin',
                            transaction_timestamp(), 0, 0,
                            transaction_timestamp(), transaction_timestamp()
                        )
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", ACTOR_ID)
                .param("companyId", COMPANY_ID)
                .update();
    }

    @AfterEach
    void removeActorFixture() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        jdbcClient.sql("DELETE FROM yumpoo.workspace WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID)
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.idempotency_record WHERE actor_user_id = :actorId")
                .param("actorId", ACTOR_ID)
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.outbox_event WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID)
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.identity_user WHERE id = :actorId")
                .param("actorId", ACTOR_ID)
                .update();
    }

    @Test
    @Transactional
    void createReplaysExactlyAndListUsesStableSortOrder() throws Exception {
        try (RequestCorrelationContext.Scope ignored = correlation("m2-02-create-list")) {
            IdempotencyExecutionResult first = create("DELIVERY", "乙空间", 20, "b", UUID.randomUUID());
            UUID replayKey = UUID.randomUUID();
            IdempotencyExecutionResult original = create("PRODUCT", "Alpha Workspace", 10, "c", replayKey);
            IdempotencyExecutionResult replay = create("PRODUCT", "Alpha Workspace", 10, "c", replayKey);
            create("PROGRAM", "Beta Workspace", 10, "d", UUID.randomUUID());

            assertThat(first.result().httpStatus()).isEqualTo(201);
            assertThat(first.result().etag()).isEqualTo("\"0\"");
            assertThat(original.replayed()).isFalse();
            assertThat(replay.replayed()).isTrue();
            assertThat(objectMapper.readTree(replay.result().responseJson()))
                    .isEqualTo(objectMapper.readTree(original.result().responseJson()));

            assertThat(service.findAll(admin(), WorkspaceListStatus.ACTIVE))
                    .extracting(WorkspaceView::code)
                    .containsExactly("PRODUCT", "PROGRAM", "DELIVERY");
            assertThat(service.findAll(admin(), WorkspaceListStatus.ACTIVE))
                    .allSatisfy(view -> assertThat(view.visibleProjectCount()).isZero());

            assertThat(jdbcClient.sql("""
                            SELECT count(*) FROM yumpoo.outbox_event
                             WHERE event_type = 'catalog.workspace_created'
                            """).query(Integer.class).single()).isEqualTo(3);
        }
    }

    @Test
    @Transactional
    void duplicateCodeIsCompanyScopedValidationFailure() {
        try (RequestCorrelationContext.Scope ignored = correlation("m2-02-duplicate")) {
            create("UNIQUE_CODE", "首个空间", 0, "e", UUID.randomUUID());

            assertThatThrownBy(() -> create(
                    "UNIQUE_CODE", "重复空间", 1, "f", UUID.randomUUID()))
                    .isInstanceOfSatisfying(ApplicationException.class, error -> {
                        assertThat(error.errorCode()).isEqualTo(StandardErrorCode.VALIDATION_FAILED);
                        assertThat(error.fieldViolations()).singleElement()
                                .satisfies(violation -> assertThat(violation.code())
                                        .isEqualTo("ALREADY_EXISTS"));
                    });
        }
    }

    @Test
    @Transactional
    void conditionalPatchLifecycleAndSnapshotRespectVersionAndCompany() {
        try (RequestCorrelationContext.Scope ignored = correlation("m2-02-lifecycle")) {
            UUID workspaceId = create(
                    "LIFECYCLE", "生命周期空间", 5, "1", UUID.randomUUID())
                    .result().resourceId();

            WorkspaceView noChange = service.update(new WorkspaceUpdateCommand(
                    admin(), workspaceId, 0, " 生命周期空间 ", " 初始描述 ", 5));
            assertThat(noChange.rowVersion()).isZero();
            assertThat(eventCount(workspaceId, "catalog.workspace_updated")).isZero();

            WorkspaceView updated = service.update(new WorkspaceUpdateCommand(
                    admin(), workspaceId, 0, "交付生命周期", "只保存在业务表", 6));
            assertThat(updated.rowVersion()).isOne();
            assertThat(eventCount(workspaceId, "catalog.workspace_updated")).isOne();
            String payload = jdbcClient.sql("""
                            SELECT payload_json::text FROM yumpoo.outbox_event
                             WHERE aggregate_id = :workspaceId
                               AND event_type = 'catalog.workspace_updated'
                            """).param("workspaceId", workspaceId).query(String.class).single();
            assertThat(payload).doesNotContain("只保存在业务表");

            assertError(StandardErrorCode.VERSION_CONFLICT, () -> service.update(
                    new WorkspaceUpdateCommand(admin(), workspaceId, 0, "旧版本写入", null, 6)));

            IdempotencyExecutionResult archived = service.archive(new WorkspaceLifecycleCommand(
                    admin(), workspaceId, 1, UUID.randomUUID(), new RequestHash("2".repeat(64))));
            assertThat(archived.result().etag()).isEqualTo("\"2\"");
            assertThat(snapshotQuery.findActive(COMPANY_ID, workspaceId)).isEmpty();
            assertThat(snapshotQuery.findActive(OTHER_COMPANY_ID, workspaceId)).isEmpty();
            assertError(StandardErrorCode.RESOURCE_NOT_FOUND,
                    () -> service.findVisible(member(), workspaceId));

            IdempotencyExecutionResult restored = service.restore(new WorkspaceLifecycleCommand(
                    admin(), workspaceId, 2, UUID.randomUUID(), new RequestHash("3".repeat(64))));
            assertThat(restored.result().etag()).isEqualTo("\"3\"");
            assertThat(snapshotQuery.findActive(COMPANY_ID, workspaceId)).isPresent();
            assertThat(service.findVisible(member(), workspaceId).status())
                    .isEqualTo(WorkspaceStatus.ACTIVE);
        }
    }

    @Test
    void eventAppendFailureRollsBackWorkspaceAndIdempotencyRecord() {
        assertThatThrownBy(() -> create(
                "ROLLBACK", "回滚验证", 0, "4", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("request correlation context");

        assertThat(jdbcClient.sql("""
                        SELECT count(*) FROM yumpoo.workspace
                         WHERE company_id = :companyId AND code = 'ROLLBACK'
                        """).param("companyId", COMPANY_ID).query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("""
                        SELECT count(*) FROM yumpoo.idempotency_record
                         WHERE actor_user_id = :actorId AND route_key = 'createWorkspace'
                        """).param("actorId", ACTOR_ID).query(Integer.class).single()).isZero();
    }

    @Test
    @Transactional
    void migrationRejectsInvalidCodeAtDatabaseBoundary() {
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO yumpoo.workspace (
                            id, company_id, code, name, sort_order, status, row_version,
                            created_at, created_by_user_id, updated_at, updated_by_user_id
                        ) VALUES (
                            :id, :companyId, 'bad-code', '非法空间', 0, 'ACTIVE', 0,
                            transaction_timestamp(), :actorId, transaction_timestamp(), :actorId
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("companyId", COMPANY_ID)
                .param("actorId", ACTOR_ID)
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    private IdempotencyExecutionResult create(
            String code,
            String name,
            int sortOrder,
            String hashCharacter,
            UUID idempotencyKey
    ) {
        return service.create(new WorkspaceCreateCommand(
                admin(), code, name, code.equals("LIFECYCLE") ? "初始描述" : null,
                sortOrder, idempotencyKey, new RequestHash(hashCharacter.repeat(64))));
    }

    private int eventCount(UUID workspaceId, String eventType) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM yumpoo.outbox_event
                         WHERE aggregate_id = :workspaceId AND event_type = :eventType
                        """)
                .param("workspaceId", workspaceId)
                .param("eventType", eventType)
                .query(Integer.class)
                .single();
    }

    private static RequestCorrelationContext.Scope correlation(String requestId) {
        return RequestCorrelationContext.open(RequestCorrelation.root(requestId));
    }

    private static CurrentActor admin() {
        return new CurrentActor(ACTOR_ID, COMPANY_ID, 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));
    }

    private static CurrentActor member() {
        return new CurrentActor(ACTOR_ID, COMPANY_ID, 0, Set.of());
    }

    private static void assertError(StandardErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(expected));
    }
}
