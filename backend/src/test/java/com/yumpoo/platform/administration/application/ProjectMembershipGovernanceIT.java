package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.catalog.api.*;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.foundation.application.request.*;
import com.yumpoo.platform.identityaccess.api.*;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import com.yumpoo.platform.administration.infrastructure.governance.JdbcProjectOwnerGovernanceProjection;
import com.yumpoo.platform.foundation.application.event.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,
        properties="yumpoo.outbox.enabled=false")
class ProjectMembershipGovernanceIT {
    private static final UUID COMPANY=UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ADMIN=UUID.fromString("25000000-0000-4000-8000-000000000101");
    private static final UUID OWNER=UUID.fromString("25000000-0000-4000-8000-000000000102");
    private static final UUID MEMBER=UUID.fromString("25000000-0000-4000-8000-000000000103");
    private static final UUID NEXT_OWNER=UUID.fromString("25000000-0000-4000-8000-000000000104");
    private static final UUID LEFT=UUID.fromString("25000000-0000-4000-8000-000000000105");
    private static final UUID WORKSPACE=UUID.fromString("a460aa25-7180-490b-ab14-f9ec09049024");

    @Autowired ProjectCreationOrchestrator creation;
    @Autowired ProjectMembershipGovernanceService governance;
    @Autowired ProjectMembershipQuery query;
    @Autowired JdbcClient jdbc;
    @Autowired JdbcProjectOwnerGovernanceProjection projection;
    @Autowired tools.jackson.databind.ObjectMapper objectMapper;
    @Autowired PlatformTransactionManager transactionManager;
    private UUID projectId;

    @BeforeEach void setUp() {
        clean();
        insertUser(ADMIN,"M2-05 Admin","ACTIVE","ENABLED");
        insertUser(OWNER,"M2-05 Owner","ACTIVE","ENABLED");
        insertUser(MEMBER,"M2-05 Member","ACTIVE","ENABLED");
        insertUser(NEXT_OWNER,"M2-05 Next Owner","ACTIVE","ENABLED");
        insertUser(LEFT,"M2-05 Left","LEFT","ENABLED");
        try(RequestCorrelationContext.Scope ignored=RequestCorrelationContext.open(
                RequestCorrelation.root("m205-create"))) {
            projectId=creation.create(new ProjectCreationCommand(admin(),"M2_05_PROJECT",
                    "M2-05 Project",null,"PRODUCT_DEVELOPMENT",OWNER,"RND",1,null,null,null,null,
                    UUID.randomUUID(),new RequestHash("a".repeat(64)),"WEB","test"))
                    .result().resourceId();
        }
    }

    @AfterEach void tearDown(){dropFailureTrigger();clean();}

    @Test void ownerCanListSearchAddRemoveAndReactivateWithoutReason() {
        assertThat(query.findMembers(owner(),projectId,ProjectMembershipStatus.ALL,
                new OffsetPageRequest(0,20)).items()).singleElement().satisfies(member -> {
                    assertThat(member.owner()).isTrue(); assertThat(member.displayName()).isEqualTo("M2-05 Owner");
                });
        assertThat(query.findCandidates(owner(),projectId,"M2-05",new OffsetPageRequest(0,20)).items())
                .extracting(ProjectMemberCandidate::userId).contains(OWNER,MEMBER,NEXT_OWNER);

        ProjectMemberGovernanceCommand addCommand=memberCommand(owner(),MEMBER,null,null,"b");
        var added=execute("add",()->governance.add(addCommand));
        assertThat(added.result().httpStatus()).isEqualTo(201);
        var replay=execute("add-replay",()->governance.add(addCommand));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.result()).isEqualTo(added.result());
        assertThatThrownBy(()->execute("duplicate",()->governance.add(
                memberCommand(owner(),MEMBER,null,null,"c"))))
                .isInstanceOfSatisfying(ApplicationException.class,e->assertThat(e.errorCode())
                        .isEqualTo(StandardErrorCode.INVALID_STATE_TRANSITION));

        var removed=execute("remove",()->governance.remove(memberCommand(owner(),MEMBER,0L,null,"d")));
        assertThat(removed.result().etag()).isEqualTo("\"1\"");
        assertThat(execute("add-late-replay",()->governance.add(addCommand)).result())
                .isEqualTo(added.result());
        assertThatThrownBy(()->execute("reactivate-missing",()->governance.add(
                memberCommand(owner(),MEMBER,null,null,"e"))))
                .isInstanceOfSatisfying(ApplicationException.class,e->assertThat(e.errorCode())
                        .isEqualTo(StandardErrorCode.PRECONDITION_REQUIRED));
        var reactivated=execute("reactivate",()->governance.add(memberCommand(owner(),MEMBER,1L,null,"f")));
        assertThat(reactivated.result().httpStatus()).isEqualTo(200);
        assertThat(reactivated.result().etag()).isEqualTo("\"2\"");
        assertThat(jdbc.sql("SELECT remove_reason FROM yumpoo.project_membership WHERE project_id=:id AND user_id=:user")
                .param("id",projectId).param("user",MEMBER).query(String.class).optional()).isEmpty();
    }

    @Test void adminRequiresReasonAndReassignmentKeepsOldOwnerActive() {
        assertThatThrownBy(()->governance.add(memberCommand(admin(),MEMBER,null,null,"1")))
                .isInstanceOfSatisfying(ApplicationException.class,e->assertThat(e.errorCode())
                        .isEqualTo(StandardErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(()->governance.add(memberCommand(owner(),LEFT,null,null,"2")))
                .isInstanceOfSatisfying(ApplicationException.class,e->assertThat(e.errorCode())
                        .isEqualTo(StandardErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(()->execute("remove-owner",()->governance.remove(
                memberCommand(owner(),OWNER,0L,null,"3"))))
                .isInstanceOfSatisfying(ApplicationException.class,e->assertThat(e.errorCode())
                        .isEqualTo(StandardErrorCode.INVALID_STATE_TRANSITION));

        var changed=execute("reassign",()->governance.reassignOwner(new ProjectOwnerReassignmentCommand(
                admin(),projectId,0,NEXT_OWNER,"负责人治理重指派测试理由",UUID.randomUUID(),
                new RequestHash("4".repeat(64)),"WEB","test")));
        assertThat(changed.result().etag()).isEqualTo("\"1\"");
        assertThat(jdbc.sql("SELECT owner_user_id FROM yumpoo.project WHERE id=:id")
                .param("id",projectId).query(UUID.class).single()).isEqualTo(NEXT_OWNER);
        assertThat(jdbc.sql("SELECT status FROM yumpoo.project_membership WHERE project_id=:id AND user_id=:user")
                .param("id",projectId).param("user",OWNER).query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE event_type IN ('catalog.project_member_added','catalog.project_owner_reassigned')")
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test void staleVersionsAndArchivedWritesFail() {
        execute("add-stale",()->governance.add(memberCommand(owner(),MEMBER,null,null,"5")));
        assertThatThrownBy(()->execute("remove-stale",()->governance.remove(
                memberCommand(owner(),MEMBER,9L,null,"6"))))
                .isInstanceOfSatisfying(ApplicationException.class,e->assertThat(e.errorCode())
                        .isEqualTo(StandardErrorCode.VERSION_CONFLICT));
        jdbc.sql("UPDATE yumpoo.project SET lifecycle='ARCHIVED',activated_at=created_at,archived_at=updated_at WHERE id=:id")
                .param("id",projectId).update();
        assertThatThrownBy(()->execute("archived",()->governance.add(
                memberCommand(owner(),NEXT_OWNER,null,null,"7"))))
                .isInstanceOfSatisfying(ApplicationException.class,e->assertThat(e.errorCode())
                        .isEqualTo(StandardErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test void concurrentOwnerReassignmentsHaveSingleWinner() throws Exception {
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->reassignConcurrently(start,MEMBER,"8"));
            var second=executor.submit(()->reassignConcurrently(start,NEXT_OWNER,"9"));
            start.countDown();
            boolean firstWon=first.get(10,TimeUnit.SECONDS);
            boolean secondWon=second.get(10,TimeUnit.SECONDS);
            assertThat(firstWon ^ secondWon).isTrue();
        }
        assertThat(jdbc.sql("SELECT row_version FROM yumpoo.project WHERE id=:id")
                .param("id",projectId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.project_membership WHERE project_id=:id AND status='ACTIVE'")
                .param("id",projectId).query(Integer.class).single()).isEqualTo(2);
    }

    @Test void auditFailureRollsBackMembershipEventAndIdempotency() {
        jdbc.sql("CREATE OR REPLACE FUNCTION yumpoo.m205_fail_audit() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''M2-05 injected audit failure''; END'").update();
        jdbc.sql("CREATE TRIGGER m205_fail_audit BEFORE INSERT ON yumpoo.security_audit_event FOR EACH ROW EXECUTE FUNCTION yumpoo.m205_fail_audit()").update();
        assertThatThrownBy(()->execute("audit-failure",()->governance.add(
                memberCommand(owner(),MEMBER,null,null,"a")))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.project_membership WHERE project_id=:id AND user_id=:user")
                .param("id",projectId).param("user",MEMBER).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE event_type='catalog.project_member_added'")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.idempotency_record WHERE route_key='addProjectMember'")
                .query(Integer.class).single()).isZero();
    }

    @Test void ownerAvailabilityEventsOpenAndResolveProjectIssueIdempotently() {
        var left=identityEvent("identity.user_employment_left",OWNER,"left");
        projection.consume(left); projection.consume(left);
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.governance_issue WHERE target_type='PROJECT' AND target_id=:id AND status='OPEN'")
                .param("id",projectId).query(Integer.class).single()).isOne();
        projection.consume(identityEvent("identity.user_account_enabled",OWNER,"enabled"));
        assertThat(jdbc.sql("SELECT status FROM yumpoo.governance_issue WHERE target_type='PROJECT' AND target_id=:id")
                .param("id",projectId).query(String.class).single()).isEqualTo("RESOLVED");
    }

    @Test void permissionMatrixHidesOrDeniesAtTheCorrectBoundary() {
        CurrentActor ordinary=new CurrentActor(MEMBER,COMPANY,0,Set.of());
        CurrentActor appManager=new CurrentActor(MEMBER,COMPANY,0,Set.of(PlatformRoleCode.APP_MANAGER));
        CurrentActor crossCompany=new CurrentActor(ADMIN,UUID.randomUUID(),0,Set.of(PlatformRoleCode.COMPANY_ADMIN));
        assertCode(StandardErrorCode.RESOURCE_NOT_FOUND,()->query.findMembers(ordinary,projectId,
                ProjectMembershipStatus.ALL,new OffsetPageRequest(0,20)));
        assertCode(StandardErrorCode.RESOURCE_NOT_FOUND,()->query.findMembers(appManager,projectId,
                ProjectMembershipStatus.ALL,new OffsetPageRequest(0,20)));
        assertCode(StandardErrorCode.RESOURCE_NOT_FOUND,()->query.findMembers(crossCompany,projectId,
                ProjectMembershipStatus.ALL,new OffsetPageRequest(0,20)));

        execute("permission-add",()->governance.add(memberCommand(owner(),MEMBER,null,null,"0")));
        assertThat(query.findMembers(ordinary,projectId,ProjectMembershipStatus.ALL,
                new OffsetPageRequest(0,20)).items()).hasSize(2);
        assertCode(StandardErrorCode.ACCESS_DENIED,()->query.findCandidates(ordinary,projectId,"M2-05",
                new OffsetPageRequest(0,20)));
        assertCode(StandardErrorCode.ACCESS_DENIED,()->governance.add(
                memberCommand(ordinary,NEXT_OWNER,null,null,"1")));
        assertThat(query.findMembers(admin(),projectId,ProjectMembershipStatus.ALL,
                new OffsetPageRequest(0,20)).items()).hasSize(2);

        CurrentActor dual=new CurrentActor(OWNER,COMPANY,0,Set.of(PlatformRoleCode.COMPANY_ADMIN));
        assertThat(execute("dual-owner",()->governance.add(
                memberCommand(dual,NEXT_OWNER,null,null,"2"))).result().httpStatus()).isEqualTo(201);
        execute("permission-remove",()->governance.remove(memberCommand(owner(),MEMBER,0L,null,"3")));
        assertCode(StandardErrorCode.RESOURCE_NOT_FOUND,()->query.findMembers(ordinary,projectId,
                ProjectMembershipStatus.ALL,new OffsetPageRequest(0,20)));
    }

    private static void assertCode(StandardErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApplicationException.class,
                exception->assertThat(exception.errorCode()).isEqualTo(code));
    }

    private DomainEventEnvelope identityEvent(String type,UUID user,String suffix) {
        return new DomainEventEnvelope(UUID.randomUUID(),type,1,java.time.Instant.now(),"IdentityUser",
                user,1,COMPANY,EventActor.system("M2_05_TEST"),"m205-"+suffix,"m205-"+suffix,
                null,objectMapper.createObjectNode());
    }

    private boolean reassignConcurrently(CountDownLatch start, UUID candidate, String hash) throws Exception {
        start.await(5,TimeUnit.SECONDS);
        try {
            execute("race-"+candidate,()->governance.reassignOwner(new ProjectOwnerReassignmentCommand(
                    admin(),projectId,0,candidate,"负责人并发治理重指派理由",UUID.randomUUID(),
                    new RequestHash(hash.repeat(64)),"WEB","test")));
            return true;
        } catch(ApplicationException exception) {
            assertThat(exception.errorCode()).isEqualTo(StandardErrorCode.VERSION_CONFLICT);
            return false;
        }
    }

    private void dropFailureTrigger() {
        jdbc.sql("DROP TRIGGER IF EXISTS m205_fail_audit ON yumpoo.security_audit_event").update();
        jdbc.sql("DROP FUNCTION IF EXISTS yumpoo.m205_fail_audit()").update();
    }

    private ProjectMemberGovernanceCommand memberCommand(CurrentActor actor,UUID user,Long version,
                                                          String reason,String hash) {
        return new ProjectMemberGovernanceCommand(actor,projectId,user,version,reason,UUID.randomUUID(),
                new RequestHash(hash.repeat(64)),"WEB","test");
    }
    private <T> T execute(String id,java.util.function.Supplier<T> action) {
        try(RequestCorrelationContext.Scope ignored=RequestCorrelationContext.open(RequestCorrelation.root("m205-"+id))) {
            return action.get();
        }
    }
    private static CurrentActor owner(){return new CurrentActor(OWNER,COMPANY,0,Set.of());}
    private static CurrentActor admin(){return new CurrentActor(ADMIN,COMPANY,0,Set.of(PlatformRoleCode.COMPANY_ADMIN));}
    private void insertUser(UUID id,String name,String employment,String account){jdbc.sql("""
            INSERT INTO yumpoo.identity_user(id,company_id,employment_status,account_status,display_name,
                directory_synced_at,left_at,left_reason,authorization_version,row_version,created_at,updated_at)
            VALUES(:id,:company,:employment,:account,:name,transaction_timestamp(),
                CASE WHEN :employment='LEFT' THEN transaction_timestamp() END,
                CASE WHEN :employment='LEFT' THEN 'M2-05 test left' END,
                0,0,transaction_timestamp(),transaction_timestamp())
            """).param("id",id).param("company",COMPANY).param("employment",employment)
            .param("account",account).param("name",name).update();}
    private void clean(){
        jdbc.sql("DELETE FROM yumpoo.governance_issue WHERE company_id=:company").param("company",COMPANY).update();
        jdbc.sql("DELETE FROM yumpoo.content WHERE company_id=:company").param("company",COMPANY).update();
        new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            jdbc.sql("DELETE FROM yumpoo.project_membership WHERE company_id=:company").param("company",COMPANY).update();
            jdbc.sql("DELETE FROM yumpoo.project WHERE company_id=:company").param("company",COMPANY).update();});
        jdbc.sql("DELETE FROM yumpoo.security_audit_event WHERE company_id=:company").param("company",COMPANY).update();
        jdbc.sql("DELETE FROM yumpoo.idempotency_record WHERE actor_user_id IN (:ids)")
                .param("ids",java.util.List.of(ADMIN,OWNER)).update();
        jdbc.sql("DELETE FROM yumpoo.outbox_consumer_receipt WHERE event_id IN (SELECT event_id FROM yumpoo.outbox_event WHERE company_id=:company)").param("company",COMPANY).update();
        jdbc.sql("DELETE FROM yumpoo.outbox_event WHERE company_id=:company").param("company",COMPANY).update();
        jdbc.sql("DELETE FROM yumpoo.identity_user WHERE id IN (:ids)")
                .param("ids",java.util.List.of(ADMIN,OWNER,MEMBER,NEXT_OWNER,LEFT)).update();
    }
}
