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
        assertThat(first.path("statusCode").asText()).isEqualTo("BACKLOG");
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
        JsonNode grouped = json.readTree(get(collection + "?status=BACKLOG&status=DONE", member).body());
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

        JsonNode combined = body(get(collection + "?status=BACKLOG&priority=LOW&priority=MEDIUM"
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
        assertThat(transitionTargets(createdJson)).containsExactly("READY", "CANCELED");

        long eventsBefore = statusEventCount(workItemId);
        HttpResponse<String> illegal = mutate("POST", transitionPath, member,
                transitionBody("IN_REVIEW", null), "\"0\"", UUID.randomUUID());
        assertThat(illegal.statusCode()).as(illegal.body()).isEqualTo(409);
        assertThat(statusEventCount(workItemId)).isEqualTo(eventsBefore);
        assertThat(json.readTree(get("/api/v1/work-items/" + workItemId, member).body())
                .path("statusCode").asText()).isEqualTo("BACKLOG");

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
        assertThat(transitionTargets(readyJson)).containsExactly("IN_PROGRESS", "CANCELED");

        HttpResponse<String> replay = mutate("POST", transitionPath, member,
                readyBody, "\"0\"", replayKey);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(ready.body());
        assertThat(statusEventCount(workItemId)).isEqualTo(eventsBefore + 1);
        JsonNode event = json.readTree(jdbc.sql("""
                SELECT payload_json::text FROM yumpoo.outbox_event
                 WHERE aggregate_id=:id AND event_type='workitem.work_item_status_changed'
                """).param("id", workItemId).query(String.class).single());
        assertThat(event.path("fromStatus").asText()).isEqualTo("BACKLOG");
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
        assertThat(doneJson.path("capabilities").path("availableTransitions").isEmpty()).isTrue();
        assertThat(mutate("POST", "/api/v1/contents/" + contentId + "/archive",
                owner, "", contentEtag, UUID.randomUUID()).statusCode()).isEqualTo(200);
    }

    @Test
    void allFixedTemplatesExposeAndExecuteTheirInitialTransition() throws Exception {
        List<TemplateCase> cases = List.of(
                new TemplateCase("M212_RND", "PRODUCT_DEVELOPMENT", "RND", "BACKLOG", "READY", "TODO"),
                new TemplateCase("M212_PRE", "PRE_SALES", "PRE_SALES", "TO_ASSESS", "PREPARING", "IN_PROGRESS"),
                new TemplateCase("M212_IMPL", "IMPLEMENTATION", "IMPLEMENTATION", "PLANNED", "IN_PROGRESS", "IN_PROGRESS"),
                new TemplateCase("M212_HYPER", "HYPERCARE", "HYPERCARE", "OPEN", "DIAGNOSING", "IN_PROGRESS")
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
        assertThat(get(collection + "?view=KANBAN&status=BACKLOG&status=READY", member).statusCode())
                .isEqualTo(422);
        assertThat(get(collection + "?view=KANBAN&status=BACKLOG&sort=TITLE,ASC", member).statusCode())
                .isEqualTo(422);
        JsonNode firstPage = json.readTree(get(
                collection + "?view=KANBAN&status=BACKLOG&page=0&size=2", member).body());
        JsonNode secondPage = json.readTree(get(
                collection + "?view=KANBAN&status=BACKLOG&page=1&size=2", member).body());
        assertThat(titles(firstPage)).containsExactly("第三张", "第二张");
        assertThat(titles(secondPage)).containsExactly("第一张");
        assertThat(firstPage.path("items").get(0).path("etag").asText()).isEqualTo("\"0\"");
        assertThat(firstPage.path("items").get(0).path("capabilities")
                .path("canMoveInKanban").asBoolean()).isTrue();

        UUID firstId = UUID.fromString(first.path("id").asText());
        String firstMovePath = "/api/v1/work-items/" + firstId + "/rank-moves";
        UUID key = UUID.randomUUID();
        String startBody = rankMoveBody("BACKLOG", "START", null, null);
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
                collection + "?view=KANBAN&status=BACKLOG", member).body())))
                .containsExactly("第一张", "第三张", "第二张");

        long eventsBeforeNoop = workItemEventCount();
        HttpResponse<String> noop = mutate("POST", firstMovePath, member,
                startBody, "\"1\"", UUID.randomUUID());
        assertThat(noop.statusCode()).as(noop.body()).isEqualTo(200);
        assertThat(json.readTree(noop.body()).path("rowVersion").asLong()).isEqualTo(1);
        assertThat(workItemEventCount()).isEqualTo(eventsBeforeNoop);

        assertThat(mutate("POST", firstMovePath, member,
                rankMoveBody("BACKLOG", "START", UUID.fromString(second.path("id").asText()), null),
                "\"1\"", UUID.randomUUID()).statusCode()).isEqualTo(422);
        assertThat(mutate("POST", firstMovePath, member,
                rankMoveBody("BACKLOG", "BEFORE", UUID.randomUUID(), null),
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
                        rankMoveBody("BACKLOG", "START", null, null),
                        "\"0\"", UUID.randomUUID());
            });
            Future<HttpResponse<String>> bottom = pool.submit(() -> {
                start.await();
                return mutate("POST", path, member,
                        rankMoveBody("BACKLOG", "END", null, null),
                        "\"0\"", UUID.randomUUID());
            });
            start.countDown();
            assertThat(List.of(top.get(20, TimeUnit.SECONDS).statusCode(),
                    bottom.get(20, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 412);
        }
        assertThat(rankEventCount(firstId)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(DISTINCT rank) = count(*) FROM yumpoo.work_item "
                        + "WHERE content_id=:id AND status_code='BACKLOG' AND deleted_at IS NULL")
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
