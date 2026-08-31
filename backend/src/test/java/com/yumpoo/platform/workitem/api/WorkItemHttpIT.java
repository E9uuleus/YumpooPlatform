package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCommandActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCommandPort;
import com.yumpoo.platform.identityaccess.api.PlatformRoleGrantCommand;
import com.yumpoo.platform.identityaccess.application.authorization.MaintenanceRoleCommand;
import com.yumpoo.platform.identityaccess.application.authorization.MaintenanceRoleMode;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMaintenanceUseCase;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningResult;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureProvisioner;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import com.yumpoo.platform.workitem.application.WorkItemLabelRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "yumpoo.outbox.enabled=false")
class WorkItemHttpIT {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID WORKSPACE_ID = UUID.fromString("a460aa25-7180-490b-ab14-f9ec09049024");
    private static final UUID PROJECT_ID = UUID.fromString("2a000000-0000-4000-8000-000000000301");
    private static final String SESSION_COOKIE = "__Host-yumpoo-session";
    private static final String CSRF_COOKIE = "__Host-yumpoo-csrf";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @LocalServerPort private int port;
    @Autowired private IdentityAcceptanceFixtureProvisioner provisioner;
    @Autowired private PlatformRoleMaintenanceUseCase maintenanceUseCase;
    @Autowired private PlatformRoleCommandPort roleCommands;
    @Autowired private CompanyConfigurationQuery companyQuery;
    @Autowired private SessionService sessions;
    @Autowired private JdbcClient jdbc;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper json;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private WorkItemLabelRepository labels;

    private ActorFixture owner;
    private ActorFixture member;
    private ActorFixture outsider;
    private ActorFixture admin;
    private UUID contentId;
    private String contentEtag;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("m210-http-" + UUID.randomUUID()))) {
            DirectoryMemberProvisioningResult ownerUser = provisioner.provision("m210-owner", "M2-10 Owner");
            DirectoryMemberProvisioningResult memberUser = provisioner.provision("m210-member", "M2-10 Member");
            DirectoryMemberProvisioningResult outsiderUser = provisioner.provision("m210-outsider", "M2-10 Outsider");
            DirectoryMemberProvisioningResult managerUser = provisioner.provision("m210-manager", "M2-10 Manager");
            DirectoryMemberProvisioningResult adminUser = provisioner.provision("m210-admin", "M2-10 Company Admin");
            var managerRole = maintenanceUseCase.execute(new MaintenanceRoleCommand(
                    companyQuery.current().companyId(), managerUser.userId(),
                    MaintenanceRoleMode.BOOTSTRAP, "M2-10 HTTP fixture"));
            roleCommands.grant(new PlatformRoleGrantCommand(COMPANY_ID, adminUser.userId(),
                    PlatformRoleCode.COMPANY_ADMIN, adminUser.rowVersion(),
                    new PlatformRoleCommandActor(managerUser.userId(),
                            managerRole.authorizationVersion(), clock.instant()),
                    UUID.randomUUID(), "a".repeat(64), "M2-10 HTTP fixture"));
            owner = actor(ownerUser.userId());
            member = actor(memberUser.userId());
            outsider = actor(outsiderUser.userId());
            admin = actor(adminUser.userId());
            createWorkspace();
            createProject(owner.userId(), member.userId());
            JsonNode content = createContent("TASKS_MAIN", "任务主表");
            contentId = UUID.fromString(content.path("id").asText());
            contentEtag = "\"" + content.path("rowVersion").asLong() + "\"";
        }
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void creationIsIdempotentNumberedSafeAndQueryableByStatus() throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        assertThat(get(collection, null).statusCode()).isEqualTo(401);
        assertThat(get(collection, outsider).statusCode()).isEqualTo(404);
        assertThat(get(collection, admin).statusCode()).isEqualTo(200);

        UUID key = UUID.randomUUID();
        String firstBody = workItemBody("  第一项  ", "MEDIUM", member.userId(),
                "  纯文本描述  ", "   ", "2026-08-22", "2026-08-29", "2026-08-30");
        HttpResponse<String> created = mutate("POST", collection, member, firstBody, null, key);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("etag")).contains("\"0\"");
        assertThat(created.headers().firstValue("location")).isPresent();
        JsonNode first = json.readTree(created.body());
        assertThat(first.path("itemNo").asText()).isEqualTo("M2_10_ACTIVE-1");
        assertThat(first.path("title").asText()).isEqualTo("第一项");
        assertThat(first.path("type").asText()).isEqualTo("TASK");
        assertThat(first.path("statusCode").asText()).isEqualTo("NOT_STARTED");
        assertThat(first.path("statusCategory").asText()).isEqualTo("TODO");
        assertThat(first.path("description").asText()).isEqualTo("纯文本描述");
        assertThat(first.path("notes").isNull()).isTrue();
        assertThat(first.path("assigneeUserId").asText()).isEqualTo(member.userId().toString());
        assertThat(first.path("assigneeDisplayName").asText()).isEqualTo("M2-10 Member");
        assertThat(first.path("timelineStartDate").asText()).isEqualTo("2026-08-22");
        assertThat(first.path("timelineEndDate").asText()).isEqualTo("2026-08-29");
        assertThat(first.path("dueDate").asText()).isEqualTo("2026-08-30");
        assertThat(first.path("rowVersion").asLong()).isZero();
        assertThat(first.path("etag").asText()).isEqualTo("\"0\"");
        assertThat(first.path("capabilities").path("canEditFields").asBoolean()).isTrue();

        HttpResponse<String> replay = mutate("POST", collection, member, firstBody, null, key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(created.body());
        HttpResponse<String> secondCreated = mutate("POST", collection, owner,
                workItemBody("第二项", "HIGH", null, "内部备注"), null, UUID.randomUUID());
        assertThat(secondCreated.statusCode()).as(secondCreated.body()).isEqualTo(201);
        assertThat(json.readTree(secondCreated.body()).path("itemNo").asText())
                .isEqualTo("M2_10_ACTIVE-2");

        JsonNode page = json.readTree(get(collection + "?page=0&size=1", member).body());
        assertThat(page.path("totalElements").asLong()).isEqualTo(2);
        assertThat(page.path("totalPages").asLong()).isEqualTo(2);
        assertThat(page.path("items").get(0).path("itemNo").asText()).isEqualTo("M2_10_ACTIVE-2");
        JsonNode grouped = json.readTree(get(collection + "?status=NOT_STARTED&status=DONE", member).body());
        assertThat(grouped.path("totalElements").asLong()).isEqualTo(2);
        JsonNode fullPage = json.readTree(get(collection + "?page=0&size=20", member).body());
        JsonNode firstSummary = fullPage.path("items").get(1);
        assertThat(firstSummary.path("assigneeDisplayName").asText()).isEqualTo("M2-10 Member");
        assertThat(firstSummary.path("description").asText()).isEqualTo("纯文本描述");
        assertThat(firstSummary.path("timelineStartDate").asText()).isEqualTo("2026-08-22");
        assertThat(get(collection + "?status=NOT_A_STATUS", member).statusCode()).isEqualTo(422);
        assertThat(get(created.headers().firstValue("location").orElseThrow(), member).body())
                .contains("纯文本描述", "M2_10_ACTIVE-1");

        assertThat(jdbc.sql("SELECT last_sequence FROM yumpoo.work_item_project_counter WHERE project_id=:id")
                .param("id", PROJECT_ID).query(Long.class).single()).isEqualTo(2L);
        List<String> payloads = jdbc.sql("""
                SELECT payload_json::text FROM yumpoo.outbox_event
                 WHERE event_type='workitem.work_item_created' ORDER BY occurred_at
                """).query(String.class).list();
        assertThat(payloads).hasSize(2);
        for (String payload : payloads) {
            JsonNode event = json.readTree(payload);
            assertThat(event.has("description")).isFalse();
            assertThat(event.has("notes")).isFalse();
            assertThat(event.has("workItemId")).isTrue();
            assertThat(event.has("reporterUserId")).isTrue();
            assertThat(event.has("assigneeUserId")).isTrue();
            assertThat(event.has("timelineStartDate")).isTrue();
        }
    }

    @Test
    void deleteRestoreIsHiddenIdempotentAuthorizedAndPreservesFactsAndRank() throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        JsonNode created = createWorkItem(collection, "待软删除", "HIGH", member.userId(),
                "2026-08-30");
        UUID workItemId = UUID.fromString(created.path("id").asText());
        String itemNo = created.path("itemNo").asText();
        String originalRank = workItemRank(workItemId);
        String path = "/api/v1/work-items/" + workItemId;
        String deleteBody = "{\"reason\":\"  已合并到主任务  \"}";
        UUID deleteKey = UUID.randomUUID();

        assertThat(mutate("DELETE", path, member, deleteBody, null, deleteKey).statusCode())
                .isEqualTo(428);
        assertThat(mutate("DELETE", path, outsider, deleteBody, "\"0\"", deleteKey).statusCode())
                .isEqualTo(404);
        assertThat(mutate("DELETE", path, admin, deleteBody, "\"0\"", deleteKey).statusCode())
                .isEqualTo(403);
        assertThat(mutate("DELETE", path, member, "{\"reason\":\"   \"}",
                "\"0\"", UUID.randomUUID()).statusCode()).isEqualTo(422);
        assertThat(mutate("DELETE", path, member,
                "{\"reason\":\"" + "删".repeat(501) + "\"}", "\"0\"",
                UUID.randomUUID()).statusCode()).isEqualTo(422);

        HttpResponse<String> deletedResponse = mutate("DELETE", path, member, deleteBody,
                "\"0\"", deleteKey);
        assertThat(deletedResponse.statusCode()).as(deletedResponse.body()).isEqualTo(200);
        JsonNode tombstone = json.readTree(deletedResponse.body());
        assertThat(tombstone.path("deleted").asBoolean()).isTrue();
        assertThat(tombstone.path("deleteReason").asText()).isEqualTo("已合并到主任务");
        assertThat(tombstone.path("deletedByUserId").asText()).isEqualTo(member.userId().toString());
        assertThat(tombstone.path("capabilities").path("canEditFields").asBoolean()).isFalse();
        assertThat(tombstone.path("capabilities").path("canDelete").asBoolean()).isFalse();
        assertThat(tombstone.path("capabilities").path("canRestore").asBoolean()).isTrue();
        assertThat(get(path, member).statusCode()).isEqualTo(404);
        assertThat(ids(body(get(collection + "?page=0&size=20", member))))
                .doesNotContain(workItemId.toString());
        assertThat(workItemEventCount(workItemId, "workitem.work_item_deleted")).isOne();

        HttpResponse<String> replay = mutate("DELETE", path, member, deleteBody,
                "\"0\"", deleteKey);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(deletedResponse.body());
        assertThat(workItemEventCount(workItemId, "workitem.work_item_deleted")).isOne();
        assertThat(mutate("DELETE", path, member, deleteBody, "\"1\"",
                UUID.randomUUID()).statusCode()).isEqualTo(409);

        String restorePath = path + "/restore";
        assertThat(mutate("POST", restorePath, member, "", null,
                UUID.randomUUID()).statusCode()).isEqualTo(428);
        assertThat(mutate("POST", restorePath, outsider, "", "\"1\"",
                UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(mutate("POST", restorePath, admin, "", "\"1\"",
                UUID.randomUUID()).statusCode()).isEqualTo(403);
        UUID restoreKey = UUID.randomUUID();
        HttpResponse<String> restoredResponse = mutate("POST", restorePath, owner, "",
                "\"1\"", restoreKey);
        assertThat(restoredResponse.statusCode()).as(restoredResponse.body()).isEqualTo(200);
        JsonNode restored = json.readTree(restoredResponse.body());
        assertThat(restored.path("deleted").asBoolean()).isFalse();
        assertThat(restored.path("deletedAt").isNull()).isTrue();
        assertThat(restored.path("itemNo").asText()).isEqualTo(itemNo);
        assertThat(restored.path("title").asText()).isEqualTo("待软删除");
        assertThat(restored.path("statusCode").asText()).isEqualTo(created.path("statusCode").asText());
        assertThat(workItemRank(workItemId)).isEqualTo(originalRank);
        assertThat(get(path, member).statusCode()).isEqualTo(200);
        assertThat(workItemEventCount(workItemId, "workitem.work_item_restored")).isOne();

        HttpResponse<String> restoreReplay = mutate("POST", restorePath, owner, "",
                "\"1\"", restoreKey);
        assertThat(restoreReplay.statusCode()).isEqualTo(200);
        assertThat(restoreReplay.body()).isEqualTo(restoredResponse.body());
        assertThat(mutate("POST", restorePath, owner, "", "\"2\"",
                UUID.randomUUID()).statusCode()).isEqualTo(409);
    }

    @Test
    void concurrentDeleteAndRestoreEachHaveOneWinner() throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        JsonNode created = createWorkItem(collection, "并发删除恢复", "MEDIUM", null, null);
        UUID workItemId = UUID.fromString(created.path("id").asText());
        String path = "/api/v1/work-items/" + workItemId;
        CountDownLatch deleteStart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> first = pool.submit(() -> {
                deleteStart.await();
                return mutate("DELETE", path, member, "{\"reason\":\"并发删除一\"}",
                        "\"0\"", UUID.randomUUID());
            });
            Future<HttpResponse<String>> second = pool.submit(() -> {
                deleteStart.await();
                return mutate("DELETE", path, owner, "{\"reason\":\"并发删除二\"}",
                        "\"0\"", UUID.randomUUID());
            });
            deleteStart.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS).statusCode(),
                    second.get(20, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 412);
        }
        assertThat(workItemEventCount(workItemId, "workitem.work_item_deleted")).isOne();

        CountDownLatch restoreStart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> first = pool.submit(() -> {
                restoreStart.await();
                return mutate("POST", path + "/restore", member, "", "\"1\"",
                        UUID.randomUUID());
            });
            Future<HttpResponse<String>> second = pool.submit(() -> {
                restoreStart.await();
                return mutate("POST", path + "/restore", owner, "", "\"1\"",
                        UUID.randomUUID());
            });
            restoreStart.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS).statusCode(),
                    second.get(20, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 412);
        }
        assertThat(workItemEventCount(workItemId, "workitem.work_item_restored")).isOne();
    }

    @Test
    void restoreMovesToLaneTopWhenHistoricalRankIsOccupiedAndArchivedParentsRejectWrites()
            throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        JsonNode occupied = createWorkItem(collection, "占位任务", "LOW", null, null);
        JsonNode deleted = createWorkItem(collection, "待恢复任务", "MEDIUM", null, null);
        UUID occupiedId = UUID.fromString(occupied.path("id").asText());
        UUID deletedId = UUID.fromString(deleted.path("id").asText());
        String deletedRank = workItemRank(deletedId);
        String deletePath = "/api/v1/work-items/" + deletedId;
        assertThat(mutate("DELETE", deletePath, owner, "{\"reason\":\"临时删除\"}",
                "\"0\"", UUID.randomUUID()).statusCode()).isEqualTo(200);
        jdbc.sql("UPDATE yumpoo.work_item SET rank=:rank WHERE id=:id")
                .param("rank", deletedRank).param("id", occupiedId).update();

        jdbc.sql("UPDATE yumpoo.content SET status='ARCHIVED', archived_at=transaction_timestamp(), "
                        + "archived_by_user_id=:actor, updated_at=transaction_timestamp(), "
                        + "updated_by_user_id=:actor, row_version=row_version+1 WHERE id=:id")
                .param("actor", owner.userId()).param("id", contentId).update();
        assertThat(mutate("POST", deletePath + "/restore", member, "", "\"1\"",
                UUID.randomUUID()).statusCode()).isEqualTo(409);
        jdbc.sql("UPDATE yumpoo.content SET status='ACTIVE', archived_at=NULL, "
                        + "archived_by_user_id=NULL, updated_at=transaction_timestamp(), "
                        + "updated_by_user_id=:actor, row_version=row_version+1 WHERE id=:id")
                .param("actor", owner.userId()).param("id", contentId).update();

        HttpResponse<String> restored = mutate("POST", deletePath + "/restore", member, "",
                "\"1\"", UUID.randomUUID());
        assertThat(restored.statusCode()).as(restored.body()).isEqualTo(200);
        assertThat(workItemRank(deletedId)).isLessThan(workItemRank(occupiedId));
        assertThatThrownBy(() -> jdbc.sql("UPDATE yumpoo.work_item SET rank=:rank WHERE id=:id")
                .param("rank", workItemRank(occupiedId)).param("id", deletedId).update())
                .isInstanceOf(RuntimeException.class);

        jdbc.sql("UPDATE yumpoo.project SET lifecycle='ARCHIVED', archived_at=transaction_timestamp(), "
                        + "updated_at=transaction_timestamp(), updated_by_user_id=:actor, "
                        + "row_version=row_version+1 WHERE id=:id")
                .param("actor", owner.userId()).param("id", PROJECT_ID).update();
        assertThat(mutate("DELETE", deletePath, owner, "{\"reason\":\"归档后拒绝\"}",
                "\"2\"", UUID.randomUUID()).statusCode()).isEqualTo(409);
    }

    @Test
    void advancedQueryCombinesEscapesSortsAndKeepsStablePages() throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        JsonNode literal = createWorkItem(collection, "Alpha_100%", "LOW", owner.userId(),
                "2026-08-20");
        JsonNode wildcard = createWorkItem(collection, "alphaX100Y", "LOW", member.userId(),
                "2026-08-21");
        JsonNode beta = createWorkItem(collection, "Beta", "HIGH", member.userId(),
                "2026-08-22");
        JsonNode noDue = createWorkItem(collection, "Gamma", "URGENT", null, null);
        jdbc.sql("UPDATE yumpoo.work_item SET status_code='IN_PROGRESS', "
                        + "status_category='IN_PROGRESS' WHERE id=:id")
                .param("id", UUID.fromString(beta.path("id").asText())).update();
        jdbc.sql("UPDATE yumpoo.work_item SET updated_at=created_at + interval '1 hour' WHERE id=:id")
                .param("id", UUID.fromString(literal.path("id").asText())).update();
        jdbc.sql("UPDATE yumpoo.work_item SET updated_at=created_at + interval '2 hours' WHERE id=:id")
                .param("id", UUID.fromString(wildcard.path("id").asText())).update();
        Instant literalUpdatedAt = jdbc.sql("SELECT updated_at FROM yumpoo.work_item WHERE id=:id")
                .param("id", UUID.fromString(literal.path("id").asText()))
                .query(Instant.class).single();

        JsonNode legacy = body(get(collection + "?page=0&size=20", member));
        assertThat(legacy.path("items").get(0).path("id").asText())
                .isEqualTo(noDue.path("id").asText());
        JsonNode caseInsensitive = body(get(collection + "?q=ALPHA", member));
        assertThat(caseInsensitive.path("totalElements").asLong()).isEqualTo(2);
        String escaped = URLEncoder.encode("ALPHA_100%", StandardCharsets.UTF_8);
        JsonNode literalOnly = body(get(collection + "?q=" + escaped, member));
        assertThat(literalOnly.path("totalElements").asLong()).isEqualTo(1);
        assertThat(literalOnly.path("items").get(0).path("id").asText())
                .isEqualTo(literal.path("id").asText());

        JsonNode combined = body(get(collection + "?status=NOT_STARTED&priority=LOW&priority=MEDIUM"
                + "&assigneeUserId=" + owner.userId()
                + "&dueFrom=2026-08-20&dueTo=2026-08-20", member));
        assertThat(combined.path("totalElements").asLong()).isEqualTo(1);
        assertThat(combined.path("items").get(0).path("id").asText())
                .isEqualTo(literal.path("id").asText());
        JsonNode updatedStrictlyAfter = body(get(collection + "?updatedAfter="
                + URLEncoder.encode(literalUpdatedAt.toString(), StandardCharsets.UTF_8), member));
        assertThat(ids(updatedStrictlyAfter)).contains(wildcard.path("id").asText())
                .doesNotContain(literal.path("id").asText());

        JsonNode priority = body(get(collection + "?sort=PRIORITY,ASC&sort=TITLE,ASC", member));
        assertThat(titles(priority)).containsExactly("Alpha_100%", "alphaX100Y", "Beta", "Gamma");
        JsonNode status = body(get(collection + "?sort=STATUS,ASC&sort=PRIORITY,DESC", member));
        assertThat(titles(status).getFirst()).isEqualTo("Gamma");
        assertThat(titles(status).subList(1, 3)).containsExactlyInAnyOrder("alphaX100Y", "Alpha_100%");
        assertThat(titles(status).getLast()).isEqualTo("Beta");
        JsonNode assignee = body(get(collection + "?sort=ASSIGNEE,ASC", member));
        assertThat(titles(assignee).subList(0, 2)).containsExactlyInAnyOrder("alphaX100Y", "Beta");
        assertThat(titles(assignee).subList(2, 4)).containsExactly("Alpha_100%", "Gamma");
        JsonNode dueDesc = body(get(collection + "?sort=DUE_DATE,DESC", member));
        assertThat(titles(dueDesc)).containsExactly("Beta", "alphaX100Y", "Alpha_100%", "Gamma");

        List<String> stableIds = List.of(literal.path("id").asText(), wildcard.path("id").asText())
                .stream().sorted().toList();
        JsonNode firstPage = body(get(collection + "?priority=LOW&sort=PRIORITY,ASC&page=0&size=1", member));
        JsonNode secondPage = body(get(collection + "?priority=LOW&sort=PRIORITY,ASC&page=1&size=1", member));
        assertThat(firstPage.path("totalElements").asLong()).isEqualTo(2);
        assertThat(List.of(firstPage.path("items").get(0).path("id").asText(),
                secondPage.path("items").get(0).path("id").asText())).isEqualTo(stableIds);

        for (String query : List.of("sort=NOPE,ASC", "sort=TITLE", "sort=TITLE,ASC&sort=TITLE,DESC",
                "sort=TITLE,ASC&sort=STATUS,ASC&sort=PRIORITY,ASC&sort=DUE_DATE,ASC"))
            assertThat(get(collection + "?" + query, member).statusCode()).isEqualTo(422);
        assertThat(get(collection + "?sort=NOPE,ASC", outsider).statusCode()).isEqualTo(404);
        assertThat(get(collection + "?q=alpha&sort=TITLE,ASC", admin).statusCode()).isEqualTo(200);
        assertThat(workItemEventCount()).isEqualTo(4L);
    }

    @Test
    void subitemsAreNestedIdempotentRootFilteredAndSiblingScoped() throws Exception {
        String contentCollection = "/api/v1/contents/" + contentId + "/work-items";
        JsonNode parent = createWorkItem(contentCollection, "父工作项", "HIGH", member.userId(), null);
        JsonNode emptyParent = createWorkItem(contentCollection, "空父工作项", null, null, null);
        UUID otherContentId = UUID.fromString(createContent("SUB_TASKS", "子任务").path("id").asText());
        String subitemsPath = "/api/v1/work-items/" + parent.path("id").asText() + "/subitems";

        assertThat(get(subitemsPath, outsider).statusCode()).isEqualTo(404);
        assertThat(body(get(subitemsPath, member)).path("items").isEmpty()).isTrue();

        UUID createKey = UUID.randomUUID();
        String firstBody = subitemBody(otherContentId, "跨 Content 子项一", "MEDIUM");
        HttpResponse<String> firstCreated = mutate("POST", subitemsPath, member,
                firstBody, null, createKey);
        assertThat(firstCreated.statusCode()).as(firstCreated.body()).isEqualTo(201);
        assertThat(firstCreated.headers().firstValue("location")).isPresent();
        assertThat(firstCreated.headers().firstValue("etag")).contains("\"0\"");
        JsonNode first = json.readTree(firstCreated.body());
        UUID firstId = UUID.fromString(first.path("id").asText());
        assertThat(first.path("contentId").asText()).isEqualTo(otherContentId.toString());
        assertThat(first.path("type").asText()).isEqualTo("TASK");

        HttpResponse<String> replay = mutate("POST", subitemsPath, member,
                firstBody, null, createKey);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(firstCreated.body());
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_relation "
                        + "WHERE right_work_item_id=:id AND deleted_at IS NULL")
                .param("id", firstId).query(Long.class).single()).isOne();

        JsonNode second = json.readTree(mutate("POST", subitemsPath, member,
                subitemBody(contentId, "同 Content 子项二", "LOW"), null,
                UUID.randomUUID()).body());
        UUID secondId = UUID.fromString(second.path("id").asText());

        JsonNode direct = body(get(subitemsPath, member));
        assertThat(titles(direct)).containsExactly("同 Content 子项二", "跨 Content 子项一");
        assertThat(direct.path("items").get(0).path("subitemCount").asLong()).isZero();

        JsonNode roots = body(get("/api/v1/projects/" + PROJECT_ID + "/work-items", member));
        assertThat(ids(roots)).containsExactlyInAnyOrder(
                parent.path("id").asText(), emptyParent.path("id").asText());
        JsonNode parentSummary = java.util.stream.StreamSupport.stream(
                        roots.path("items").spliterator(), false)
                .filter(node -> node.path("id").asText().equals(parent.path("id").asText()))
                .findFirst().orElseThrow();
        assertThat(parentSummary.path("subitemCount").asLong()).isEqualTo(2L);
        assertThat(body(get("/api/v1/contents/" + otherContentId + "/work-items", member))
                .path("totalElements").asLong()).isZero();

        HttpResponse<String> nested = mutate("POST", "/api/v1/work-items/" + firstId + "/subitems",
                member, subitemBody(contentId, "不允许的孙项", null), null, UUID.randomUUID());
        assertThat(nested.statusCode()).as(nested.body()).isEqualTo(409);
        assertThat(json.readTree(nested.body()).path("code").asText())
                .isEqualTo("INVALID_STATE_TRANSITION");

        HttpResponse<String> moved = mutate("POST", subitemsPath + "/" + secondId + "/order-moves",
                member, "{\"previousVisibleWorkItemId\":null,\"nextVisibleWorkItemId\":\""
                        + firstId + "\"}", "\"0\"", UUID.randomUUID());
        assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);
        assertThat(moved.headers().firstValue("etag")).contains("\"1\"");
        assertThat(mutate("POST", subitemsPath + "/" + secondId + "/order-moves", member,
                "{\"previousVisibleWorkItemId\":null,\"nextVisibleWorkItemId\":\""
                        + firstId + "\"}", "\"0\"", UUID.randomUUID()).statusCode()).isEqualTo(412);

        List<String> relationEvents = jdbc.sql("""
                SELECT payload_json::text FROM yumpoo.outbox_event
                 WHERE event_type='workitem.work_item_relation_created'
                 ORDER BY occurred_at
                """).query(String.class).list();
        assertThat(relationEvents).hasSize(2);
        JsonNode relationEvent = json.readTree(relationEvents.getFirst());
        assertThat(relationEvent.path("relationType").asText()).isEqualTo("PARENT_CHILD");
        assertThat(relationEvent.path("leftWorkItemId").asText()).isEqualTo(parent.path("id").asText());
        assertThat(relationEvent.path("rightWorkItemId").asText()).isEqualTo(firstId.toString());
        assertThat(relationEvent.has("title")).isFalse();
    }

    @Test
    void ordinaryRelationsSupportAllRolesAtomicReparentDeletionAndDeletedCounterpart() throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        JsonNode firstParent = createWorkItem(collection, "父项一", "HIGH", member.userId(), null);
        JsonNode secondParent = createWorkItem(collection, "父项二", "MEDIUM", member.userId(), null);
        JsonNode thirdParent = createWorkItem(collection, "父项三", "MEDIUM", member.userId(), null);
        JsonNode child = createWorkItem(collection, "候选子项", "LOW", member.userId(), null);
        UUID firstParentId = UUID.fromString(firstParent.path("id").asText());
        UUID secondParentId = UUID.fromString(secondParent.path("id").asText());
        UUID thirdParentId = UUID.fromString(thirdParent.path("id").asText());
        UUID childId = UUID.fromString(child.path("id").asText());
        String firstRelations = "/api/v1/work-items/" + firstParentId + "/relations";
        String originalSortKey = jdbc.sql("SELECT project_sort_key FROM yumpoo.work_item WHERE id=:id")
                .param("id", childId).query(String.class).single();

        assertThat(get(firstRelations, outsider).statusCode()).isEqualTo(404);
        JsonNode adminPage = body(get(firstRelations, admin));
        assertThat(adminPage.path("canCreate").asBoolean()).isFalse();
        assertThat(mutate("POST", firstRelations, admin,
                relationBody("RELATED", "RELATED", childId), null,
                UUID.randomUUID()).statusCode()).isEqualTo(403);

        UUID parentKey = UUID.randomUUID();
        HttpResponse<String> parentCreated = mutate("POST", firstRelations, member,
                relationBody("PARENT_CHILD", "PARENT", childId), null, parentKey);
        assertThat(parentCreated.statusCode()).as(parentCreated.body()).isEqualTo(201);
        JsonNode parentRelation = json.readTree(parentCreated.body());
        assertThat(parentRelation.path("currentRole").asText()).isEqualTo("PARENT");
        assertThat(parentRelation.path("counterpartRole").asText()).isEqualTo("CHILD");

        HttpResponse<String> replay = mutate("POST", firstRelations, member,
                relationBody("PARENT_CHILD", "PARENT", childId), null, parentKey);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(parentCreated.body());
        HttpResponse<String> duplicate = mutate("POST", firstRelations, member,
                relationBody("PARENT_CHILD", "PARENT", childId), null, UUID.randomUUID());
        assertThat(duplicate.statusCode()).isEqualTo(200);
        assertThat(duplicate.body()).isEqualTo(parentCreated.body());

        List<List<String>> directed = List.of(
                List.of("RELATED", "RELATED"),
                List.of("BLOCKS", "BLOCKED_BY"),
                List.of("SOURCE", "DERIVED_FROM"),
                List.of("DUPLICATE", "CANONICAL"));
        for (List<String> relation : directed) {
            HttpResponse<String> response = mutate("POST", firstRelations, member,
                    relationBody(relation.get(0), relation.get(1), childId), null,
                    UUID.randomUUID());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            assertThat(json.readTree(response.body()).path("currentRole").asText())
                    .isEqualTo(relation.get(1));
        }
        List<String> relatedEndpoints = jdbc.sql("SELECT left_work_item_id::text, "
                        + "right_work_item_id::text FROM yumpoo.work_item_relation "
                        + "WHERE relation_type='RELATED' AND deleted_at IS NULL")
                .query((rs, row) -> List.of(rs.getString(1), rs.getString(2))).single();
        assertThat(relatedEndpoints.get(0)).isLessThan(relatedEndpoints.get(1));
        JsonNode relationPage = body(get(firstRelations + "?page=0&size=20", member));
        assertThat(relationPage.path("totalElements").asLong()).isEqualTo(5L);
        assertThat(relationPage.path("items").size()).isEqualTo(5);
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event "
                        + "WHERE event_type='workitem.work_item_relation_created'")
                .query(Long.class).single()).isEqualTo(5L);

        HttpResponse<String> childCannotParent = mutate("POST",
                "/api/v1/work-items/" + childId + "/relations", member,
                relationBody("PARENT_CHILD", "PARENT", secondParentId), null,
                UUID.randomUUID());
        assertThat(childCannotParent.statusCode()).isEqualTo(409);
        HttpResponse<String> parentWithChildrenCannotBecomeChild = mutate("POST",
                "/api/v1/work-items/" + secondParentId + "/relations", member,
                relationBody("PARENT_CHILD", "PARENT", firstParentId), null,
                UUID.randomUUID());
        assertThat(parentWithChildrenCannotBecomeChild.statusCode()).isEqualTo(409);

        JsonNode candidates = body(get("/api/v1/work-items/" + secondParentId
                + "/relation-candidates?relationType=PARENT_CHILD&currentRole=PARENT&q="
                + URLEncoder.encode(child.path("itemNo").asText(), StandardCharsets.UTF_8), member));
        assertThat(candidates.path("items").size()).isOne();
        JsonNode candidate = candidates.path("items").get(0);
        assertThat(candidate.path("eligibility").asText()).isEqualTo("REPARENT_REQUIRED");
        assertThat(candidate.path("reasonCode").asText()).isEqualTo("CHILD_ALREADY_HAS_PARENT");
        assertThat(candidate.path("activeParent").path("parent").path("id").asText())
                .isEqualTo(firstParentId.toString());

        UUID oldRelationId = UUID.fromString(parentRelation.path("id").asText());
        HttpResponse<String> reparented = mutate("POST",
                "/api/v1/work-item-relations/" + oldRelationId + "/parent-changes", member,
                "{\"newParentWorkItemId\":\"" + secondParentId
                        + "\",\"reason\":\"调整父项\"}", "\"0\"", UUID.randomUUID());
        assertThat(reparented.statusCode()).as(reparented.body()).isEqualTo(200);
        JsonNode newRelation = json.readTree(reparented.body());
        UUID newRelationId = UUID.fromString(newRelation.path("id").asText());
        assertThat(newRelationId).isNotEqualTo(oldRelationId);
        assertThat(newRelation.path("counterpart").path("id").asText())
                .isEqualTo(secondParentId.toString());
        assertThat(jdbc.sql("SELECT delete_reason FROM yumpoo.work_item_relation WHERE id=:id")
                .param("id", oldRelationId).query(String.class).single()).isEqualTo("调整父项");
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event "
                        + "WHERE event_type='workitem.work_item_parent_changed'")
                .query(Long.class).single()).isOne();
        assertThat(mutate("POST", "/api/v1/work-item-relations/" + oldRelationId
                        + "/parent-changes", member,
                "{\"newParentWorkItemId\":\"" + firstParentId
                        + "\",\"reason\":\"旧版本\"}", "\"0\"",
                UUID.randomUUID()).statusCode()).isEqualTo(412);

        HttpResponse<String> deleted = mutate("DELETE",
                "/api/v1/work-item-relations/" + newRelationId, member,
                "{\"reason\":\"解除层级\"}", "\"0\"", UUID.randomUUID());
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(200);
        assertThat(json.readTree(deleted.body()).path("status").asText()).isEqualTo("DELETED");
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_relation WHERE "
                        + "relation_type='PARENT_CHILD' AND right_work_item_id=:id AND deleted_at IS NULL")
                .param("id", childId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT project_sort_key FROM yumpoo.work_item WHERE id=:id")
                .param("id", childId).query(String.class).single()).isEqualTo(originalSortKey);
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event "
                        + "WHERE event_type='workitem.work_item_relation_deleted'")
                .query(Long.class).single()).isOne();

        HttpResponse<String> rebuilt = mutate("POST", firstRelations, member,
                relationBody("PARENT_CHILD", "PARENT", childId), null, UUID.randomUUID());
        assertThat(rebuilt.statusCode()).as(rebuilt.body()).isEqualTo(201);
        String rebuiltId = json.readTree(rebuilt.body()).path("id").asText();
        assertThat(rebuiltId)
                .isNotEqualTo(oldRelationId.toString()).isNotEqualTo(newRelationId.toString());
        CountDownLatch reparentStart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> one = pool.submit(() -> {
                reparentStart.await();
                return mutate("POST", "/api/v1/work-item-relations/" + rebuiltId
                                + "/parent-changes", member,
                        "{\"newParentWorkItemId\":\"" + secondParentId
                                + "\",\"reason\":\"并发换父一\"}", "\"0\"", UUID.randomUUID());
            });
            Future<HttpResponse<String>> two = pool.submit(() -> {
                reparentStart.await();
                return mutate("POST", "/api/v1/work-item-relations/" + rebuiltId
                                + "/parent-changes", owner,
                        "{\"newParentWorkItemId\":\"" + thirdParentId
                                + "\",\"reason\":\"并发换父二\"}", "\"0\"", UUID.randomUUID());
            });
            reparentStart.countDown();
            assertThat(List.of(one.get(20, TimeUnit.SECONDS).statusCode(),
                    two.get(20, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 412);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event "
                        + "WHERE event_type='workitem.work_item_parent_changed'")
                .query(Long.class).single()).isEqualTo(2L);

        assertThat(mutate("DELETE", "/api/v1/work-items/" + childId, member,
                "{\"reason\":\"验证已删除对端\"}", "\"0\"",
                UUID.randomUUID()).statusCode()).isEqualTo(200);
        JsonNode placeholders = body(get(firstRelations, member));
        assertThat(placeholders.path("items").size()).isEqualTo(4);
        placeholders.path("items").forEach(item ->
                assertThat(item.path("counterpart").path("deleted").asBoolean()).isTrue());
        UUID activeParentId = jdbc.sql("SELECT left_work_item_id FROM yumpoo.work_item_relation "
                        + "WHERE relation_type='PARENT_CHILD' AND right_work_item_id=:childId "
                        + "AND deleted_at IS NULL")
                .param("childId", childId).query(UUID.class).single();
        JsonNode parentPlaceholder = body(get("/api/v1/work-items/" + activeParentId
                + "/relations?relationType=PARENT_CHILD", member));
        assertThat(parentPlaceholder.path("items").size()).isOne();
        assertThat(parentPlaceholder.path("items").get(0)
                .path("counterpart").path("deleted").asBoolean()).isTrue();
        JsonNode noDeletedCandidate = body(get("/api/v1/work-items/" + firstParentId
                + "/relation-candidates?relationType=RELATED&currentRole=RELATED&q="
                + URLEncoder.encode(child.path("itemNo").asText(), StandardCharsets.UTF_8), member));
        assertThat(noDeletedCandidate.path("items").isEmpty()).isTrue();
    }

    @Test
    void crossProjectRelationsRequireBothMembershipsAndHideInvisibleCounterparts() throws Exception {
        UUID targetProjectId = createProjectFixture("M2_22_TARGET", "PRODUCT_DEVELOPMENT", "RND");
        UUID targetContentId = UUID.fromString(createContent(targetProjectId,
                "M2_22_TASKS", "跨项目任务").path("id").asText());
        JsonNode current = createWorkItem("/api/v1/contents/" + contentId + "/work-items",
                "关系源事项", "HIGH", member.userId(), null);
        JsonNode target = createWorkItem("/api/v1/contents/" + targetContentId + "/work-items",
                "关系目标事项", "MEDIUM", member.userId(), null);
        UUID currentId = UUID.fromString(current.path("id").asText());
        UUID targetId = UUID.fromString(target.path("id").asText());
        String currentRelations = "/api/v1/work-items/" + currentId + "/relations";
        String targetQuery = "&targetProjectId=" + targetProjectId;

        setMembership(targetProjectId, member.userId(), false);
        HttpResponse<String> hiddenCandidates = get("/api/v1/work-items/" + currentId
                + "/relation-candidates?relationType=RELATED&currentRole=RELATED&q=M2" + targetQuery,
                member);
        assertThat(hiddenCandidates.statusCode()).isEqualTo(404);
        HttpResponse<String> hiddenCreate = mutate("POST", currentRelations, member,
                relationBody("RELATED", "RELATED", targetProjectId, targetId), null,
                UUID.randomUUID());
        assertThat(hiddenCreate.statusCode()).isEqualTo(404);

        setMembership(targetProjectId, member.userId(), true);
        List<List<String>> types = List.of(
                List.of("RELATED", "RELATED"),
                List.of("BLOCKS", "BLOCKS"),
                List.of("SOURCE", "SOURCE"),
                List.of("DUPLICATE", "DUPLICATE_OF"));
        java.util.ArrayList<UUID> relationIds = new java.util.ArrayList<>();
        for (List<String> type : types) {
            HttpResponse<String> created = mutate("POST", currentRelations, member,
                    relationBody(type.get(0), type.get(1), targetProjectId, targetId), null,
                    UUID.randomUUID());
            assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
            relationIds.add(UUID.fromString(json.readTree(created.body()).path("id").asText()));
        }
        HttpResponse<String> crossParent = mutate("POST", currentRelations, member,
                relationBody("PARENT_CHILD", "PARENT", targetProjectId, targetId), null,
                UUID.randomUUID());
        assertThat(crossParent.statusCode()).isEqualTo(422);
        assertThat(crossParent.body()).contains("PARENT_CHILD_REQUIRES_SAME_PROJECT");
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_relation WHERE company_id=:companyId "
                        + "AND left_project_id<>right_project_id AND deleted_at IS NULL")
                .param("companyId", COMPANY_ID).query(Long.class).single()).isEqualTo(4L);
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item WHERE id IN (:ids) "
                        + "AND deleted_at IS NULL")
                .param("ids", List.of(currentId, targetId)).query(Long.class).single()).isEqualTo(2L);

        JsonNode visible = body(get(currentRelations, member));
        assertThat(visible.path("totalElements").asLong()).isEqualTo(4L);
        assertThat(visible.path("hasHiddenRelations").asBoolean()).isFalse();
        visible.path("items").forEach(item -> {
            assertThat(item.path("counterpartVisible").asBoolean()).isTrue();
            assertThat(item.path("counterpart").isObject()).isTrue();
            assertThat(item.path("capabilities").path("canDelete").asBoolean()).isTrue();
        });

        JsonNode adminPage = body(get(currentRelations, admin));
        assertThat(adminPage.path("items").get(0).path("capabilities")
                .path("canDelete").asBoolean()).isFalse();
        assertThat(mutate("DELETE", "/api/v1/work-item-relations/" + relationIds.getFirst(), admin,
                "{\"reason\":\"管理员只读\"}", "\"0\"", UUID.randomUUID()).statusCode())
                .isEqualTo(403);

        setMembership(targetProjectId, member.userId(), false);
        for (String type : List.of("RELATED", "BLOCKS", "SOURCE", "DUPLICATE", "PARENT_CHILD")) {
            JsonNode hidden = body(get(currentRelations + "?relationType=" + type, member));
            assertThat(hidden.path("items").isEmpty()).isTrue();
            assertThat(hidden.path("totalElements").asLong()).isZero();
            assertThat(hidden.path("hasHiddenRelations").asBoolean()).isTrue();
            assertThat(hidden.toString()).doesNotContain(targetId.toString())
                    .doesNotContain(targetProjectId.toString())
                    .doesNotContain(relationIds.getFirst().toString());
        }
        assertThat(mutate("DELETE", "/api/v1/work-item-relations/" + relationIds.getFirst(), member,
                "{\"reason\":\"失权删除\"}", "\"0\"", UUID.randomUUID()).statusCode())
                .isEqualTo(404);

        setMembership(targetProjectId, member.userId(), true);
        assertThat(body(get(currentRelations, member)).path("totalElements").asLong()).isEqualTo(4L);
        archiveProject(targetProjectId, true);
        HttpResponse<String> archivedCreate = mutate("POST", currentRelations, member,
                relationBody("RELATED", "RELATED", targetProjectId, targetId), null,
                UUID.randomUUID());
        assertThat(archivedCreate.statusCode()).isEqualTo(409);
        assertThat(archivedCreate.body()).contains("PROJECT_ARCHIVED");
        HttpResponse<String> archivedDelete = mutate("DELETE",
                "/api/v1/work-item-relations/" + relationIds.getFirst(), member,
                "{\"reason\":\"归档删除\"}", "\"0\"", UUID.randomUUID());
        assertThat(archivedDelete.statusCode()).isEqualTo(409);
        assertThat(archivedDelete.body()).contains("PROJECT_ARCHIVED");
        archiveProject(targetProjectId, false);
        assertThat(mutate("DELETE", "/api/v1/work-item-relations/" + relationIds.getFirst(), member,
                "{\"reason\":\"正常解除\"}", "\"0\"", UUID.randomUUID()).statusCode())
                .isEqualTo(200);

        JsonNode concurrentLeft = createWorkItem("/api/v1/contents/" + contentId + "/work-items",
                "并发左端", "LOW", member.userId(), null);
        JsonNode concurrentRight = createWorkItem("/api/v1/contents/" + targetContentId + "/work-items",
                "并发右端", "LOW", member.userId(), null);
        UUID leftId = UUID.fromString(concurrentLeft.path("id").asText());
        UUID rightId = UUID.fromString(concurrentRight.path("id").asText());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> one = pool.submit(() -> {
                start.await();
                return mutate("POST", "/api/v1/work-items/" + leftId + "/relations", member,
                        relationBody("RELATED", "RELATED", targetProjectId, rightId), null,
                        UUID.randomUUID());
            });
            Future<HttpResponse<String>> two = pool.submit(() -> {
                start.await();
                return mutate("POST", "/api/v1/work-items/" + rightId + "/relations", member,
                        relationBody("RELATED", "RELATED", PROJECT_ID, leftId), null,
                        UUID.randomUUID());
            });
            start.countDown();
            assertThat(List.of(one.get(20, TimeUnit.SECONDS).statusCode(),
                    two.get(20, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 201);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_relation WHERE relation_type='RELATED' "
                        + "AND deleted_at IS NULL AND left_work_item_id IN (:ids) AND right_work_item_id IN (:ids)")
                .param("ids", List.of(leftId, rightId)).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event "
                        + "WHERE event_type='workitem.work_item_relation_created' "
                        + "AND payload_json->>'leftWorkItemId' IN (:ids) "
                        + "AND payload_json->>'rightWorkItemId' IN (:ids)")
                .param("ids", List.of(leftId.toString(), rightId.toString()))
                .query(Long.class).single()).isOne();
    }

    @Test
    void projectCollectionAggregatesContentsAndSupportsNullablePriority() throws Exception {
        String firstCollection = "/api/v1/contents/" + contentId + "/work-items";
        UUID secondContentId = UUID.fromString(createContent("DEFECTS", "缺陷").path("id").asText());
        String secondCollection = "/api/v1/contents/" + secondContentId + "/work-items";
        JsonNode prioritized = createWorkItem(firstCollection, "有优先级", "LOW", member.userId(), null);
        JsonNode nullable = createWorkItem(secondCollection, "待定优先级", null, null, null);
        String projectCollection = "/api/v1/projects/" + PROJECT_ID + "/work-items";

        assertThat(get(projectCollection, null).statusCode()).isEqualTo(401);
        assertThat(get(projectCollection, outsider).statusCode()).isEqualTo(404);
        assertThat(get(projectCollection, admin).statusCode()).isEqualTo(200);

        JsonNode aggregate = body(get(projectCollection + "?sort=PRIORITY,ASC", member));
        assertThat(aggregate.path("items").size()).isEqualTo(2);
        assertThat(aggregate.path("nextCursor").isNull()).isTrue();
        assertThat(titles(aggregate)).containsExactly("有优先级", "待定优先级");
        assertThat(aggregate.path("items").get(1).path("priority").isNull()).isTrue();
        assertThat(ids(aggregate)).containsExactlyInAnyOrder(
                prioritized.path("id").asText(), nullable.path("id").asText());
        JsonNode light = aggregate.path("items").get(0);
        assertThat(light.path("contentName").asText()).isNotBlank();
        assertThat(light.has("description")).isFalse();
        assertThat(light.has("notes")).isFalse();
        JsonNode firstCursorPage = body(get(projectCollection + "?limit=1", member));
        assertThat(firstCursorPage.path("items").size()).isOne();
        String projectCursor = firstCursorPage.path("nextCursor").asText();
        assertThat(projectCursor).isNotBlank();
        JsonNode secondCursorPage = body(get(projectCollection + "?limit=1&cursor="
                + URLEncoder.encode(projectCursor, StandardCharsets.UTF_8), member));
        assertThat(secondCursorPage.path("items").size()).isOne();
        assertThat(ids(secondCursorPage)).doesNotContain(ids(firstCursorPage).getFirst());
        assertThat(get(projectCollection + "?limit=101", member).statusCode()).isEqualTo(422);
        assertThat(get(projectCollection + "?limit=1&q=other&cursor="
                + URLEncoder.encode(projectCursor, StandardCharsets.UTF_8), member).statusCode())
                .isEqualTo(422);
        assertThat(get(projectCollection + "?cursor=" + projectCursor + "x", member).statusCode())
                .isEqualTo(422);

        String filterOptions = projectCollection + "/filter-options";
        assertThat(get(filterOptions + "?field=STATUS", outsider).statusCode()).isEqualTo(404);
        assertThat(get(filterOptions + "?field=STATUS&limit=101", member).statusCode()).isEqualTo(422);
        JsonNode statusOptions = body(get(filterOptions + "?field=STATUS&limit=25", member));
        JsonNode initialOption = null;
        for (JsonNode option : statusOptions.path("items"))
            if ("NOT_STARTED".equals(option.path("value").asText())) initialOption = option;
        assertThat(initialOption).isNotNull();
        assertThat(initialOption.path("count").asInt()).isEqualTo(2);
        assertThat(statusOptions.path("nextCursor").isNull()).isTrue();

        String nullableResource = "/api/v1/work-items/" + nullable.path("id").asText();
        HttpResponse<String> assigned = mutate("PATCH", nullableResource + "/assignee", member,
                "{\"assigneeUserId\":\"" + member.userId() + "\"}", "\"0\"", UUID.randomUUID());
        assertThat(assigned.statusCode()).as(assigned.body()).isEqualTo(200);
        HttpResponse<String> reprioritized = mutate("PATCH", nullableResource + "/priority", member,
                "{\"priority\":\"HIGH\"}", "\"1\"", UUID.randomUUID());
        assertThat(reprioritized.statusCode()).as(reprioritized.body()).isEqualTo(200);
        HttpResponse<String> dated = mutate("PATCH", nullableResource + "/due-date", member,
                "{\"dueDate\":\"2026-09-30\"}", "\"2\"", UUID.randomUUID());
        assertThat(dated.statusCode()).as(dated.body()).isEqualTo(200);
        assertThat(json.readTree(dated.body()).path("dueDate").asText()).isEqualTo("2026-09-30");

        var updatedAtBeforeMove = jdbc.sql("SELECT updated_at FROM yumpoo.work_item WHERE id=:id")
                .param("id", UUID.fromString(nullable.path("id").asText()))
                .query(java.time.OffsetDateTime.class).single();
        HttpResponse<String> moved = mutate("POST", projectCollection + "/"
                        + nullable.path("id").asText() + "/order-moves", member,
                "{\"previousVisibleWorkItemId\":null,\"nextVisibleWorkItemId\":\""
                        + prioritized.path("id").asText() + "\"}", "\"3\"", UUID.randomUUID());
        assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT updated_at FROM yumpoo.work_item WHERE id=:id")
                .param("id", UUID.fromString(nullable.path("id").asText()))
                .query(java.time.OffsetDateTime.class).single()).isEqualTo(updatedAtBeforeMove);
        assertThat(jdbc.sql("SELECT payload_json->'priority' = 'null'::jsonb FROM yumpoo.outbox_event "
                        + "WHERE aggregate_id=:id AND event_type='workitem.work_item_created'")
                .param("id", UUID.fromString(nullable.path("id").asText()))
                .query(Boolean.class).single()).isTrue();

        JsonNode filtered = body(get(projectCollection + "?priority=LOW", member));
        assertThat(titles(filtered)).containsExactly("有优先级");
        assertThat(get(projectCollection + "?view=KANBAN", member).statusCode()).isEqualTo(422);
        assertThat(get(projectCollection + "?view=KANBAN&status=NOT_STARTED&status=DONE", member)
                .statusCode()).isEqualTo(422);
        JsonNode lane = body(get(projectCollection + "?view=KANBAN&status=NOT_STARTED", member));
        assertThat(lane.path("items").size()).isEqualTo(2);
        assertThat(lane.path("nextCursor").isNull()).isTrue();

        String resource = "/api/v1/work-items/" + prioritized.path("id").asText();
        HttpResponse<String> cleared = mutate("PATCH", resource, member,
                workItemBody("有优先级", null, member.userId(), null, null,
                        null, null, null), "\"0\"", null);
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
        assertThat(json.readTree(cleared.body()).path("priority").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT priority IS NULL FROM yumpoo.work_item WHERE id=:id")
                .param("id", UUID.fromString(prioritized.path("id").asText()))
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void statusTransitionIsExplicitIdempotentAndCapabilityDriven() throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        HttpResponse<String> created = mutate("POST", collection, member,
                workItemBody("状态迁移", "HIGH", member.userId(), "保留描述", "保留备注",
                        "2026-08-23", "2026-08-24", "2026-08-25"),
                null, UUID.randomUUID());
        JsonNode createdJson = json.readTree(created.body());
        UUID workItemId = UUID.fromString(createdJson.path("id").asText());
        String transitionPath = created.headers().firstValue("location").orElseThrow()
                + "/transitions";
        assertThat(transitionTargets(createdJson)).contains("READY", "IN_REVIEW", "CANCELED")
                .doesNotContain("NOT_STARTED");

        long eventsBefore = statusEventCount(workItemId);
        HttpResponse<String> illegal = mutate("POST", transitionPath, member,
                transitionBody("NOT_STARTED", null), "\"0\"", UUID.randomUUID());
        assertThat(illegal.statusCode()).as(illegal.body()).isEqualTo(409);
        assertThat(statusEventCount(workItemId)).isEqualTo(eventsBefore);
        assertThat(json.readTree(get("/api/v1/work-items/" + workItemId, member).body())
                .path("statusCode").asText()).isEqualTo("NOT_STARTED");

        UUID replayKey = UUID.randomUUID();
        String readyBody = transitionBody("READY", "  需求已澄清  ");
        HttpResponse<String> ready = mutate("POST", transitionPath, member,
                readyBody, "\"0\"", replayKey);
        assertThat(ready.statusCode()).as(ready.body()).isEqualTo(200);
        assertThat(ready.headers().firstValue("etag")).contains("\"1\"");
        JsonNode readyJson = json.readTree(ready.body());
        assertThat(readyJson.path("statusCode").asText()).isEqualTo("READY");
        assertThat(readyJson.path("statusCategory").asText()).isEqualTo("TODO");
        assertThat(readyJson.path("description").asText()).isEqualTo("保留描述");
        assertThat(readyJson.path("notes").asText()).isEqualTo("保留备注");
        assertThat(transitionTargets(readyJson)).contains("IN_PROGRESS", "IN_REVIEW", "CANCELED")
                .doesNotContain("READY");

        HttpResponse<String> replay = mutate("POST", transitionPath, member,
                readyBody, "\"0\"", replayKey);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(ready.body());
        assertThat(statusEventCount(workItemId)).isEqualTo(eventsBefore + 1);
        JsonNode event = json.readTree(jdbc.sql("""
                SELECT payload_json::text FROM yumpoo.outbox_event
                 WHERE aggregate_id=:id AND event_type='workitem.work_item_status_changed'
                """).param("id", workItemId).query(String.class).single());
        assertThat(event.path("fromStatus").asText()).isEqualTo("NOT_STARTED");
        assertThat(event.path("toStatus").asText()).isEqualTo("READY");
        assertThat(event.path("resolution").asText()).isEqualTo("需求已澄清");
        assertThat(event.has("description")).isFalse();
        assertThat(event.has("notes")).isFalse();

        assertThat(mutate("POST", transitionPath, member,
                transitionBody("CANCELED", null), "\"0\"", UUID.randomUUID()).statusCode())
                .isEqualTo(412);
        assertThat(mutate("POST", transitionPath, member,
                transitionBody("IN_PROGRESS", null), null, UUID.randomUUID()).statusCode())
                .isEqualTo(428);
        assertThat(mutate("POST", transitionPath, member,
                transitionBody("IN_PROGRESS", null), "\"1\"", null).statusCode())
                .isEqualTo(400);
        assertThat(mutate("POST", transitionPath, admin,
                transitionBody("IN_PROGRESS", null), "\"1\"", UUID.randomUUID()).statusCode())
                .isEqualTo(403);
        assertThat(mutate("POST", transitionPath, outsider,
                transitionBody("IN_PROGRESS", null), "\"1\"", UUID.randomUUID()).statusCode())
                .isEqualTo(404);

        HttpResponse<String> inProgress = mutate("POST", transitionPath, member,
                transitionBody("IN_PROGRESS", null), "\"1\"", UUID.randomUUID());
        HttpResponse<String> inReview = mutate("POST", transitionPath, member,
                transitionBody("IN_REVIEW", null), "\"2\"", UUID.randomUUID());
        HttpResponse<String> done = mutate("POST", transitionPath, member,
                transitionBody("DONE", null), "\"3\"", UUID.randomUUID());
        assertThat(List.of(inProgress.statusCode(), inReview.statusCode(), done.statusCode()))
                .containsOnly(200);
        JsonNode doneJson = json.readTree(done.body());
        assertThat(doneJson.path("statusCategory").asText()).isEqualTo("DONE");
        assertThat(doneJson.path("rowVersion").asLong()).isEqualTo(4);
        assertThat(transitionTargets(doneJson))
                .containsExactly("NOT_STARTED", "BACKLOG", "READY", "IN_PROGRESS", "IN_REVIEW", "CANCELED");
        assertThat(mutate("POST", "/api/v1/contents/" + contentId + "/archive",
                owner, "", contentEtag, UUID.randomUUID()).statusCode()).isEqualTo(200);
    }

    @Test
    void allFixedTemplatesExposeAndExecuteTheirInitialTransition() throws Exception {
        List<TemplateCase> cases = List.of(
                new TemplateCase("M212_RND", "PRODUCT_DEVELOPMENT", "RND", "NOT_STARTED", "READY", "TODO"),
                new TemplateCase("M212_PRE", "PRE_SALES", "PRE_SALES", "NOT_STARTED", "PREPARING", "IN_PROGRESS"),
                new TemplateCase("M212_IMPL", "IMPLEMENTATION", "IMPLEMENTATION", "NOT_STARTED", "IN_PROGRESS", "IN_PROGRESS"),
                new TemplateCase("M212_HYPER", "HYPERCARE", "HYPERCARE", "NOT_STARTED", "DIAGNOSING", "IN_PROGRESS")
        );

        for (TemplateCase fixture : cases) {
            UUID projectId = createProjectFixture(fixture.code(), fixture.projectType(), fixture.templateKey());
            JsonNode createdContent = createContent(projectId, "TASKS_MAIN", fixture.code());
            UUID nextContentId = UUID.fromString(createdContent.path("id").asText());
            HttpResponse<String> created = mutate("POST",
                    "/api/v1/contents/" + nextContentId + "/work-items", member,
                    workItemBody(fixture.code(), "MEDIUM", null, null), null, UUID.randomUUID());
            JsonNode before = json.readTree(created.body());
            assertThat(before.path("statusCode").asText()).isEqualTo(fixture.initialStatus());
            assertThat(transitionTargets(before)).contains(fixture.nextStatus());

            HttpResponse<String> transitioned = mutate("POST",
                    created.headers().firstValue("location").orElseThrow() + "/transitions",
                    member, transitionBody(fixture.nextStatus(), null), "\"0\"", UUID.randomUUID());
            assertThat(transitioned.statusCode()).as(transitioned.body()).isEqualTo(200);
            JsonNode after = json.readTree(transitioned.body());
            assertThat(after.path("statusCode").asText()).isEqualTo(fixture.nextStatus());
            assertThat(after.path("statusCategory").asText()).isEqualTo(fixture.nextCategory());
        }
    }

    @Test
    void concurrentTransitionsHaveOneWinnerAndSameKeyProducesOneEvent() throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        HttpResponse<String> created = mutate("POST", collection, member,
                workItemBody("并发迁移", "MEDIUM", null, null), null, UUID.randomUUID());
        UUID firstId = UUID.fromString(json.readTree(created.body()).path("id").asText());
        String firstPath = created.headers().firstValue("location").orElseThrow() + "/transitions";
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> ready = pool.submit(() -> {
                start.await();
                return mutate("POST", firstPath, member, transitionBody("READY", null),
                        "\"0\"", UUID.randomUUID());
            });
            Future<HttpResponse<String>> canceled = pool.submit(() -> {
                start.await();
                return mutate("POST", firstPath, member, transitionBody("CANCELED", null),
                        "\"0\"", UUID.randomUUID());
            });
            start.countDown();
            assertThat(List.of(ready.get(20, TimeUnit.SECONDS).statusCode(),
                    canceled.get(20, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 412);
        }
        assertThat(statusEventCount(firstId)).isEqualTo(1);
        assertThat(json.readTree(get("/api/v1/work-items/" + firstId, member).body())
                .path("rowVersion").asLong()).isEqualTo(1);

        HttpResponse<String> second = mutate("POST", collection, member,
                workItemBody("同键迁移", "LOW", null, null), null, UUID.randomUUID());
        UUID secondId = UUID.fromString(json.readTree(second.body()).path("id").asText());
        String secondPath = second.headers().firstValue("location").orElseThrow() + "/transitions";
        UUID key = UUID.randomUUID();
        CountDownLatch sameKeyStart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> one = pool.submit(() -> {
                sameKeyStart.await();
                return mutate("POST", secondPath, member, transitionBody("READY", null), "\"0\"", key);
            });
            Future<HttpResponse<String>> two = pool.submit(() -> {
                sameKeyStart.await();
                return mutate("POST", secondPath, member, transitionBody("READY", null), "\"0\"", key);
            });
            sameKeyStart.countDown();
            HttpResponse<String> first = one.get(20, TimeUnit.SECONDS);
            HttpResponse<String> replay = two.get(20, TimeUnit.SECONDS);
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(replay.body()).isEqualTo(first.body());
        }
        assertThat(statusEventCount(secondId)).isEqualTo(1);
    }

    @Test
    void kanbanPagingRankPlacementsNoopReplayAndCrossStatusMoveStayConsistent() throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        JsonNode first = createWorkItem(collection, "第一张", "LOW", null, null);
        JsonNode second = createWorkItem(collection, "第二张", "MEDIUM", null, null);
        JsonNode third = createWorkItem(collection, "第三张", "HIGH", null, null);

        assertThat(get(collection + "?view=KANBAN", member).statusCode()).isEqualTo(422);
        assertThat(get(collection + "?view=KANBAN&status=NOT_STARTED&status=READY", member).statusCode())
                .isEqualTo(422);
        assertThat(get(collection + "?view=KANBAN&status=NOT_STARTED&sort=TITLE,ASC", member).statusCode())
                .isEqualTo(422);
        JsonNode firstPage = json.readTree(get(
                collection + "?view=KANBAN&status=NOT_STARTED&page=0&size=2", member).body());
        JsonNode secondPage = json.readTree(get(
                collection + "?view=KANBAN&status=NOT_STARTED&page=1&size=2", member).body());
        assertThat(titles(firstPage)).containsExactly("第三张", "第二张");
        assertThat(titles(secondPage)).containsExactly("第一张");
        assertThat(firstPage.path("items").get(0).path("etag").asText()).isEqualTo("\"0\"");
        assertThat(firstPage.path("items").get(0).path("capabilities")
                .path("canMoveInKanban").asBoolean()).isTrue();

        UUID firstId = UUID.fromString(first.path("id").asText());
        String firstMovePath = "/api/v1/work-items/" + firstId + "/rank-moves";
        UUID key = UUID.randomUUID();
        String startBody = rankMoveBody("NOT_STARTED", "START", null, null);
        HttpResponse<String> moved = mutate("POST", firstMovePath, member,
                startBody, "\"0\"", key);
        assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);
        assertThat(moved.headers().firstValue("etag")).contains("\"1\"");
        HttpResponse<String> replay = mutate("POST", firstMovePath, member,
                startBody, "\"0\"", key);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(moved.body());
        assertThat(rankEventCount(firstId)).isEqualTo(1);
        assertThat(titles(json.readTree(get(
                collection + "?view=KANBAN&status=NOT_STARTED", member).body())))
                .containsExactly("第一张", "第三张", "第二张");

        long eventsBeforeNoop = workItemEventCount();
        HttpResponse<String> noop = mutate("POST", firstMovePath, member,
                startBody, "\"1\"", UUID.randomUUID());
        assertThat(noop.statusCode()).as(noop.body()).isEqualTo(200);
        assertThat(json.readTree(noop.body()).path("rowVersion").asLong()).isEqualTo(1);
        assertThat(workItemEventCount()).isEqualTo(eventsBeforeNoop);

        assertThat(mutate("POST", firstMovePath, member,
                rankMoveBody("NOT_STARTED", "START", UUID.fromString(second.path("id").asText()), null),
                "\"1\"", UUID.randomUUID()).statusCode()).isEqualTo(422);
        assertThat(mutate("POST", firstMovePath, member,
                rankMoveBody("NOT_STARTED", "BEFORE", UUID.randomUUID(), null),
                "\"1\"", UUID.randomUUID()).statusCode()).isEqualTo(409);
        assertThat(mutate("POST", firstMovePath, member, startBody,
                null, UUID.randomUUID()).statusCode()).isEqualTo(428);

        UUID secondId = UUID.fromString(second.path("id").asText());
        HttpResponse<String> cross = mutate("POST",
                "/api/v1/work-items/" + secondId + "/rank-moves", member,
                rankMoveBody("READY", "START", null, "已澄清"), "\"0\"", UUID.randomUUID());
        assertThat(cross.statusCode()).as(cross.body()).isEqualTo(200);
        JsonNode crossJson = json.readTree(cross.body());
        assertThat(crossJson.path("statusCode").asText()).isEqualTo("READY");
        assertThat(statusEventCount(secondId)).isEqualTo(1);
        assertThat(rankEventCount(secondId)).isZero();
        assertThat(titles(json.readTree(get(
                collection + "?view=KANBAN&status=READY", member).body())))
                .containsExactly("第二张");

        String rankPayload = jdbc.sql("SELECT payload_json::text FROM yumpoo.outbox_event "
                        + "WHERE aggregate_id=:id AND event_type='workitem.work_item_rank_changed'")
                .param("id", firstId).query(String.class).single();
        assertThat(rankPayload).doesNotContain("\"rank\"");
    }

    @Test
    void concurrentRankMovesOnOneCardHaveOneWinner() throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        createWorkItem(collection, "下方锚点", "LOW", null, null);
        JsonNode first = createWorkItem(collection, "并发卡片", "LOW", null, null);
        createWorkItem(collection, "上方锚点", "MEDIUM", null, null);
        UUID firstId = UUID.fromString(first.path("id").asText());
        String path = "/api/v1/work-items/" + firstId + "/rank-moves";
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> top = pool.submit(() -> {
                start.await();
                return mutate("POST", path, member,
                        rankMoveBody("NOT_STARTED", "START", null, null),
                        "\"0\"", UUID.randomUUID());
            });
            Future<HttpResponse<String>> bottom = pool.submit(() -> {
                start.await();
                return mutate("POST", path, member,
                        rankMoveBody("NOT_STARTED", "END", null, null),
                        "\"0\"", UUID.randomUUID());
            });
            start.countDown();
            assertThat(List.of(top.get(20, TimeUnit.SECONDS).statusCode(),
                    bottom.get(20, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 412);
        }
        assertThat(rankEventCount(firstId)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(DISTINCT rank) = count(*) FROM yumpoo.work_item "
                        + "WHERE content_id=:id AND status_code='NOT_STARTED' AND deleted_at IS NULL")
                .param("id", contentId).query(Boolean.class).single()).isTrue();
    }

    @Test
    void permissionsAndRealOpenItemArchiveBlockersAreEnforced() throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        String body = workItemBody("阻塞归档", "URGENT", null, null);
        assertThat(mutate("POST", collection, admin, body, null, UUID.randomUUID()).statusCode())
                .isEqualTo(403);
        assertThat(mutate("POST", collection, outsider, body, null, UUID.randomUUID()).statusCode())
                .isEqualTo(404);
        assertThat(mutate("POST", collection, member, body, null, UUID.randomUUID()).statusCode())
                .isEqualTo(201);

        HttpResponse<String> contentArchive = mutate("POST", "/api/v1/contents/" + contentId + "/archive",
                owner, "", contentEtag, UUID.randomUUID());
        assertThat(contentArchive.statusCode()).as(contentArchive.body()).isEqualTo(409);
        assertThat(contentArchive.body()).contains("CONTENT_ARCHIVE_BLOCKED", "OPEN_WORK_ITEMS", "\"count\":1");

        HttpResponse<String> projectArchive = mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/archive",
                owner, "", "\"0\"", UUID.randomUUID());
        assertThat(projectArchive.statusCode()).as(projectArchive.body()).isEqualTo(409);
        assertThat(projectArchive.body()).contains("PROJECT_ARCHIVE_BLOCKED", "OPEN_WORK_ITEMS", "\"count\":1");

        var overrideBody = json.createObjectNode();
        overrideBody.put("action", "PROJECT_ARCHIVE_WITH_OPEN_ITEMS");
        overrideBody.put("targetType", "PROJECT");
        overrideBody.put("targetId", PROJECT_ID.toString());
        overrideBody.put("reason", "M2-10 验证治理覆盖保留真实开放工作项数量");
        HttpResponse<String> override = mutate("POST", "/api/v1/admin/governance-overrides", admin,
                json.writeValueAsString(overrideBody), "\"0\"", UUID.randomUUID());
        assertThat(override.statusCode()).as(override.body()).isEqualTo(200);
        assertThat(jdbc.sql("""
                SELECT blocker_counts::text FROM yumpoo.admin_override
                 WHERE target_id=:projectId AND result='SUCCEEDED'
                """).param("projectId", PROJECT_ID).query(String.class).single())
                .contains("OPEN_WORK_ITEMS", "\"count\": 1");
    }

    @Test
    void deletingOpenItemRemovesArchiveBlockerAndRestoreWaitsForActiveParent() throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        JsonNode created = createWorkItem(collection, "删除后解除归档阻塞", "HIGH", null, null);
        UUID workItemId = UUID.fromString(created.path("id").asText());
        String itemPath = "/api/v1/work-items/" + workItemId;
        String contentPath = "/api/v1/contents/" + contentId;

        HttpResponse<String> blocked = mutate("POST", contentPath + "/archive", owner, "",
                contentEtag, UUID.randomUUID());
        assertThat(blocked.statusCode()).as(blocked.body()).isEqualTo(409);
        assertThat(mutate("DELETE", itemPath, member, "{\"reason\":\"解除归档阻塞\"}",
                "\"0\"", UUID.randomUUID()).statusCode()).isEqualTo(200);

        HttpResponse<String> archived = mutate("POST", contentPath + "/archive", owner, "",
                contentEtag, UUID.randomUUID());
        assertThat(archived.statusCode()).as(archived.body()).isEqualTo(200);
        assertThat(mutate("POST", itemPath + "/restore", member, "", "\"1\"",
                UUID.randomUUID()).statusCode()).isEqualTo(409);

        HttpResponse<String> parentRestored = mutate("POST", contentPath + "/restore", owner, "",
                archived.headers().firstValue("etag").orElseThrow(), UUID.randomUUID());
        assertThat(parentRestored.statusCode()).as(parentRestored.body()).isEqualTo(200);
        HttpResponse<String> itemRestored = mutate("POST", itemPath + "/restore", member, "",
                "\"1\"", UUID.randomUUID());
        assertThat(itemRestored.statusCode()).as(itemRestored.body()).isEqualTo(200);
        assertThat(json.readTree(itemRestored.body()).path("deleted").asBoolean()).isFalse();
    }

    @Test
    void fullSnapshotPatchEnforcesEtagMembershipDatesNoopAndSafeEvents() throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        HttpResponse<String> created = mutate("POST", collection, member,
                workItemBody("待协作", "LOW", member.userId(), "初始描述", "初始备注",
                        "2026-08-22", "2026-08-23", null), null, UUID.randomUUID());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String location = created.headers().firstValue("location").orElseThrow();

        HttpResponse<String> detail = get(location, member);
        assertThat(detail.headers().firstValue("etag")).contains("\"0\"");
        assertThat(json.readTree(get(location, admin).body()).path("capabilities")
                .path("canEditFields").asBoolean()).isFalse();
        assertThat(get(location, outsider).statusCode()).isEqualTo(404);

        String updateBody = workItemBody("  协作更新  ", "URGENT", owner.userId(),
                "  更新描述  ", "  更新备注  ", "2026-08-24", "2026-08-29", "2026-08-30");
        HttpResponse<String> updated = mutate("PATCH", location, member, updateBody, "\"0\"", null);
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertThat(updated.headers().firstValue("etag")).contains("\"1\"");
        JsonNode updatedJson = json.readTree(updated.body());
        assertThat(updatedJson.path("title").asText()).isEqualTo("协作更新");
        assertThat(updatedJson.path("priority").asText()).isEqualTo("URGENT");
        assertThat(updatedJson.path("assigneeUserId").asText()).isEqualTo(owner.userId().toString());
        assertThat(updatedJson.path("assigneeDisplayName").asText()).isEqualTo("M2-10 Owner");
        assertThat(updatedJson.path("timelineStartDate").asText()).isEqualTo("2026-08-24");
        assertThat(updatedJson.path("rowVersion").asLong()).isEqualTo(1);
        String updatedAt = updatedJson.path("updatedAt").asText();

        long eventCount = workItemEventCount();
        HttpResponse<String> noop = mutate("PATCH", location, member, updateBody, "\"1\"", null);
        assertThat(noop.statusCode()).as(noop.body()).isEqualTo(200);
        assertThat(noop.headers().firstValue("etag")).contains("\"1\"");
        assertThat(json.readTree(noop.body()).path("updatedAt").asText()).isEqualTo(updatedAt);
        assertThat(workItemEventCount()).isEqualTo(eventCount);

        HttpResponse<String> stale = mutate("PATCH", location, member,
                workItemBody("不应覆盖", "LOW", null, null, null, null, null, null),
                "\"0\"", null);
        assertThat(stale.statusCode()).isEqualTo(412);
        assertThat(json.readTree(get(location, member).body()).path("title").asText()).isEqualTo("协作更新");
        assertThat(workItemEventCount()).isEqualTo(eventCount);

        assertThat(mutate("PATCH", location, member, updateBody, null, null).statusCode()).isEqualTo(428);
        for (String invalid : List.of("W/\"1\"", "*", "\"1\",\"2\"")) {
            assertThat(mutate("PATCH", location, member, updateBody, invalid, null).statusCode())
                    .isEqualTo(400);
        }

        var forbidden = (tools.jackson.databind.node.ObjectNode) json.readTree(updateBody);
        forbidden.put("status", "DONE");
        assertThat(mutate("PATCH", location, member, json.writeValueAsString(forbidden),
                "\"1\"", null).statusCode()).isEqualTo(400);
        forbidden.remove("status");
        forbidden.remove("dueDate");
        assertThat(mutate("PATCH", location, member, json.writeValueAsString(forbidden),
                "\"1\"", null).statusCode()).isEqualTo(400);

        assertThat(mutate("PATCH", location, member,
                workItemBody("非法处理人", "LOW", outsider.userId(), null, null, null, null, null),
                "\"1\"", null).statusCode()).isEqualTo(422);
        assertThat(mutate("PATCH", location, member,
                workItemBody("倒置日期", "LOW", null, null, null,
                        "2026-08-29", "2026-08-28", null), "\"1\"", null).statusCode())
                .isEqualTo(422);
        assertThat(mutate("PATCH", location, admin, updateBody, "\"1\"", null).statusCode())
                .isEqualTo(403);
        assertThat(workItemEventCount()).isEqualTo(eventCount);

        String unassignBody = workItemBody("协作更新", "URGENT", null,
                "更新描述", "更新备注", "2026-08-24", "2026-08-29", "2026-08-30");
        HttpResponse<String> unassigned = mutate("PATCH", location, owner,
                unassignBody, "\"1\"", null);
        assertThat(unassigned.statusCode()).as(unassigned.body()).isEqualTo(200);
        assertThat(json.readTree(unassigned.body()).path("assigneeUserId").isNull()).isTrue();
        assertThat(workItemEventCount()).isEqualTo(eventCount + 2);

        List<String> eventTypes = jdbc.sql("""
                SELECT event_type FROM yumpoo.outbox_event
                 WHERE aggregate_id=:workItemId ORDER BY aggregate_version, occurred_at, event_id
                """).param("workItemId", UUID.fromString(updatedJson.path("id").asText()))
                .query(String.class).list();
        assertThat(eventTypes).containsExactly("workitem.work_item_created",
                "workitem.work_item_fields_changed", "workitem.work_item_assigned",
                "workitem.work_item_fields_changed", "workitem.work_item_unassigned");
        List<String> changedPayloads = jdbc.sql("""
                SELECT payload_json::text FROM yumpoo.outbox_event
                 WHERE aggregate_id=:workItemId AND event_type='workitem.work_item_fields_changed'
                 ORDER BY aggregate_version
                """).param("workItemId", UUID.fromString(updatedJson.path("id").asText()))
                .query(String.class).list();
        assertThat(changedPayloads).hasSize(2);
        for (String payload : changedPayloads) {
            JsonNode event = json.readTree(payload);
            assertThat(event.has("description")).isFalse();
            assertThat(event.has("notes")).isFalse();
            assertThat(event.path("changedFields").isArray()).isTrue();
        }
    }

    @Test
    void concurrentFieldUpdatesHaveOneWinnerAndArchivedContentRejectsWrites() throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        HttpResponse<String> created = mutate("POST", collection, member,
                workItemBody("并发更新", "MEDIUM", null, null, null, null, null, null),
                null, UUID.randomUUID());
        String location = created.headers().firstValue("location").orElseThrow();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> first = pool.submit(() -> {
                start.await();
                return mutate("PATCH", location, member,
                        workItemBody("先写候选 A", "HIGH", owner.userId(), null, null,
                                null, null, null), "\"0\"", null);
            });
            Future<HttpResponse<String>> second = pool.submit(() -> {
                start.await();
                return mutate("PATCH", location, member,
                        workItemBody("先写候选 B", "URGENT", member.userId(), null, null,
                                null, null, null), "\"0\"", null);
            });
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS).statusCode(),
                    second.get(20, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 412);
        }
        JsonNode winner = json.readTree(get(location, member).body());
        assertThat(winner.path("rowVersion").asLong()).isEqualTo(1);
        assertThat(winner.path("title").asText()).isIn("先写候选 A", "先写候选 B");

        jdbc.sql("UPDATE yumpoo.content SET status='ARCHIVED', archived_at=transaction_timestamp(), "
                        + "archived_by_user_id=:actor, updated_at=transaction_timestamp(), "
                        + "updated_by_user_id=:actor, row_version=row_version+1 WHERE id=:id")
                .param("actor", owner.userId()).param("id", contentId).update();
        assertThat(mutate("PATCH", location, member,
                workItemBody("归档后拒绝", "LOW", null, null, null, null, null, null),
                "\"1\"", null).statusCode()).isEqualTo(409);
    }

    @Test
    void concurrentIdempotencyAndContentArchiveRaceHaveOnlyClosedOutcomes() throws Exception {
        String collection = "/api/v1/contents/" + contentId + "/work-items";
        UUID sharedKey = UUID.randomUUID();
        String sharedBody = workItemBody("同键并发", "LOW", null, null);
        CountDownLatch sharedStart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> first = pool.submit(() -> {
                sharedStart.await();
                return mutate("POST", collection, member, sharedBody, null, sharedKey);
            });
            Future<HttpResponse<String>> second = pool.submit(() -> {
                sharedStart.await();
                return mutate("POST", collection, member, sharedBody, null, sharedKey);
            });
            sharedStart.countDown();
            HttpResponse<String> one = first.get(20, TimeUnit.SECONDS);
            HttpResponse<String> two = second.get(20, TimeUnit.SECONDS);
            assertThat(one.statusCode()).isEqualTo(201);
            assertThat(two.statusCode()).isEqualTo(201);
            assertThat(two.body()).isEqualTo(one.body());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item WHERE content_id=:id")
                .param("id", contentId).query(Long.class).single()).isEqualTo(1L);

        JsonNode racingContent = createContent("TASKS_RACE", "归档竞争");
        UUID racingContentId = UUID.fromString(racingContent.path("id").asText());
        String racingEtag = "\"" + racingContent.path("rowVersion").asLong() + "\"";
        CountDownLatch raceStart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> create = pool.submit(() -> {
                raceStart.await();
                return mutate("POST", "/api/v1/contents/" + racingContentId + "/work-items", member,
                        workItemBody("与归档竞争", "MEDIUM", null, null), null, UUID.randomUUID());
            });
            Future<HttpResponse<String>> archive = pool.submit(() -> {
                raceStart.await();
                return mutate("POST", "/api/v1/contents/" + racingContentId + "/archive", owner,
                        "", racingEtag, UUID.randomUUID());
            });
            raceStart.countDown();
            int createStatus = create.get(20, TimeUnit.SECONDS).statusCode();
            int archiveStatus = archive.get(20, TimeUnit.SECONDS).statusCode();
            assertThat(Set.of(List.of(createStatus, archiveStatus)))
                    .isIn(Set.of(List.of(201, 409)), Set.of(List.of(409, 200)));
        }
        String status = jdbc.sql("SELECT status FROM yumpoo.content WHERE id=:id")
                .param("id", racingContentId).query(String.class).single();
        long count = jdbc.sql("SELECT count(*) FROM yumpoo.work_item WHERE content_id=:id")
                .param("id", racingContentId).query(Long.class).single();
        assertThat(status.equals("ARCHIVED") ? count == 0 : count == 1).isTrue();
    }

    @Test
    void workItemUpdatesSanitizeMentionsReplayExactlyOnceAndKeepParentVersion() throws Exception {
        JsonNode created = createWorkItem("/api/v1/contents/" + contentId + "/work-items",
                "讨论目标", "MEDIUM", member.userId(), null);
        UUID workItemId = UUID.fromString(created.path("id").asText());
        String path = "/api/v1/work-items/" + workItemId + "/updates";
        String parentUpdatedAt = json.readTree(get("/api/v1/work-items/" + workItemId, member).body())
                .path("updatedAt").asText();
        UUID key = UUID.randomUUID();
        String unsafe = "<h1 style=\"color:red\">标题</h1><p onclick=\"evil()\">你好 "
                + "<span data-type=\"mention\" data-mention-user-id=\"" + owner.userId()
                + "\">@伪造名</span><script>alert(1)</script>"
                + "<a href=\"https://example.com/x\">链接</a></p>";
        String request = updateBody(unsafe);

        assertThat(get(path, null).statusCode()).isEqualTo(401);
        assertThat(get(path, outsider).statusCode()).isEqualTo(404);
        assertThat(get(path, admin).statusCode()).isEqualTo(200);
        assertThat(mutate("POST", path, outsider, request, null, UUID.randomUUID()).statusCode())
                .isEqualTo(404);
        assertThat(mutate("POST", path, admin, request, null, UUID.randomUUID()).statusCode())
                .isEqualTo(403);

        HttpResponse<String> published = mutate("POST", path, member, request, null, key);
        assertThat(published.statusCode()).as(published.body()).isEqualTo(201);
        assertThat(published.headers().firstValue("etag")).contains("\"0\"");
        assertThat(published.headers().firstValue("location")).isPresent();
        JsonNode update = json.readTree(published.body());
        assertThat(update.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(update.path("authorDisplayName").asText()).isEqualTo("M2-10 Member");
        assertThat(update.path("bodyHtml").asText())
                .contains("@M2-10 Owner", "target=\"_blank\"", "nofollow noopener noreferrer")
                .doesNotContain("伪造名", "script", "onclick", "style", "<h1");
        assertThat(update.path("bodyText").asText()).contains("标题", "你好", "@M2-10 Owner", "链接");
        assertThat(Instant.parse(update.path("editDeadlineAt").asText()))
                .isEqualTo(Instant.parse(update.path("createdAt").asText()).plusSeconds(900));

        JsonNode parent = json.readTree(get("/api/v1/work-items/" + workItemId, member).body());
        assertThat(parent.path("rowVersion").asLong()).isZero();
        assertThat(Instant.parse(parent.path("updatedAt").asText()))
                .isEqualTo(Instant.parse(parentUpdatedAt));
        HttpResponse<String> replay = mutate("POST", path, member, request, null, key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(published.body());
        assertThat(mutate("POST", path, member, updateBody("<p>不同请求</p>"), null, key).statusCode())
                .isEqualTo(409);

        UUID updateId = UUID.fromString(update.path("id").asText());
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_update WHERE id=:id")
                .param("id", updateId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT mentioned_display_name FROM yumpoo.work_item_update_mention "
                        + "WHERE update_id=:id AND mentioned_user_id=:userId")
                .param("id", updateId).param("userId", owner.userId()).query(String.class).single())
                .isEqualTo("M2-10 Owner");
        String payload = jdbc.sql("SELECT payload_json::text FROM yumpoo.outbox_event "
                        + "WHERE aggregate_id=:id AND event_type='workitem.work_item_update_published'")
                .param("id", updateId).query(String.class).single();
        assertThat(payload).contains("mentionedUserIds", owner.userId().toString())
                .doesNotContain("bodyHtml", "bodyText", "你好");

        long before = jdbc.sql("SELECT count(*) FROM yumpoo.work_item_update WHERE work_item_id=:id")
                .param("id", workItemId).query(Long.class).single();
        assertThat(mutate("POST", path, member,
                updateBody("<p><span data-type=\"mention\" data-mention-user-id=\"not-a-uuid\">x</span></p>"),
                null, UUID.randomUUID()).statusCode()).isEqualTo(422);
        HttpResponse<String> inactiveMention = mutate("POST", path, member,
                updateBody("<p><span data-type=\"mention\" data-mention-user-id=\"" + outsider.userId()
                        + "\">@外部用户</span></p>"), null, UUID.randomUUID());
        assertThat(inactiveMention.statusCode()).isEqualTo(422);
        assertThat(inactiveMention.body()).contains("MENTION_NOT_ACTIVE_PROJECT_MEMBER");
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_update WHERE work_item_id=:id")
                .param("id", workItemId).query(Long.class).single()).isEqualTo(before);
    }

    @Test
    void workItemUpdateCursorLoadsOlderAscendingWithoutConcurrentDuplicatesAndArchiveIsReadOnly()
            throws Exception {
        JsonNode created = createWorkItem("/api/v1/contents/" + contentId + "/work-items",
                "分页讨论", "LOW", null, null);
        UUID workItemId = UUID.fromString(created.path("id").asText());
        String path = "/api/v1/work-items/" + workItemId + "/updates";
        for (String text : List.of("第一条", "第二条", "第三条")) {
            assertThat(mutate("POST", path, owner, updateBody("<p>" + text + "</p>"), null,
                    UUID.randomUUID()).statusCode()).isEqualTo(201);
        }

        JsonNode latest = json.readTree(get(path + "?size=2", member).body());
        assertThat(latest.path("items").size()).isEqualTo(2);
        assertThat(latest.path("items").get(0).path("bodyText").asText()).isEqualTo("第二条");
        assertThat(latest.path("items").get(1).path("bodyText").asText()).isEqualTo("第三条");
        String cursor = latest.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        assertThat(get(path + "?cursor=not-base64&size=2", member).statusCode()).isEqualTo(422);

        assertThat(mutate("POST", path, member, updateBody("<p>并发新增</p>"), null,
                UUID.randomUUID()).statusCode()).isEqualTo(201);
        JsonNode older = json.readTree(get(path + "?cursor="
                + URLEncoder.encode(cursor, StandardCharsets.UTF_8) + "&size=2", member).body());
        assertThat(older.path("items").size()).isEqualTo(1);
        assertThat(older.path("items").get(0).path("bodyText").asText()).isEqualTo("第一条");
        assertThat(older.path("nextCursor").isNull()).isTrue();

        jdbc.sql("UPDATE yumpoo.content SET status='ARCHIVED', archived_at=transaction_timestamp(), "
                        + "archived_by_user_id=:actor, updated_at=transaction_timestamp(), "
                        + "updated_by_user_id=:actor, row_version=row_version+1 WHERE id=:id")
                .param("actor", owner.userId()).param("id", contentId).update();
        assertThat(get(path, admin).statusCode()).isEqualTo(200);
        assertThat(get(path, member).statusCode()).isEqualTo(200);
        assertThat(mutate("POST", path, member, updateBody("<p>归档后拒绝</p>"), null,
                UUID.randomUUID()).statusCode()).isEqualTo(409);
    }

    @Test
    void workItemUpdateEditUsesStrongEtagReplacesMentionsAndKeepsParentStable() throws Exception {
        JsonNode created = createWorkItem("/api/v1/contents/" + contentId + "/work-items",
                "编辑讨论", "MEDIUM", member.userId(), null);
        UUID workItemId = UUID.fromString(created.path("id").asText());
        String collection = "/api/v1/work-items/" + workItemId + "/updates";
        String withMention = "<p>初稿 <span data-type=\"mention\" data-mention-user-id=\""
                + owner.userId() + "\">@伪造</span></p>";
        JsonNode published = json.readTree(mutate("POST", collection, member,
                updateBody(withMention), null, UUID.randomUUID()).body());
        UUID updateId = UUID.fromString(published.path("id").asText());
        String resource = "/api/v1/work-item-updates/" + updateId;
        JsonNode parentBefore = json.readTree(get("/api/v1/work-items/" + workItemId, member).body());

        JsonNode ownerView = json.readTree(get(resource, owner).body());
        assertThat(ownerView.path("capabilities").path("canModerateDelete").asBoolean()).isTrue();
        assertThat(ownerView.path("capabilities").path("canEdit").asBoolean()).isFalse();
        assertThat(mutate("PATCH", resource, member, updateBody("<p>缺少版本</p>"), null, null)
                .statusCode()).isEqualTo(428);

        HttpResponse<String> editedResponse = mutate("PATCH", resource, member,
                updateBody("<p onclick=\"bad()\">更新正文</p>"), "\"0\"", null);
        assertThat(editedResponse.statusCode()).as(editedResponse.body()).isEqualTo(200);
        JsonNode edited = json.readTree(editedResponse.body());
        assertThat(edited.path("status").asText()).isEqualTo("EDITED");
        assertThat(edited.path("rowVersion").asLong()).isOne();
        assertThat(edited.path("bodyHtml").asText()).isEqualTo("<p>更新正文</p>");
        assertThat(edited.path("capabilities").path("canEdit").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_update_mention WHERE update_id=:id")
                .param("id", updateId).query(Long.class).single()).isZero();
        JsonNode parentAfter = json.readTree(get("/api/v1/work-items/" + workItemId, member).body());
        assertThat(parentAfter.path("rowVersion").asLong()).isEqualTo(parentBefore.path("rowVersion").asLong());
        assertThat(parentAfter.path("updatedAt").asText()).isEqualTo(parentBefore.path("updatedAt").asText());

        String event = jdbc.sql("SELECT payload_json::text FROM yumpoo.outbox_event "
                        + "WHERE aggregate_id=:id AND event_type='workitem.work_item_update_edited'")
                .param("id", updateId).query(String.class).single();
        assertThat(event).contains("removedMentionedUserIds", owner.userId().toString())
                .doesNotContain("bodyHtml", "bodyText\"", "更新正文");
        assertThat(mutate("PATCH", resource, owner, updateBody("<p>Owner 不能改写</p>"),
                "\"1\"", null).statusCode()).isEqualTo(403);
        assertThat(mutate("PATCH", resource, member, updateBody("<p>旧版本</p>"),
                "\"0\"", null).statusCode()).isEqualTo(412);

        JsonNode expiring = json.readTree(mutate("POST", collection, member,
                updateBody("<p>即将超窗</p>"), null, UUID.randomUUID()).body());
        UUID expiringId = UUID.fromString(expiring.path("id").asText());
        jdbc.sql("UPDATE yumpoo.work_item_update SET "
                        + "created_at=transaction_timestamp()-interval '15 minutes', "
                        + "edit_deadline_at=transaction_timestamp() WHERE id=:id")
                .param("id", expiringId).update();
        assertThat(mutate("PATCH", "/api/v1/work-item-updates/" + expiringId, member,
                updateBody("<p>截止时刻</p>"), "\"0\"", null).statusCode()).isEqualTo(409);
    }

    @Test
    void workItemUpdateSelfDeleteAndArchivedOwnerModerationKeepTombstonesAndAudit() throws Exception {
        JsonNode created = createWorkItem("/api/v1/contents/" + contentId + "/work-items",
                "删除讨论", "LOW", null, null);
        UUID workItemId = UUID.fromString(created.path("id").asText());
        String collection = "/api/v1/work-items/" + workItemId + "/updates";
        String mention = "<p>自删 <span data-type=\"mention\" data-mention-user-id=\""
                + owner.userId() + "\">@Owner</span></p>";
        JsonNode selfPublished = json.readTree(mutate("POST", collection, member,
                updateBody(mention), null, UUID.randomUUID()).body());
        UUID selfId = UUID.fromString(selfPublished.path("id").asText());
        String selfResource = "/api/v1/work-item-updates/" + selfId;

        HttpResponse<String> selfDeletedResponse = mutate("DELETE", selfResource, member,
                "{}", "\"0\"", null);
        assertThat(selfDeletedResponse.statusCode()).as(selfDeletedResponse.body()).isEqualTo(200);
        JsonNode selfDeleted = json.readTree(selfDeletedResponse.body());
        assertThat(selfDeleted.path("status").asText()).isEqualTo("DELETED");
        assertThat(selfDeleted.path("bodyHtml").isNull()).isTrue();
        assertThat(selfDeleted.path("deleteReason").isNull()).isTrue();
        assertThat(selfDeleted.path("capabilities").path("canEdit").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_update_mention WHERE update_id=:id")
                .param("id", selfId).query(Long.class).single()).isOne();
        assertThat(json.readTree(get(collection, owner).body()).path("items").toString())
                .contains(selfId.toString(), "DELETED").doesNotContain("自删");
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.security_audit_event "
                        + "WHERE target_id=:id AND action='WORK_ITEM_UPDATE_SELF_DELETED'")
                .param("id", selfId.toString()).query(Long.class).single()).isOne();
        String selfEvent = jdbc.sql("SELECT payload_json::text FROM yumpoo.outbox_event "
                        + "WHERE aggregate_id=:id AND event_type='workitem.work_item_update_deleted'")
                .param("id", selfId).query(String.class).single();
        assertThat(selfEvent).contains("\"deletionMode\": \"SELF\"")
                .doesNotContain("bodyHtml", "bodyText", "自删");
        assertThat(mutate("DELETE", selfResource, member, "{}", "\"0\"", null).statusCode())
                .isEqualTo(412);
        assertThat(mutate("DELETE", selfResource, member, "{}", "\"1\"", null).statusCode())
                .isEqualTo(409);

        JsonNode moderatedPublished = json.readTree(mutate("POST", collection, member,
                updateBody("<p>需要治理</p>"), null, UUID.randomUUID()).body());
        JsonNode archivedPublished = json.readTree(mutate("POST", collection, member,
                updateBody("<p>归档治理</p>"), null, UUID.randomUUID()).body());
        UUID moderatedId = UUID.fromString(moderatedPublished.path("id").asText());
        UUID archivedId = UUID.fromString(archivedPublished.path("id").asText());
        String moderatedResource = "/api/v1/work-item-updates/" + moderatedId;
        assertThat(mutate("DELETE", moderatedResource, member, "{\"reason\":\"越权治理\"}",
                "\"0\"", null).statusCode()).isEqualTo(403);
        assertThat(mutate("DELETE", moderatedResource, admin, "{\"reason\":\"管理员治理\"}",
                "\"0\"", null).statusCode()).isEqualTo(403);

        jdbc.sql("UPDATE yumpoo.project SET lifecycle='ARCHIVED', archived_at=transaction_timestamp(), "
                        + "updated_at=transaction_timestamp(), updated_by_user_id=:actor, "
                        + "row_version=row_version+1 WHERE id=:id")
                .param("actor", owner.userId()).param("id", PROJECT_ID).update();
        assertThat(mutate("DELETE", moderatedResource, member, "{}", "\"0\"", null).statusCode())
                .isEqualTo(409);
        HttpResponse<String> moderatedResponse = mutate("DELETE",
                "/api/v1/work-item-updates/" + archivedId, owner,
                "{\"reason\":\"  归档后仍需清理  \"}", "\"0\"", null);
        assertThat(moderatedResponse.statusCode()).as(moderatedResponse.body()).isEqualTo(200);
        JsonNode moderated = json.readTree(moderatedResponse.body());
        assertThat(moderated.path("deleteReason").asText()).isEqualTo("归档后仍需清理");
        assertThat(moderated.path("bodyHtml").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT reason_reference FROM yumpoo.security_audit_event "
                        + "WHERE target_id=:id AND action='WORK_ITEM_UPDATE_MODERATED'")
                .param("id", archivedId.toString()).query(String.class).single())
                .isEqualTo("归档后仍需清理");
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.security_audit_event "
                        + "WHERE action='WORK_ITEM_UPDATE_MODERATION_FAILED' "
                        + "AND target_id=:id AND outcome='FAILED'")
                .param("id", moderatedId.toString()).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void workItemUpdateConcurrentMutationHasOneWinnerAndAuditOutboxFailuresRollbackEverything()
            throws Exception {
        JsonNode created = createWorkItem("/api/v1/contents/" + contentId + "/work-items",
                "讨论并发与事务", "HIGH", member.userId(), null);
        UUID workItemId = UUID.fromString(created.path("id").asText());
        String collection = "/api/v1/work-items/" + workItemId + "/updates";

        JsonNode concurrent = json.readTree(mutate("POST", collection, member,
                updateBody("<p>竞争写</p>"), null, UUID.randomUUID()).body());
        UUID concurrentId = UUID.fromString(concurrent.path("id").asText());
        String concurrentResource = "/api/v1/work-item-updates/" + concurrentId;
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> edit = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mutate("PATCH", concurrentResource, member,
                        updateBody("<p>竞争编辑</p>"), "\"0\"", null);
            });
            Future<HttpResponse<String>> delete = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mutate("DELETE", concurrentResource, member, "{}", "\"0\"", null);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(edit.get(10, TimeUnit.SECONDS).statusCode(),
                            delete.get(10, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 412);
        }
        assertThat(jdbc.sql("SELECT row_version FROM yumpoo.work_item_update WHERE id=:id")
                .param("id", concurrentId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE aggregate_id=:id "
                        + "AND event_type IN ('workitem.work_item_update_edited',"
                        + "'workitem.work_item_update_deleted')")
                .param("id", concurrentId).query(Long.class).single()).isOne();

        String mention = "<p>事务回滚 <span data-type=\"mention\" data-mention-user-id=\""
                + owner.userId() + "\">@Owner</span></p>";
        JsonNode outboxProbe = json.readTree(mutate("POST", collection, member,
                updateBody(mention), null, UUID.randomUUID()).body());
        UUID outboxProbeId = UUID.fromString(outboxProbe.path("id").asText());
        jdbc.sql("CREATE OR REPLACE FUNCTION yumpoo.m217_fail_outbox() RETURNS trigger "
                + "LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''m2-17 outbox failure''; END'").update();
        jdbc.sql("CREATE TRIGGER m217_fail_outbox BEFORE INSERT ON yumpoo.outbox_event "
                + "FOR EACH ROW WHEN (NEW.event_type = 'workitem.work_item_update_edited') "
                + "EXECUTE FUNCTION yumpoo.m217_fail_outbox()").update();
        try {
            assertThat(mutate("PATCH", "/api/v1/work-item-updates/" + outboxProbeId, member,
                    updateBody("<p>不应落库</p>"), "\"0\"", null).statusCode()).isEqualTo(500);
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS m217_fail_outbox ON yumpoo.outbox_event").update();
            jdbc.sql("DROP FUNCTION IF EXISTS yumpoo.m217_fail_outbox()").update();
        }
        assertThat(jdbc.sql("SELECT body_text || ':' || status || ':' || row_version "
                        + "FROM yumpoo.work_item_update WHERE id=:id")
                .param("id", outboxProbeId).query(String.class).single())
                .isEqualTo("事务回滚 @M2-10 Owner:PUBLISHED:0");
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_update_mention WHERE update_id=:id")
                .param("id", outboxProbeId).query(Long.class).single()).isOne();

        JsonNode auditProbe = json.readTree(mutate("POST", collection, member,
                updateBody(mention), null, UUID.randomUUID()).body());
        UUID auditProbeId = UUID.fromString(auditProbe.path("id").asText());
        jdbc.sql("CREATE OR REPLACE FUNCTION yumpoo.m217_fail_audit() RETURNS trigger "
                + "LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''m2-17 audit failure''; END'").update();
        jdbc.sql("CREATE TRIGGER m217_fail_audit BEFORE INSERT ON yumpoo.security_audit_event "
                + "FOR EACH ROW WHEN (NEW.action = 'WORK_ITEM_UPDATE_SELF_DELETED') "
                + "EXECUTE FUNCTION yumpoo.m217_fail_audit()").update();
        try {
            assertThat(mutate("DELETE", "/api/v1/work-item-updates/" + auditProbeId, member,
                    "{}", "\"0\"", null).statusCode()).isEqualTo(500);
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS m217_fail_audit ON yumpoo.security_audit_event").update();
            jdbc.sql("DROP FUNCTION IF EXISTS yumpoo.m217_fail_audit()").update();
        }
        assertThat(jdbc.sql("SELECT body_text || ':' || status || ':' || row_version "
                        + "FROM yumpoo.work_item_update WHERE id=:id")
                .param("id", auditProbeId).query(String.class).single())
                .isEqualTo("事务回滚 @M2-10 Owner:PUBLISHED:0");
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_update_mention WHERE update_id=:id")
                .param("id", auditProbeId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE aggregate_id=:id "
                        + "AND event_type='workitem.work_item_update_deleted'")
                .param("id", auditProbeId).query(Long.class).single()).isZero();
    }

    private ActorFixture actor(UUID userId) {
        return new ActorFixture(userId, sessions.issueWebSession(userId, "m210-http"));
    }

    private JsonNode createWorkItem(String collection, String title, String priority,
            UUID assigneeUserId, String dueDate) throws Exception {
        HttpResponse<String> response = mutate("POST", collection, member,
                workItemBody(title, priority, assigneeUserId, null, null,
                        null, null, dueDate), null, UUID.randomUUID());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readTree(response.body());
    }

    private String updateBody(String bodyHtml) throws Exception {
        return json.writeValueAsString(java.util.Map.of("bodyHtml", bodyHtml));
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private static List<String> titles(JsonNode page) {
        List<String> values = new java.util.ArrayList<>();
        page.path("items").forEach(item -> values.add(item.path("title").asText()));
        return List.copyOf(values);
    }

    private static List<String> ids(JsonNode page) {
        List<String> values = new java.util.ArrayList<>();
        page.path("items").forEach(item -> values.add(item.path("id").asText()));
        return List.copyOf(values);
    }

    private HttpResponse<String> get(String path, ActorFixture actor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (actor != null) request.header("Cookie", cookies(actor));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> mutate(String method, String path, ActorFixture actor, String body,
            String ifMatch, UUID idempotencyKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Cookie", cookies(actor))
                .header(CSRF_HEADER, actor.session().csrfCredential().value());
        if (ifMatch != null) request.header("If-Match", ifMatch);
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey.toString());
        if (!body.isEmpty()) request.header("Content-Type", "application/json");
        HttpRequest.BodyPublisher publisher = body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        return client.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private long workItemEventCount() {
        return jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event "
                        + "WHERE event_type LIKE 'workitem.work_item_%'")
                .query(Long.class).single();
    }

    private long workItemEventCount(UUID workItemId, String eventType) {
        return jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event "
                        + "WHERE aggregate_id=:id AND event_type=:eventType")
                .param("id", workItemId).param("eventType", eventType)
                .query(Long.class).single();
    }

    private String workItemRank(UUID workItemId) {
        return jdbc.sql("SELECT rank FROM yumpoo.work_item WHERE id=:id")
                .param("id", workItemId).query(String.class).single();
    }

    private long statusEventCount(UUID workItemId) {
        return jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event "
                        + "WHERE aggregate_id=:id AND event_type='workitem.work_item_status_changed'")
                .param("id", workItemId).query(Long.class).single();
    }

    private long rankEventCount(UUID workItemId) {
        return jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event "
                        + "WHERE aggregate_id=:id AND event_type='workitem.work_item_rank_changed'")
                .param("id", workItemId).query(Long.class).single();
    }

    private static List<String> transitionTargets(JsonNode detail) {
        List<String> targets = new java.util.ArrayList<>();
        detail.path("capabilities").path("availableTransitions")
                .forEach(node -> targets.add(node.path("toStatus").asText()));
        return List.copyOf(targets);
    }

    private JsonNode createContent(String code, String name) throws Exception {
        return createContent(PROJECT_ID, code, name);
    }

    private JsonNode createContent(UUID projectId, String code, String name) throws Exception {
        var body = json.createObjectNode();
        body.put("code", code); body.put("name", name); body.putNull("description");
        body.put("blueprintCode", "TASKS");
        HttpResponse<String> response = mutate("POST", "/api/v1/projects/" + projectId + "/contents",
                owner, json.writeValueAsString(body), null, UUID.randomUUID());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readTree(response.body());
    }

    private String workItemBody(String title, String priority, String description, String notes) throws Exception {
        return workItemBody(title, priority, null, description, notes, null, null, null);
    }

    private String workItemBody(String title, String priority, UUID assigneeUserId,
            String description, String notes, String timelineStartDate,
            String timelineEndDate, String dueDate) throws Exception {
        var body = json.createObjectNode();
        body.put("title", title); body.put("priority", priority);
        if (assigneeUserId == null) body.putNull("assigneeUserId");
        else body.put("assigneeUserId", assigneeUserId.toString());
        if (description == null) body.putNull("description"); else body.put("description", description);
        if (notes == null) body.putNull("notes"); else body.put("notes", notes);
        if (timelineStartDate == null) body.putNull("timelineStartDate");
        else body.put("timelineStartDate", timelineStartDate);
        if (timelineEndDate == null) body.putNull("timelineEndDate");
        else body.put("timelineEndDate", timelineEndDate);
        if (dueDate == null) body.putNull("dueDate"); else body.put("dueDate", dueDate);
        return json.writeValueAsString(body);
    }

    private String subitemBody(UUID targetContentId, String title, String priority) throws Exception {
        var body = json.createObjectNode();
        body.put("contentId", targetContentId.toString());
        body.put("title", title);
        if (priority == null) body.putNull("priority"); else body.put("priority", priority);
        return json.writeValueAsString(body);
    }

    private static String relationBody(String relationType, String currentRole, UUID targetId) {
        return "{\"relationType\":\"" + relationType + "\",\"currentRole\":\""
                + currentRole + "\",\"targetWorkItemId\":\"" + targetId + "\"}";
    }

    private static String relationBody(String relationType, String currentRole,
            UUID targetProjectId, UUID targetId) {
        return "{\"relationType\":\"" + relationType + "\",\"currentRole\":\""
                + currentRole + "\",\"targetProjectId\":\"" + targetProjectId
                + "\",\"targetWorkItemId\":\"" + targetId + "\"}";
    }

    private void setMembership(UUID projectId, UUID userId, boolean active) {
        jdbc.sql("UPDATE yumpoo.project_membership SET status=:status, row_version=row_version+1, "
                        + "removed_at=CASE WHEN :active THEN NULL ELSE transaction_timestamp() END, "
                        + "removed_by_user_id=CASE WHEN :active THEN NULL ELSE :actorId END, "
                        + "remove_reason=CASE WHEN :active THEN NULL ELSE 'M2-22 visibility probe' END "
                        + "WHERE company_id=:companyId AND project_id=:projectId AND user_id=:userId")
                .param("status", active ? "ACTIVE" : "REMOVED").param("active", active)
                .param("actorId", owner.userId()).param("companyId", COMPANY_ID)
                .param("projectId", projectId).param("userId", userId).update();
    }

    private void archiveProject(UUID projectId, boolean archived) {
        jdbc.sql("UPDATE yumpoo.project SET lifecycle=:lifecycle, row_version=row_version+1, "
                        + "archived_at=CASE WHEN :archived THEN transaction_timestamp() ELSE NULL END, "
                        + "updated_at=transaction_timestamp(), updated_by_user_id=:actorId "
                        + "WHERE company_id=:companyId AND id=:projectId")
                .param("lifecycle", archived ? "ARCHIVED" : "ACTIVE")
                .param("archived", archived).param("actorId", owner.userId())
                .param("companyId", COMPANY_ID).param("projectId", projectId).update();
    }

    private String transitionBody(String toStatus, String resolution) throws Exception {
        var body = json.createObjectNode();
        body.put("toStatus", toStatus);
        if (resolution != null) body.put("resolution", resolution);
        return json.writeValueAsString(body);
    }

    private String rankMoveBody(String toStatus, String placement, UUID anchorWorkItemId,
            String resolution) throws Exception {
        var body = json.createObjectNode();
        body.put("toStatus", toStatus);
        body.put("placement", placement);
        if (anchorWorkItemId == null) body.putNull("anchorWorkItemId");
        else body.put("anchorWorkItemId", anchorWorkItemId.toString());
        if (resolution == null) body.putNull("resolution");
        else body.put("resolution", resolution);
        return json.writeValueAsString(body);
    }

    private void createWorkspace() {
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.workspace WHERE id=:id AND code='MAIN'")
                .param("id", WORKSPACE_ID).query(Integer.class).single()).isOne();
    }

    private void createProject(UUID ownerId, UUID memberId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("""
                INSERT INTO yumpoo.project (id, company_id, workspace_id, project_code, name,
                    project_type, lifecycle, owner_user_id, template_key, template_version,
                    row_version, created_at, created_by_user_id, updated_at, updated_by_user_id, activated_at)
                VALUES (:id, :companyId, :workspaceId, 'M2_10_ACTIVE', 'M2-10 Active',
                    'PRODUCT_DEVELOPMENT', 'ACTIVE', :ownerId, 'RND', 1, 0,
                    transaction_timestamp(), :ownerId, transaction_timestamp(), :ownerId,
                    transaction_timestamp())
                """).param("id", PROJECT_ID).param("companyId", COMPANY_ID)
                    .param("workspaceId", WORKSPACE_ID).param("ownerId", ownerId).update();
            jdbc.sql("""
                INSERT INTO yumpoo.project_membership (id, company_id, project_id, user_id,
                    status, joined_at, joined_by_user_id, row_version)
                VALUES (:ownerMembership, :companyId, :projectId, :ownerId, 'ACTIVE',
                    transaction_timestamp(), :ownerId, 0),
                    (:memberMembership, :companyId, :projectId, :memberId, 'ACTIVE',
                    transaction_timestamp(), :ownerId, 0)
                """).param("ownerMembership", UUID.randomUUID()).param("memberMembership", UUID.randomUUID())
                    .param("companyId", COMPANY_ID).param("projectId", PROJECT_ID)
                    .param("ownerId", ownerId).param("memberId", memberId).update();
            labels.initialize(COMPANY_ID, PROJECT_ID, "RND", 1, clock.instant());
        });
    }

    private UUID createProjectFixture(String code, String projectType, String templateKey) {
        UUID projectId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("""
                INSERT INTO yumpoo.project (id, company_id, workspace_id, project_code, name,
                    project_type, lifecycle, owner_user_id, template_key, template_version,
                    row_version, created_at, created_by_user_id, updated_at, updated_by_user_id, activated_at)
                VALUES (:id, :companyId, :workspaceId, :code, :name,
                    :projectType, 'ACTIVE', :ownerId, :templateKey, 1, 0,
                    transaction_timestamp(), :ownerId, transaction_timestamp(), :ownerId,
                    transaction_timestamp())
                """).param("id", projectId).param("companyId", COMPANY_ID)
                    .param("workspaceId", WORKSPACE_ID).param("code", code).param("name", code)
                    .param("projectType", projectType).param("templateKey", templateKey)
                    .param("ownerId", owner.userId()).update();
            jdbc.sql("""
                INSERT INTO yumpoo.project_membership (id, company_id, project_id, user_id,
                    status, joined_at, joined_by_user_id, row_version)
                VALUES (:ownerMembership, :companyId, :projectId, :ownerId, 'ACTIVE',
                    transaction_timestamp(), :ownerId, 0),
                    (:memberMembership, :companyId, :projectId, :memberId, 'ACTIVE',
                    transaction_timestamp(), :ownerId, 0)
                """).param("ownerMembership", UUID.randomUUID())
                    .param("memberMembership", UUID.randomUUID()).param("companyId", COMPANY_ID)
                    .param("projectId", projectId).param("ownerId", owner.userId())
                    .param("memberId", member.userId()).update();
            labels.initialize(COMPANY_ID, projectId, templateKey, 1, clock.instant());
        });
        return projectId;
    }

    private static String cookies(ActorFixture actor) {
        return SESSION_COOKIE + "=" + actor.session().sessionCredential().value()
                + "; " + CSRF_COOKIE + "=" + actor.session().csrfCredential().value();
    }

    private void cleanUp() {
        jdbc.sql("DELETE FROM yumpoo.work_item_update_mention WHERE company_id=:id")
                .param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item_update WHERE company_id=:id")
                .param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item_relation WHERE company_id=:id")
                .param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item_project_counter WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.content WHERE company_id=:id").param("id", COMPANY_ID).update();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM yumpoo.project_membership WHERE company_id=:id").param("id", COMPANY_ID).update();
            jdbc.sql("DELETE FROM yumpoo.project WHERE company_id=:id").param("id", COMPANY_ID).update();
        });
        jdbc.sql("DELETE FROM yumpoo.outbox_consumer_receipt").update();
        jdbc.sql("DELETE FROM yumpoo.security_audit_event WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("""
                DELETE FROM yumpoo.idempotency_record WHERE actor_user_id IN
                    (SELECT id FROM yumpoo.identity_user WHERE company_id=:id)
                """).param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.login_session WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.outbox_event WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.admin_override WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.platform_role_assignment WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.external_identity WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.identity_user WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("""
                UPDATE yumpoo.app_manager_governance_state
                   SET lifecycle_status='UNINITIALIZED', initialized_at=NULL, missing_since=NULL,
                       event_version=0, row_version=0, updated_at=transaction_timestamp()
                 WHERE company_id=:id
                """).param("id", COMPANY_ID).update();
    }

    private record TemplateCase(String code, String projectType, String templateKey,
            String initialStatus, String nextStatus, String nextCategory) {}

    private record ActorFixture(UUID userId, IssuedSession session) {}
}
