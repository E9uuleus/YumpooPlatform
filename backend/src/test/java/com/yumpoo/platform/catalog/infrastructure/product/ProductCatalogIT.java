package com.yumpoo.platform.catalog.infrastructure.product;

import com.yumpoo.platform.administration.application.ProductGovernanceService;
import com.yumpoo.platform.administration.application.GovernanceOverrideAction;
import com.yumpoo.platform.administration.application.GovernanceOverrideCommand;
import com.yumpoo.platform.administration.application.GovernanceOverrideService;
import com.yumpoo.platform.administration.application.ProductLifecycleGovernanceCommand;
import com.yumpoo.platform.administration.application.ProductOwnerReassignmentCommand;
import com.yumpoo.platform.administration.infrastructure.governance.JdbcProductOwnerGovernanceProjection;
import com.yumpoo.platform.catalog.application.product.ProductCreateCommand;
import com.yumpoo.platform.catalog.application.product.ProductListStatus;
import com.yumpoo.platform.catalog.application.product.ProductService;
import com.yumpoo.platform.catalog.application.product.ProductUpdateCommand;
import com.yumpoo.platform.catalog.application.product.ProductView;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProductCatalogIT {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ADMIN_ID = UUID.fromString("23000000-0000-4000-8000-000000000101");
    private static final UUID OWNER_ONE = UUID.fromString("23000000-0000-4000-8000-000000000102");
    private static final UUID OWNER_TWO = UUID.fromString("23000000-0000-4000-8000-000000000103");
    private static final UUID MEMBER_ID = UUID.fromString("23000000-0000-4000-8000-000000000104");

    @Autowired private ProductService productService;
    @Autowired private ProductGovernanceService governanceService;
    @Autowired private GovernanceOverrideService overrideService;
    @Autowired private JdbcProductOwnerGovernanceProjection ownerGovernanceProjection;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void insertUsers() {
        cleanUp();
        insertUser(ADMIN_ID, "M2-03 Admin", "ENABLED");
        insertUser(OWNER_ONE, "M2-03 Owner One", "ENABLED");
        insertUser(OWNER_TWO, "M2-03 Owner Two", "ENABLED");
        insertUser(MEMBER_ID, "M2-03 Member", "ENABLED");
    }

    @AfterEach
    void removeFixtures() {
        cleanUp();
    }

    @Test
    void visibilityIsAppliedBeforePagingAndDetailsUseHiddenNotFound() {
        try (RequestCorrelationContext.Scope ignored = correlation("m2-03-visible")) {
            create("BETA", "Beta", OWNER_ONE, "a");
            create("ALPHA_TWO", "Alpha", OWNER_TWO, "b");
            UUID ownerProduct = create("ALPHA_ONE", "Alpha", OWNER_ONE, "c");

            assertThat(productService.findAll(admin(), ProductListStatus.ACTIVE,
                    new OffsetPageRequest(0, 2)).items()).extracting(ProductView::code)
                    .containsExactly("ALPHA_ONE", "ALPHA_TWO");
            assertThat(productService.findAll(admin(), ProductListStatus.ACTIVE,
                    new OffsetPageRequest(0, 2)).totalElements()).isEqualTo(3);
            assertThat(productService.findAll(owner(OWNER_ONE), ProductListStatus.ALL,
                    new OffsetPageRequest(0, 20)).items()).extracting(ProductView::code)
                    .containsExactly("ALPHA_ONE", "BETA");
            assertThat(productService.findAll(member(), ProductListStatus.ALL,
                    new OffsetPageRequest(0, 20)).totalElements()).isZero();
            assertThatThrownBy(() -> productService.findVisible(owner(OWNER_TWO), ownerProduct))
                    .isInstanceOfSatisfying(ApplicationException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(StandardErrorCode.RESOURCE_NOT_FOUND));
        }
    }

    @Test
    void patchLifecycleOwnerValidationAndArchivedReadOnlyFollowFrozenRules() {
        try (RequestCorrelationContext.Scope ignored = correlation("m2-03-lifecycle")) {
            UUID productId = create("LIFECYCLE", "Lifecycle", OWNER_ONE, "d");

            ProductView unchanged = productService.update(new ProductUpdateCommand(
                    owner(OWNER_ONE), productId, 0, " Lifecycle ", "  "));
            assertThat(unchanged.rowVersion()).isZero();
            assertThat(eventCount(productId, "catalog.product_updated")).isZero();

            ProductView updated = productService.update(new ProductUpdateCommand(
                    owner(OWNER_ONE), productId, 0, "Product Lifecycle", "private description"));
            assertThat(updated.rowVersion()).isOne();
            assertThat(eventPayload(productId, "catalog.product_updated"))
                    .doesNotContain("private description");

            IdempotencyExecutionResult archived = governanceService.archive(lifecycle(
                    owner(OWNER_ONE), productId, 1, "e"));
            assertThat(archived.result().etag()).isEqualTo("\"2\"");
            assertThat(productService.findVisible(owner(OWNER_ONE), productId).status().name())
                    .isEqualTo("ARCHIVED");
            assertThatThrownBy(() -> productService.update(new ProductUpdateCommand(
                    owner(OWNER_ONE), productId, 2, "Denied", null)))
                    .isInstanceOfSatisfying(ApplicationException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(StandardErrorCode.INVALID_STATE_TRANSITION));

            jdbcClient.sql("""
                            UPDATE yumpoo.identity_user
                            SET account_status = 'DISABLED', account_disabled_at = transaction_timestamp(),
                                account_disabled_by_user_id = :adminId,
                                account_disabled_reason = 'M2-03 restore validation',
                                updated_at = transaction_timestamp(), row_version = row_version + 1
                            WHERE id = :id
                            """)
                    .param("adminId", ADMIN_ID).param("id", OWNER_ONE).update();
            assertThatThrownBy(() -> governanceService.restore(lifecycle(admin(), productId, 2, "f")))
                    .isInstanceOfSatisfying(ApplicationException.class, error -> {
                        assertThat(error.errorCode()).isEqualTo(StandardErrorCode.INVALID_STATE_TRANSITION);
                        assertThat(error.reason()).isEqualTo("OWNER_MISSING");
                    });

            governanceService.reassignOwner(reassignment(productId, 2, OWNER_TWO, "1"));
            IdempotencyExecutionResult restored = governanceService.restore(lifecycle(admin(), productId, 3, "2"));
            assertThat(restored.result().etag()).isEqualTo("\"4\"");
        }
    }

    @Test
    void reassignmentAuditOutboxProductAndIdempotencyCommitOrRollbackTogether() {
        UUID productId;
        try (RequestCorrelationContext.Scope ignored = correlation("m2-03-create-atomic")) {
            productId = create("ATOMIC", "Atomic", OWNER_ONE, "3");
        }

        ProductOwnerReassignmentCommand failed = reassignment(productId, 0, OWNER_TWO, "4");
        assertThatThrownBy(() -> governanceService.reassignOwner(failed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("request correlation context");
        assertAtomicCounts(productId, OWNER_ONE, 0, 0, 0, 0);

        try (RequestCorrelationContext.Scope ignored = correlation("m2-03-reassign-atomic")) {
            governanceService.reassignOwner(reassignment(productId, 0, OWNER_TWO, "5"));
        }
        assertAtomicCounts(productId, OWNER_TWO, 1, 1, 1, 1);
        assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.platform_role_assignment WHERE role_code = 'PRODUCT_OWNER'")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void activeDevelopmentAndSupportProjectsBlockOnceAndAdminOverrideKeepsFacts() {
        try (RequestCorrelationContext.Scope ignored = correlation("m2-24-product-blocker")) {
            UUID productId = create("M2_24_BLOCKER", "M2-24 Blocker", OWNER_ONE, "b");
            UUID active = insertProject("M2_24_ACTIVE", "ACTIVE");
            UUID draft = insertProject("M2_24_DRAFT", "DRAFT");
            insertLink(active, productId, "DEVELOPMENT");
            insertLink(active, productId, "SUPPORT");
            insertLink(draft, productId, "DEVELOPMENT");

            assertThatThrownBy(() -> governanceService.archive(lifecycle(
                    owner(OWNER_ONE), productId, 0, "c")))
                    .isInstanceOfSatisfying(ApplicationException.class, error -> {
                        assertThat(error.errorCode()).isEqualTo(
                                StandardErrorCode.INVALID_STATE_TRANSITION);
                        assertThat(error.reason()).isEqualTo("PRODUCT_ARCHIVE_BLOCKED");
                        assertThat(error.blockers()).extracting("code", "count")
                                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                                        "ACTIVE_DEVELOPMENT_SUPPORT_PROJECTS", 1L));
                    });

            IdempotencyExecutionResult overridden = overrideService.override(
                    new GovernanceOverrideCommand(admin(),
                            GovernanceOverrideAction.PRODUCT_ARCHIVE_WITH_BLOCKERS,
                            "PRODUCT", productId, "阶段收口确认后执行显式产品归档覆盖", 0,
                            UUID.randomUUID(), new RequestHash("d".repeat(64))));
            assertThat(overridden.result().responseJson())
                    .contains("\"status\":\"ARCHIVED\"", "ownerDisplayName",
                            "capabilities", "etag");
            assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.project_product_link "
                            + "WHERE product_id = :productId AND removed_at IS NULL")
                    .param("productId", productId).query(Integer.class).single()).isEqualTo(3);
            assertThat(jdbcClient.sql("SELECT blocker_counts::text FROM yumpoo.admin_override "
                            + "WHERE target_type = 'PRODUCT' AND target_id = :productId")
                    .param("productId", productId).query(String.class).single())
                    .contains("ACTIVE_DEVELOPMENT_SUPPORT_PROJECTS", "1");
        }
    }

    @Test
    void ownerMissingProjectionFansOutAndResolvesOnlyFromCurrentFacts() {
        UUID first;
        UUID second;
        try (RequestCorrelationContext.Scope ignored = correlation("m2-03-owner-missing")) {
            first = create("OWNER_ONE_A", "Owner A", OWNER_ONE, "6");
            second = create("OWNER_ONE_B", "Owner B", OWNER_ONE, "7");
        }

        disable(OWNER_ONE);
        DomainEventEnvelope disabled = event("identity.user_account_disabled", "User", OWNER_ONE);
        ownerGovernanceProjection.consume(disabled);
        ownerGovernanceProjection.consume(disabled);
        assertThat(openOwnerMissingCount()).isEqualTo(2);

        ownerGovernanceProjection.consume(event("identity.user_employment_returned", "User", OWNER_ONE));
        assertThat(openOwnerMissingCount()).isEqualTo(2);

        enable(OWNER_ONE);
        ownerGovernanceProjection.consume(event("identity.user_account_enabled", "User", OWNER_ONE));
        assertThat(openOwnerMissingCount()).isZero();

        disable(OWNER_ONE);
        ownerGovernanceProjection.consume(event("identity.user_account_disabled", "User", OWNER_ONE));
        assertThat(openOwnerMissingCount()).isEqualTo(2);

        try (RequestCorrelationContext.Scope ignored = correlation("m2-03-owner-resolve")) {
            governanceService.archive(lifecycle(admin(), first, 0, "8"));
            governanceService.reassignOwner(reassignment(second, 0, OWNER_TWO, "9"));
        }
        ownerGovernanceProjection.consume(event("catalog.product_archived", "Product", first));
        ownerGovernanceProjection.consume(event("catalog.product_owner_reassigned", "Product", second));
        assertThat(openOwnerMissingCount()).isZero();
        assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.governance_issue WHERE issue_type = 'OWNER_MISSING' AND target_type = 'PRODUCT'")
                .query(Integer.class).single()).isEqualTo(4);
    }

    private UUID create(String code, String name, UUID ownerUserId, String hash) {
        return productService.create(new ProductCreateCommand(admin(), code, name, null,
                ownerUserId, UUID.randomUUID(), new RequestHash(hash.repeat(64))))
                .result().resourceId();
    }

    private UUID insertProject(String code, String lifecycle) {
        UUID projectId = UUID.randomUUID();
        UUID workspaceId = jdbcClient.sql("SELECT id FROM yumpoo.workspace "
                        + "WHERE company_id = :companyId ORDER BY created_at LIMIT 1")
                .param("companyId", COMPANY_ID).query(UUID.class).single();
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.project (
                            id, company_id, workspace_id, project_code, name, project_type,
                            lifecycle, owner_user_id, template_key, template_version, row_version,
                            created_at, created_by_user_id, updated_at, updated_by_user_id,
                            activated_at, archived_at
                        ) VALUES (:id, :companyId, :workspaceId, :code, :code,
                            'PRODUCT_DEVELOPMENT', 'DRAFT', :ownerId, 'RND', 1, 0,
                            transaction_timestamp(), :ownerId, transaction_timestamp(), :ownerId,
                            NULL, NULL)
                        """).param("id", projectId).param("companyId", COMPANY_ID)
                .param("workspaceId", workspaceId).param("code", code)
                .param("ownerId", OWNER_ONE).update();
        jdbcClient.sql("""
                        INSERT INTO yumpoo.project_membership (
                            id, company_id, project_id, user_id, status, joined_at,
                            joined_by_user_id, row_version
                        ) VALUES (:id, :companyId, :projectId, :ownerId, 'ACTIVE',
                            transaction_timestamp(), :ownerId, 0)
                        """).param("id", UUID.randomUUID()).param("companyId", COMPANY_ID)
                .param("projectId", projectId).param("ownerId", OWNER_ONE).update();
        if ("ACTIVE".equals(lifecycle)) {
            jdbcClient.sql("""
                            UPDATE yumpoo.project SET lifecycle = 'ACTIVE',
                                activated_at = transaction_timestamp(),
                                updated_at = transaction_timestamp(), row_version = 1
                            WHERE id = :id
                            """).param("id", projectId).update();
        }
        });
        return projectId;
    }

    private void insertLink(UUID projectId, UUID productId, String relationType) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.project_product_link (
                            id, company_id, project_id, product_id, relation_type, is_primary,
                            linked_at, linked_by_user_id, updated_at, updated_by_user_id, row_version
                        ) VALUES (:id, :companyId, :projectId, :productId, :relationType, false,
                            transaction_timestamp(), :ownerId, transaction_timestamp(), :ownerId, 0)
                        """).param("id", UUID.randomUUID()).param("companyId", COMPANY_ID)
                .param("projectId", projectId).param("productId", productId)
                .param("relationType", relationType).param("ownerId", OWNER_ONE).update();
    }

    private ProductLifecycleGovernanceCommand lifecycle(
            CurrentActor actor, UUID productId, long version, String hash) {
        return new ProductLifecycleGovernanceCommand(actor, productId, version, UUID.randomUUID(),
                new RequestHash(hash.repeat(64)));
    }

    private ProductOwnerReassignmentCommand reassignment(
            UUID productId, long version, UUID newOwnerId, String hash) {
        return new ProductOwnerReassignmentCommand(admin(), productId, version, newOwnerId,
                "负责人岗位调整并完成交接", UUID.randomUUID(), new RequestHash(hash.repeat(64)),
                "WEB", "m2-03-test");
    }

    private void assertAtomicCounts(
            UUID productId, UUID ownerId, long version, int audit, int events, int idempotency) {
        assertThat(jdbcClient.sql("SELECT owner_user_id FROM yumpoo.product WHERE id = :id")
                .param("id", productId).query(UUID.class).single()).isEqualTo(ownerId);
        assertThat(jdbcClient.sql("SELECT row_version FROM yumpoo.product WHERE id = :id")
                .param("id", productId).query(Long.class).single()).isEqualTo(version);
        assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.security_audit_event WHERE target_type = 'PRODUCT' AND target_id = :id")
                .param("id", productId.toString()).query(Integer.class).single()).isEqualTo(audit);
        assertThat(eventCount(productId, "catalog.product_owner_reassigned")).isEqualTo(events);
        assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.idempotency_record WHERE route_key = 'reassignProductOwner'")
                .query(Integer.class).single()).isEqualTo(idempotency);
    }

    private int eventCount(UUID productId, String eventType) {
        return jdbcClient.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE aggregate_id = :id AND event_type = :type")
                .param("id", productId).param("type", eventType).query(Integer.class).single();
    }

    private String eventPayload(UUID productId, String eventType) {
        return jdbcClient.sql("SELECT payload_json::text FROM yumpoo.outbox_event WHERE aggregate_id = :id AND event_type = :type")
                .param("id", productId).param("type", eventType).query(String.class).single();
    }

    private void insertUser(UUID id, String name, String accountStatus) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.identity_user (
                            id, company_id, employment_status, account_status, display_name,
                            directory_synced_at, authorization_version, row_version, created_at, updated_at
                        ) VALUES (:id, :companyId, 'ACTIVE', :accountStatus, :name,
                            transaction_timestamp(), 0, 0, transaction_timestamp(), transaction_timestamp())
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", id).param("companyId", COMPANY_ID)
                .param("accountStatus", accountStatus).param("name", name).update();
    }

    private void disable(UUID userId) {
        jdbcClient.sql("""
                        UPDATE yumpoo.identity_user
                        SET account_status = 'DISABLED', account_disabled_at = transaction_timestamp(),
                            account_disabled_by_user_id = :adminId, account_disabled_reason = 'M2-03 projection',
                            updated_at = transaction_timestamp(), row_version = row_version + 1
                        WHERE id = :userId
                        """).param("adminId", ADMIN_ID).param("userId", userId).update();
    }

    private void enable(UUID userId) {
        jdbcClient.sql("""
                        UPDATE yumpoo.identity_user
                        SET account_status = 'ENABLED', account_disabled_at = NULL,
                            account_disabled_by_user_id = NULL, account_disabled_reason = NULL,
                            updated_at = transaction_timestamp(), row_version = row_version + 1
                        WHERE id = :userId
                        """).param("userId", userId).update();
    }

    private int openOwnerMissingCount() {
        return jdbcClient.sql("SELECT count(*) FROM yumpoo.governance_issue WHERE issue_type = 'OWNER_MISSING' AND target_type = 'PRODUCT' AND status = 'OPEN'")
                .query(Integer.class).single();
    }

    private DomainEventEnvelope event(String eventType, String aggregateType, UUID aggregateId) {
        return new DomainEventEnvelope(UUID.randomUUID(), eventType, 1, Instant.now(), aggregateType,
                aggregateId, 1, COMPANY_ID, EventActor.system("M2_03_TEST"),
                "m203-projection", "m203-projection", null, objectMapper.createObjectNode());
    }

    private void cleanUp() {
        jdbcClient.sql("DELETE FROM yumpoo.admin_override WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
        jdbcClient.sql("DELETE FROM yumpoo.project_product_link WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.project_membership WHERE company_id = :companyId "
                        + "AND project_id IN (SELECT id FROM yumpoo.project "
                        + "WHERE company_id = :companyId AND project_code LIKE 'M2_24_%')")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.project WHERE company_id = :companyId "
                        + "AND project_code LIKE 'M2_24_%'")
                .param("companyId", COMPANY_ID).update();
        });
        jdbcClient.sql("DELETE FROM yumpoo.governance_issue WHERE target_type = 'PRODUCT'").update();
        jdbcClient.sql("DELETE FROM yumpoo.security_audit_event WHERE target_type = 'PRODUCT'").update();
        jdbcClient.sql("DELETE FROM yumpoo.outbox_consumer_receipt WHERE event_id IN (SELECT event_id FROM yumpoo.outbox_event WHERE company_id = :companyId)")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.product WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.idempotency_record WHERE actor_user_id IN (:adminId, :ownerOne, :ownerTwo, :memberId)")
                .param("adminId", ADMIN_ID).param("ownerOne", OWNER_ONE)
                .param("ownerTwo", OWNER_TWO).param("memberId", MEMBER_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.outbox_event WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID).update();
        jdbcClient.sql("DELETE FROM yumpoo.identity_user WHERE id IN (:adminId, :ownerOne, :ownerTwo, :memberId)")
                .param("adminId", ADMIN_ID).param("ownerOne", OWNER_ONE)
                .param("ownerTwo", OWNER_TWO).param("memberId", MEMBER_ID).update();
    }

    private static RequestCorrelationContext.Scope correlation(String requestId) {
        return RequestCorrelationContext.open(RequestCorrelation.root(requestId));
    }

    private static CurrentActor admin() {
        return new CurrentActor(ADMIN_ID, COMPANY_ID, 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));
    }

    private static CurrentActor owner(UUID id) {
        return new CurrentActor(id, COMPANY_ID, 0, Set.of());
    }

    private static CurrentActor member() {
        return owner(MEMBER_ID);
    }
}
