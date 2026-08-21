package com.yumpoo.platform.catalog.infrastructure.project;

import com.yumpoo.platform.catalog.application.product.ProductListStatus;
import com.yumpoo.platform.catalog.application.product.ProductService;
import com.yumpoo.platform.catalog.application.product.ProductUpdateCommand;
import com.yumpoo.platform.catalog.application.project.ProjectLifecycleFilter;
import com.yumpoo.platform.catalog.application.project.ProjectProductLinkCommands.ChangePrimary;
import com.yumpoo.platform.catalog.application.project.ProjectProductLinkCommands.Create;
import com.yumpoo.platform.catalog.application.project.ProjectProductLinkCommands.Remove;
import com.yumpoo.platform.catalog.application.project.ProjectProductLinkModels.LinkView;
import com.yumpoo.platform.catalog.application.project.ProjectProductLinkService;
import com.yumpoo.platform.catalog.application.project.ProjectProductRelation;
import com.yumpoo.platform.catalog.application.project.ProjectService;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
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

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "yumpoo.outbox.enabled=false")
class ProjectProductLinkIT {

    private static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OWNER = UUID.fromString("27000000-0000-4000-8000-000000000101");
    private static final UUID MEMBER = UUID.fromString("27000000-0000-4000-8000-000000000102");
    private static final UUID ADMIN = UUID.fromString("27000000-0000-4000-8000-000000000103");
    private static final UUID WORKSPACE = UUID.fromString("27000000-0000-4000-8000-000000000104");
    private static final UUID PROJECT = UUID.fromString("27000000-0000-4000-8000-000000000105");
    private static final UUID PRODUCT_ONE = UUID.fromString("27000000-0000-4000-8000-000000000106");
    private static final UUID PRODUCT_TWO = UUID.fromString("27000000-0000-4000-8000-000000000107");

    @Autowired ProjectProductLinkService links;
    @Autowired ProductService products;
    @Autowired ProjectService projects;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        clean();
        insertUser(OWNER, "M2-07 Owner");
        insertUser(MEMBER, "M2-07 Member");
        insertUser(ADMIN, "M2-07 Admin");
        jdbc.sql("""
                INSERT INTO yumpoo.workspace(id,company_id,code,name,sort_order,status,row_version,
                    created_at,created_by_user_id,updated_at,updated_by_user_id)
                VALUES(:id,:company,'M2_07','M2-07 Workspace',10,'ACTIVE',0,
                    transaction_timestamp(),:owner,transaction_timestamp(),:owner)
                """).param("id", WORKSPACE).param("company", COMPANY).param("owner", OWNER).update();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("""
                    INSERT INTO yumpoo.project(id,company_id,workspace_id,project_code,name,
                        project_type,lifecycle,owner_user_id,template_key,template_version,row_version,
                        created_at,created_by_user_id,updated_at,updated_by_user_id,activated_at)
                    VALUES(:id,:company,:workspace,'M2_07_PROJECT','M2-07 Project',
                        'PRODUCT_DEVELOPMENT','ACTIVE',:owner,'RND',1,0,
                        transaction_timestamp(),:owner,transaction_timestamp(),:owner,
                        transaction_timestamp())
                    """).param("id", PROJECT).param("company", COMPANY)
                    .param("workspace", WORKSPACE).param("owner", OWNER).update();
            insertMembership(OWNER, true);
        });
        insertMembership(MEMBER, false);
        insertProduct(PRODUCT_ONE, "M2_07_ONE", "M2-07 One", "ACTIVE");
        insertProduct(PRODUCT_TWO, "M2_07_TWO", "M2-07 Two", "ACTIVE");
    }

    @AfterEach
    void tearDown() {
        dropOutboxFailure();
        clean();
    }

    @Test
    void fourTypesPrimaryUniquenessSoftRemovalAndFreshRelinkPreserveProjectVersion() {
        assertThat(links.findCandidates(owner(), PROJECT, "M2_07", new OffsetPageRequest(0, 20)).items())
                .extracting(candidate -> candidate.id())
                .containsExactlyInAnyOrder(PRODUCT_ONE, PRODUCT_TWO);

        LinkView primary = create(PRODUCT_ONE, ProjectProductRelation.DEVELOPMENT, true, "a");
        for (ProjectProductRelation type : Set.of(ProjectProductRelation.DELIVERY,
                ProjectProductRelation.SUPPORT, ProjectProductRelation.USED_BY)) {
            create(PRODUCT_ONE, type, false, type.name().substring(0, 1).toLowerCase());
        }
        assertThat(links.findActive(owner(), PROJECT).items()).hasSize(4)
                .first().extracting(LinkView::id).isEqualTo(primary.id());
        assertThat(projectVersion()).isZero();

        assertThatThrownBy(() -> create(PRODUCT_ONE, ProjectProductRelation.DEVELOPMENT, false, "e"))
                .isInstanceOfSatisfying(ApplicationException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(StandardErrorCode.INVALID_STATE_TRANSITION));
        assertThatThrownBy(() -> create(PRODUCT_TWO, ProjectProductRelation.DELIVERY, true, "f"))
                .isInstanceOfSatisfying(ApplicationException.class, error ->
                        assertThat(error.reason()).isEqualTo("PRIMARY_PRODUCT_ALREADY_EXISTS"));

        RemovedFact removed = remove(primary, "  主关系不再适用  ", "1");
        assertThat(removed.reason()).isEqualTo("主关系不再适用");
        assertThat(removed.rowVersion()).isOne();
        LinkView relinked = create(PRODUCT_ONE, ProjectProductRelation.DEVELOPMENT, true, "2");
        assertThat(relinked.id()).isNotEqualTo(primary.id());
        assertThat(projectVersion()).isZero();
    }

    @Test
    void relationControlsProductReadScopeButNeverGrantsWritesOrProjectPermissions() {
        LinkView link = create(PRODUCT_ONE, ProjectProductRelation.SUPPORT, false, "3");

        assertThat(products.findAll(member(), ProductListStatus.ACTIVE,
                new OffsetPageRequest(0, 20)).items()).extracting(item -> item.id())
                .containsExactly(PRODUCT_ONE);
        assertThat(projects.findAll(member(), null, null, ProjectLifecycleFilter.ALL, PRODUCT_ONE,
                new OffsetPageRequest(0, 20)).totalElements()).isOne();
        assertThatThrownBy(() -> products.update(new ProductUpdateCommand(member(), PRODUCT_ONE, 0,
                "越权修改", null))).isInstanceOfSatisfying(ApplicationException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(StandardErrorCode.ACCESS_DENIED));
        assertThatThrownBy(() -> createAs(member(), PRODUCT_TWO, ProjectProductRelation.DELIVERY,
                false, "4")).isInstanceOfSatisfying(ApplicationException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(StandardErrorCode.ACCESS_DENIED));
        assertThatThrownBy(() -> createAs(admin(), PRODUCT_TWO, ProjectProductRelation.DELIVERY,
                false, "5")).isInstanceOfSatisfying(ApplicationException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(StandardErrorCode.ACCESS_DENIED));

        remove(link, null, "6");
        assertThat(products.findAll(member(), ProductListStatus.ALL,
                new OffsetPageRequest(0, 20)).totalElements()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.project_membership WHERE project_id=:project")
                .param("project", PROJECT).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void archivedProductBlocksNewLinkButDoesNotBlockUnlink() {
        LinkView link = create(PRODUCT_ONE, ProjectProductRelation.USED_BY, false, "7");
        jdbc.sql("UPDATE yumpoo.product SET status='ARCHIVED', archived_at=updated_at, "
                        + "archived_by_user_id=:owner WHERE id=:id")
                .param("owner", OWNER).param("id", PRODUCT_ONE).update();

        assertThat(remove(link, null, "8").rowVersion()).isOne();
        assertThatThrownBy(() -> create(PRODUCT_ONE, ProjectProductRelation.SUPPORT, false, "9"))
                .isInstanceOfSatisfying(ApplicationException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(StandardErrorCode.VALIDATION_FAILED));

        jdbc.sql("UPDATE yumpoo.project SET lifecycle='ARCHIVED', archived_at=updated_at WHERE id=:id")
                .param("id", PROJECT).update();
        assertThatThrownBy(() -> create(PRODUCT_TWO, ProjectProductRelation.SUPPORT, false, "10"))
                .isInstanceOfSatisfying(ApplicationException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(StandardErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void concurrentPrimaryCreationHasOneWinnerAndOutboxFailureRollsBackFactAndIdempotency() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentCreate(start, PRODUCT_ONE, "a"));
            var second = executor.submit(() -> concurrentCreate(start, PRODUCT_TWO, "b"));
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.project_product_link WHERE project_id=:project "
                        + "AND removed_at IS NULL AND is_primary")
                .param("project", PROJECT).query(Integer.class).single()).isOne();

        LinkView winner = links.findActive(owner(), PROJECT).items().getFirst();
        remove(winner, null, "c");
        installOutboxFailure();
        assertThatThrownBy(() -> create(PRODUCT_ONE, ProjectProductRelation.DELIVERY, false, "d"))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.project_product_link WHERE project_id=:project "
                        + "AND product_id=:product AND relation_type='DELIVERY' AND removed_at IS NULL")
                .param("project", PROJECT).param("product", PRODUCT_ONE)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.idempotency_record "
                        + "WHERE route_key='createProjectProductLink'")
                .query(Integer.class).single()).isOne();
    }

    private boolean concurrentCreate(CountDownLatch start, UUID productId, String hash) {
        try {
            start.await(5, TimeUnit.SECONDS);
            try (RequestCorrelationContext.Scope ignored = correlation("m207-concurrent-" + hash)) {
                createAs(owner(), productId, ProjectProductRelation.DEVELOPMENT, true, hash);
            }
            return true;
        } catch (ApplicationException expected) {
            return false;
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private LinkView create(UUID productId, ProjectProductRelation type, boolean primary, String hash) {
        try (RequestCorrelationContext.Scope ignored = correlation("m207-create-" + hash)) {
            return createAs(owner(), productId, type, primary, hash);
        }
    }

    private LinkView createAs(CurrentActor actor, UUID productId, ProjectProductRelation type,
                              boolean primary, String hash) {
        return links.create(new Create(actor, PROJECT, productId, type, primary, UUID.randomUUID(),
                requestHash(hash))).result().resourceId() == null ? null
                : links.findActive(owner(), PROJECT).items().stream()
                .filter(link -> link.productId().equals(productId) && link.relationType().equals(type.name()))
                .findFirst().orElseThrow();
    }

    private RemovedFact remove(LinkView link, String reason, String hash) {
        try (RequestCorrelationContext.Scope ignored = correlation("m207-remove-" + hash)) {
            links.remove(new Remove(owner(), PROJECT, link.id(), link.rowVersion(), reason,
                    UUID.randomUUID(), requestHash(hash)));
            return jdbc.sql("SELECT remove_reason, row_version FROM yumpoo.project_product_link WHERE id=:id")
                    .param("id", link.id()).query((rs, row) ->
                            new RemovedFact(rs.getString("remove_reason"), rs.getLong("row_version")))
                    .single();
        }
    }

    private long projectVersion() {
        return jdbc.sql("SELECT row_version FROM yumpoo.project WHERE id=:id")
                .param("id", PROJECT).query(Long.class).single();
    }

    private void insertUser(UUID id, String name) {
        jdbc.sql("""
                INSERT INTO yumpoo.identity_user(id,company_id,employment_status,account_status,
                    display_name,directory_synced_at,authorization_version,row_version,created_at,updated_at)
                VALUES(:id,:company,'ACTIVE','ENABLED',:name,transaction_timestamp(),0,0,
                    transaction_timestamp(),transaction_timestamp())
                """).param("id", id).param("company", COMPANY).param("name", name).update();
    }

    private void insertMembership(UUID userId, boolean ownerMembership) {
        jdbc.sql("""
                INSERT INTO yumpoo.project_membership(id,company_id,project_id,user_id,status,
                    joined_at,joined_by_user_id,row_version)
                VALUES(:id,:company,:project,:user,'ACTIVE',transaction_timestamp(),:joinedBy,0)
                """).param("id", UUID.randomUUID()).param("company", COMPANY)
                .param("project", PROJECT).param("user", userId)
                .param("joinedBy", ownerMembership ? userId : OWNER).update();
    }

    private void insertProduct(UUID id, String code, String name, String status) {
        jdbc.sql("""
                INSERT INTO yumpoo.product(id,company_id,product_code,name,status,owner_user_id,row_version,
                    created_at,created_by_user_id,updated_at,updated_by_user_id)
                VALUES(:id,:company,:code,:name,:status,:owner,0,transaction_timestamp(),:owner,
                    transaction_timestamp(),:owner)
                """).param("id", id).param("company", COMPANY).param("code", code)
                .param("name", name).param("status", status).param("owner", OWNER).update();
    }

    private void installOutboxFailure() {
        jdbc.sql("CREATE OR REPLACE FUNCTION yumpoo.m207_fail_outbox() RETURNS trigger LANGUAGE plpgsql "
                + "AS 'BEGIN RAISE EXCEPTION ''M2-07 injected outbox failure''; END'").update();
        jdbc.sql("CREATE TRIGGER m207_fail_outbox BEFORE INSERT ON yumpoo.outbox_event "
                + "FOR EACH ROW EXECUTE FUNCTION yumpoo.m207_fail_outbox()").update();
    }

    private void dropOutboxFailure() {
        try {
            jdbc.sql("DROP TRIGGER IF EXISTS m207_fail_outbox ON yumpoo.outbox_event").update();
            jdbc.sql("DROP FUNCTION IF EXISTS yumpoo.m207_fail_outbox()").update();
        } catch (RuntimeException ignored) {
            // Database may not have started.
        }
    }

    private void clean() {
        try {
            jdbc.sql("DELETE FROM yumpoo.project_product_link WHERE company_id=:company")
                    .param("company", COMPANY).update();
            jdbc.sql("DELETE FROM yumpoo.idempotency_record WHERE actor_user_id IN (:owner,:member,:admin)")
                    .param("owner", OWNER).param("member", MEMBER).param("admin", ADMIN).update();
            jdbc.sql("DELETE FROM yumpoo.outbox_event WHERE company_id=:company")
                    .param("company", COMPANY).update();
            jdbc.sql("DELETE FROM yumpoo.product WHERE company_id=:company")
                    .param("company", COMPANY).update();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.sql("DELETE FROM yumpoo.project_membership WHERE project_id=:project")
                        .param("project", PROJECT).update();
                jdbc.sql("DELETE FROM yumpoo.project WHERE id=:project")
                        .param("project", PROJECT).update();
            });
            jdbc.sql("DELETE FROM yumpoo.workspace WHERE id=:workspace").param("workspace", WORKSPACE).update();
            jdbc.sql("DELETE FROM yumpoo.identity_user WHERE id IN (:owner,:member,:admin)")
                    .param("owner", OWNER).param("member", MEMBER).param("admin", ADMIN).update();
        } catch (RuntimeException ignored) {
            // Database may not have started.
        }
    }

    private static RequestHash requestHash(String seed) {
        int nibble = Math.floorMod(seed.hashCode(), 16);
        return new RequestHash(Integer.toHexString(nibble).repeat(64));
    }

    private static RequestCorrelationContext.Scope correlation(String requestId) {
        return RequestCorrelationContext.open(RequestCorrelation.root(requestId));
    }

    private static CurrentActor owner() {
        return new CurrentActor(OWNER, COMPANY, 0, Set.of());
    }

    private static CurrentActor member() {
        return new CurrentActor(MEMBER, COMPANY, 0, Set.of());
    }

    private static CurrentActor admin() {
        return new CurrentActor(ADMIN, COMPANY, 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));
    }

    private record RemovedFact(String reason, long rowVersion) {}
}
