package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningResult;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureProvisioner;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    @LocalServerPort private int port;
    @Autowired private IdentityAcceptanceFixtureProvisioner provisioner;
    @Autowired private SessionService sessions;
    @Autowired private JdbcClient jdbc;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper json;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private WorkItemLabelRepository labels;

    private ActorFixture owner;
    private ActorFixture member;
    private UUID requirementsId;
    private UUID tasksId;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("work-item-category-http-" + UUID.randomUUID()))) {
            DirectoryMemberProvisioningResult ownerUser = provisioner.provision("work-category-owner", "Work Category Owner");
            DirectoryMemberProvisioningResult memberUser = provisioner.provision("work-category-member", "Work Category Member");
            owner = actor(ownerUser.userId());
            member = actor(memberUser.userId());
            createProject(owner.userId(), member.userId());
            requirementsId = createContent("需求", "BRIGHT_BLUE");
            tasksId = createContent("任务", "BRIGHT_GREEN");
        }
    }

    @AfterEach
    void tearDown() { cleanUp(); }

    @Test
    void categoryIsRequiredAndProjectListCanSortByCategoryWithout422() throws Exception {
        String collection = "/api/v1/projects/" + PROJECT_ID + "/work-items";
        assertThat(mutate("POST", collection, member, workItemBody(null, "缺少类别"), null,
                UUID.randomUUID()).statusCode()).isEqualTo(422);

        JsonNode task = created(mutate("POST", collection, member,
                workItemBody(tasksId, "任务项"), null, UUID.randomUUID()));
        JsonNode requirement = created(mutate("POST", collection, member,
                workItemBody(requirementsId, "需求项"), null, UUID.randomUUID()));
        assertThat(task.has("type")).isFalse();
        assertThat(task.path("contentName").asText()).isEqualTo("任务");
        assertThat(task.path("contentColorToken").asText()).isEqualTo("BRIGHT_GREEN");

        HttpResponse<String> sortedResponse = get(collection + "?view=TABLE&sort=CONTENT,ASC&limit=20", member);
        assertThat(sortedResponse.statusCode()).as(sortedResponse.body()).isEqualTo(200);
        JsonNode sorted = json.readTree(sortedResponse.body());
        assertThat(sorted.path("items").get(0).path("id").asText()).isEqualTo(requirement.path("id").asText());
        assertThat(sorted.path("items").get(1).path("id").asText()).isEqualTo(task.path("id").asText());
        assertThat(get("/api/v1/contents/" + tasksId + "/work-items", member).statusCode()).isEqualTo(404);
    }

    @Test
    void switchingCategoryPreservesIdentityHierarchyDiscussionAndProjectRanks() throws Exception {
        String collection = "/api/v1/projects/" + PROJECT_ID + "/work-items";
        JsonNode parent = created(mutate("POST", collection, member,
                workItemBody(requirementsId, "父项"), null, UUID.randomUUID()));
        JsonNode child = created(mutate("POST", "/api/v1/work-items/" + parent.path("id").asText() + "/subitems",
                member, subitemBody(requirementsId, "子项"), null, UUID.randomUUID()));
        UUID childId = UUID.fromString(child.path("id").asText());
        String rankBefore = jdbc.sql("SELECT rank FROM yumpoo.work_item WHERE id=:id")
                .param("id", childId).query(String.class).single();
        String projectSortBefore = jdbc.sql("SELECT project_sort_key FROM yumpoo.work_item WHERE id=:id")
                .param("id", childId).query(String.class).single();

        JsonNode update = json.readTree(mutate("POST", "/api/v1/work-items/" + childId + "/updates",
                member, json.writeValueAsString(java.util.Map.of("bodyHtml", "<p>切换前讨论</p>")),
                null, UUID.randomUUID()).body());

        UUID key = UUID.randomUUID();
        HttpResponse<String> switchedResponse = mutate("PATCH", "/api/v1/work-items/" + childId + "/content",
                member, json.writeValueAsString(java.util.Map.of("contentId", tasksId)),
                child.path("etag").asText(), key);
        assertThat(switchedResponse.statusCode()).as(switchedResponse.body()).isEqualTo(200);
        JsonNode switched = json.readTree(switchedResponse.body());
        assertThat(switched.path("itemNo").asText()).isEqualTo(child.path("itemNo").asText());
        assertThat(switched.path("contentId").asText()).isEqualTo(tasksId.toString());
        assertThat(switched.path("contentName").asText()).isEqualTo("任务");
        assertThat(jdbc.sql("SELECT rank FROM yumpoo.work_item WHERE id=:id").param("id", childId)
                .query(String.class).single()).isEqualTo(rankBefore);
        assertThat(jdbc.sql("SELECT project_sort_key FROM yumpoo.work_item WHERE id=:id").param("id", childId)
                .query(String.class).single()).isEqualTo(projectSortBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_relation WHERE right_work_item_id=:id AND deleted_at IS NULL")
                .param("id", childId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_update WHERE id=:id AND work_item_id=:workItemId")
                .param("id", UUID.fromString(update.path("id").asText())).param("workItemId", childId)
                .query(Long.class).single()).isOne();

        HttpResponse<String> replay = mutate("PATCH", "/api/v1/work-items/" + childId + "/content",
                member, json.writeValueAsString(java.util.Map.of("contentId", tasksId)),
                child.path("etag").asText(), key);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(switchedResponse.body());
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE aggregate_id=:id "
                        + "AND event_type='workitem.work_item_fields_changed' AND event_version=2")
                .param("id", childId).query(Long.class).single()).isOne();

        HttpResponse<String> unchanged = mutate("PATCH", "/api/v1/work-items/" + childId + "/content",
                member, json.writeValueAsString(java.util.Map.of("contentId", tasksId)),
                switched.path("etag").asText(), UUID.randomUUID());
        assertThat(unchanged.statusCode()).as(unchanged.body()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE aggregate_id=:id "
                        + "AND event_type='workitem.work_item_fields_changed' AND event_version=2")
                .param("id", childId).query(Long.class).single()).isOne();
    }

    @Test
    void inactiveCategoryRemainsEditableOnExistingItemsButCannotBeSelected() throws Exception {
        JsonNode item = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(requirementsId, "已有项"), null, UUID.randomUUID()));
        String catalogPath = "/api/v1/projects/" + PROJECT_ID + "/contents";
        JsonNode catalog = json.readTree(get(catalogPath, owner).body());
        JsonNode actual = null;
        for (JsonNode candidate : catalog.path("items")) {
            if (requirementsId.toString().equals(candidate.path("id").asText())) {
                actual = candidate;
            }
        }
        assertThat(actual).isNotNull();
        assertThat(mutate("PATCH", catalogPath + "/" + requirementsId, owner,
                json.writeValueAsString(java.util.Map.of("name", actual.path("name").asText(),
                        "colorToken", actual.path("colorToken").asText(), "active", false,
                        "sortOrder", actual.path("sortOrder").asInt())), catalog.path("etag").asText(), null)
                .statusCode()).isEqualTo(200);

        HttpResponse<String> priority = mutate("PATCH", "/api/v1/work-items/" + item.path("id").asText() + "/priority",
                member, "{\"priority\":null}", item.path("etag").asText(), UUID.randomUUID());
        assertThat(priority.statusCode()).as(priority.body()).isEqualTo(200);

        JsonNode taskItem = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "待切换"), null, UUID.randomUUID()));
        assertThat(mutate("PATCH", "/api/v1/work-items/" + taskItem.path("id").asText() + "/content", member,
                json.writeValueAsString(java.util.Map.of("contentId", requirementsId)),
                taskItem.path("etag").asText(), UUID.randomUUID()).statusCode()).isEqualTo(409);
    }

    @Test
    void deadlineTimePreservesOmissionDistinguishesNullAndEmitsOneLogicalChange() throws Exception {
        String collection = "/api/v1/projects/" + PROJECT_ID + "/work-items";
        var body = (tools.jackson.databind.node.ObjectNode) json.readTree(workItemBody(tasksId, "截止时间"));
        body.put("dueDate", "2026-09-08").put("dueTime", "18:05");
        JsonNode item = created(mutate("POST", collection, member, json.writeValueAsString(body), null, UUID.randomUUID()));
        UUID id = UUID.fromString(item.path("id").asText());
        String path = "/api/v1/work-items/" + id;
        assertThat(item.path("dueTime").asText()).isEqualTo("18:05");
        assertThat(item.path("completedAt").isNull()).isTrue();
        JsonNode listed = json.readTree(get(collection + "?view=TABLE", member).body()).path("items").get(0);
        assertThat(listed.path("dueTime").asText()).isEqualTo("18:05");

        body.remove("dueTime");
        body.remove("contentId");
        body.put("title", "普通编辑保留时间");
        item = ok(mutate("PATCH", path, member, json.writeValueAsString(body), item.path("etag").asText(), UUID.randomUUID()));
        assertThat(item.path("dueTime").asText()).isEqualTo("18:05");
        item = ok(mutate("PATCH", path + "/due-date", member, "{\"dueDate\":\"2026-09-09\"}",
                item.path("etag").asText(), UUID.randomUUID()));
        assertThat(item.path("dueTime").asText()).isEqualTo("18:05");

        String etag = item.path("etag").asText();
        UUID key = UUID.randomUUID();
        String removeTime = "{\"dueDate\":\"2026-09-09\",\"dueTime\":null}";
        HttpResponse<String> removed = mutate("PATCH", path + "/due-date", member, removeTime, etag, key);
        item = ok(removed);
        assertThat(item.path("dueTime").isNull()).isTrue();
        JsonNode event = lastFieldsEvent(id);
        assertThat(event.path("changedFields").toString()).isEqualTo("[\"dueDate\"]");
        assertThat(event.path("previousDueTime").asText()).isEqualTo("18:05");
        assertThat(event.path("dueTime").isNull()).isTrue();
        long eventCount = fieldsEventCount(id);
        assertThat(mutate("PATCH", path + "/due-date", member, removeTime, etag, key).body()).isEqualTo(removed.body());
        assertThat(mutate("PATCH", path + "/due-date", member, "{\"dueDate\":\"2026-09-09\"}", etag, key)
                .statusCode()).isEqualTo(409);
        assertThat(mutate("PATCH", path + "/due-date", member, "{\"dueDate\":null}", etag, UUID.randomUUID())
                .statusCode()).isEqualTo(412);
        item = ok(mutate("PATCH", path + "/due-date", member, removeTime, item.path("etag").asText(), UUID.randomUUID()));
        assertThat(fieldsEventCount(id)).isEqualTo(eventCount);

        item = ok(mutate("PATCH", path + "/due-date", member, "{\"dueDate\":\"2026-09-10\",\"dueTime\":\"09:30\"}",
                item.path("etag").asText(), UUID.randomUUID()));
        assertThat(fieldsEventCount(id)).isEqualTo(eventCount + 1);
        assertThat(lastFieldsEvent(id).path("changedFields").toString()).isEqualTo("[\"dueDate\"]");
        item = ok(mutate("PATCH", path + "/due-date", member, "{\"dueDate\":null,\"dueTime\":null}",
                item.path("etag").asText(), UUID.randomUUID()));
        assertThat(item.path("dueDate").isNull()).isTrue();
        assertThat(item.path("dueTime").isNull()).isTrue();
        assertThat(lastFieldsEvent(id).path("previousDueTime").asText()).isEqualTo("09:30");
        assertThat(fieldsEventCount(id)).isEqualTo(eventCount + 2);
        ok(mutate("PATCH", path + "/due-date", member, "{\"dueDate\":null}", item.path("etag").asText(), UUID.randomUUID()));
        assertThat(fieldsEventCount(id)).isEqualTo(eventCount + 2);

        for (String invalid : new String[] { "{\"dueDate\":null,\"dueTime\":\"09:30\"}",
                "{\"dueDate\":\"2026-09-10\",\"dueTime\":\"24:00\"}",
                "{\"dueDate\":\"2026-09-10\",\"dueTime\":\"9:30\"}",
                "{\"dueDate\":\"2026-09-10\",\"dueTime\":\"09:30:00\"}",
                "{\"dueDate\":\"2026-09-10\",\"dueTime\":930}" }) {
            assertThat(mutate("PATCH", path + "/due-date", member, invalid, item.path("etag").asText(), UUID.randomUUID())
                    .statusCode()).as(invalid).isEqualTo(422);
        }
        assertThat(mutate("PATCH", path + "/due-date", member, "{}", item.path("etag").asText(), UUID.randomUUID())
                .statusCode()).isEqualTo(400);
        assertThat(fieldsEventCount(id)).isEqualTo(eventCount + 2);
    }

    @Test
    void completionFactSurvivesEditsAndBothStatusEntrypointsHandleReopening() throws Exception {
        JsonNode parent = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "父项"), null, UUID.randomUUID()));
        String childrenPath = "/api/v1/work-items/" + parent.path("id").asText() + "/subitems";
        var body = (tools.jackson.databind.node.ObjectNode) json.readTree(subitemBody(tasksId, "完成时间"));
        body.put("dueDate", "2026-09-08").put("dueTime", "08:00");
        JsonNode item = created(mutate("POST", childrenPath, member, json.writeValueAsString(body), null, UUID.randomUUID()));
        String path = "/api/v1/work-items/" + item.path("id").asText();
        String todo = item.path("statusCode").asText();
        String done = transitionTo(item, "DONE");
        item = ok(mutate("POST", path + "/transitions", member, "{\"toStatus\":\"" + done + "\"}",
                item.path("etag").asText(), UUID.randomUUID()));
        String completedAt = item.path("completedAt").asText();
        assertThat(item.path("completedAt").isNull()).isFalse();
        body.remove("contentId");
        item = ok(mutate("PATCH", path, member, json.writeValueAsString(body.put("title", "已完成后的编辑")),
                item.path("etag").asText(), UUID.randomUUID()));
        assertThat(item.path("completedAt").asText()).isEqualTo(completedAt);
        assertThat(item.path("dueTime").asText()).isEqualTo("08:00");
        JsonNode child = json.readTree(get(childrenPath, member).body()).path("items").get(0);
        assertThat(child.path("completedAt").asText()).isEqualTo(completedAt);
        assertThat(child.path("dueTime").asText()).isEqualTo("08:00");

        item = ok(mutate("POST", path + "/rank-moves", member,
                "{\"toStatus\":\"" + todo + "\",\"placement\":\"START\"}", item.path("etag").asText(), UUID.randomUUID()));
        assertThat(item.path("completedAt").isNull()).isTrue();
        item = ok(mutate("POST", path + "/rank-moves", member,
                "{\"toStatus\":\"" + done + "\",\"placement\":\"START\"}", item.path("etag").asText(), UUID.randomUUID()));
        assertThat(java.time.Instant.parse(item.path("completedAt").asText())).isAfter(java.time.Instant.parse(completedAt));

        jdbc.sql("UPDATE yumpoo.work_item SET completed_at=NULL WHERE id=:id")
                .param("id", UUID.fromString(item.path("id").asText())).update();
        item = ok(mutate("PATCH", path, member, json.writeValueAsString(body.put("title", "历史完成时间不推算")),
                item.path("etag").asText(), UUID.randomUUID()));
        assertThat(item.path("statusCategory").asText()).isEqualTo("DONE");
        assertThat(item.path("completedAt").isNull()).isTrue();
    }

    private String transitionTo(JsonNode item, String category) {
        for (JsonNode transition : item.path("capabilities").path("availableTransitions")) {
            if (category.equals(transition.path("statusCategory").asText())) return transition.path("toStatus").asText();
        }
        throw new AssertionError("Missing transition to " + category + ": " + item);
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private long fieldsEventCount(UUID id) {
        return jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE aggregate_id=:id AND event_type='workitem.work_item_fields_changed'")
                .param("id", id).query(Long.class).single();
    }

    private JsonNode lastFieldsEvent(UUID id) throws Exception {
        return json.readTree(jdbc.sql("SELECT payload_json::text FROM yumpoo.outbox_event WHERE aggregate_id=:id "
                        + "AND event_type='workitem.work_item_fields_changed' ORDER BY aggregate_version DESC LIMIT 1")
                .param("id", id).query(String.class).single());
    }

    private JsonNode created(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readTree(response.body());
    }

    private UUID createContent(String name, String color) throws Exception {
        HttpResponse<String> response = mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/contents", owner,
                json.writeValueAsString(java.util.Map.of("name", name, "colorToken", color)), null, UUID.randomUUID());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(json.readTree(response.body()).path("id").asText());
    }

    private String workItemBody(UUID contentId, String title) throws Exception {
        var body = json.createObjectNode();
        if (contentId == null) body.putNull("contentId"); else body.put("contentId", contentId.toString());
        body.put("title", title); body.putNull("priority"); body.putNull("assigneeUserId");
        body.putNull("description"); body.putNull("notes"); body.putNull("timelineStartDate");
        body.putNull("timelineEndDate"); body.putNull("dueDate");
        return json.writeValueAsString(body);
    }

    private String subitemBody(UUID contentId, String title) throws Exception { return workItemBody(contentId, title); }
    private ActorFixture actor(UUID userId) { return new ActorFixture(userId, sessions.issueWebSession(userId, "work-item-category-http")); }

    private HttpResponse<String> get(String path, ActorFixture actor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (actor != null) request.header("Cookie", cookies(actor));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> mutate(String method, String path, ActorFixture actor, String body,
            String ifMatch, UUID idempotencyKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Cookie", cookies(actor))
                .header(CSRF_HEADER, actor.session().csrfCredential().value());
        if (ifMatch != null) request.header("If-Match", ifMatch);
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey.toString());
        if (!body.isEmpty()) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private void createProject(UUID ownerId, UUID memberId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("""
                INSERT INTO yumpoo.project (id,company_id,workspace_id,project_code,name,project_type,
                    lifecycle,owner_user_id,template_key,template_version,row_version,created_at,
                    created_by_user_id,updated_at,updated_by_user_id,activated_at)
                VALUES (:id,:companyId,:workspaceId,'CATEGORY_WORK','Category Work','PRODUCT_DEVELOPMENT',
                    'ACTIVE',:ownerId,'RND',1,0,transaction_timestamp(),:ownerId,
                    transaction_timestamp(),:ownerId,transaction_timestamp())
                """).param("id", PROJECT_ID).param("companyId", COMPANY_ID)
                    .param("workspaceId", WORKSPACE_ID).param("ownerId", ownerId).update();
            jdbc.sql("""
                INSERT INTO yumpoo.project_membership (id,company_id,project_id,user_id,status,joined_at,joined_by_user_id,row_version)
                VALUES (:ownerMembership,:companyId,:projectId,:ownerId,'ACTIVE',transaction_timestamp(),:ownerId,0),
                       (:memberMembership,:companyId,:projectId,:memberId,'ACTIVE',transaction_timestamp(),:ownerId,0)
                """).param("ownerMembership", UUID.randomUUID()).param("memberMembership", UUID.randomUUID())
                    .param("companyId", COMPANY_ID).param("projectId", PROJECT_ID)
                    .param("ownerId", ownerId).param("memberId", memberId).update();
            jdbc.sql("INSERT INTO yumpoo.content_catalog_version (project_id,company_id) VALUES (:projectId,:companyId)")
                    .param("projectId", PROJECT_ID).param("companyId", COMPANY_ID).update();
            labels.initialize(COMPANY_ID, PROJECT_ID, "RND", 1, clock.instant());
        });
    }

    private static String cookies(ActorFixture actor) {
        return SESSION_COOKIE + "=" + actor.session().sessionCredential().value()
                + "; " + CSRF_COOKIE + "=" + actor.session().csrfCredential().value();
    }

    private void cleanUp() {
        jdbc.sql("DELETE FROM yumpoo.work_item_update_mention WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item_update WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item_relation WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.work_item_project_counter WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.content WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.content_catalog_version WHERE company_id=:id")
                .param("id", COMPANY_ID).update();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM yumpoo.project_membership WHERE company_id=:id").param("id", COMPANY_ID).update();
            jdbc.sql("DELETE FROM yumpoo.project WHERE company_id=:id").param("id", COMPANY_ID).update();
        });
        jdbc.sql("DELETE FROM yumpoo.security_audit_event WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.idempotency_record WHERE actor_user_id IN (SELECT id FROM yumpoo.identity_user WHERE company_id=:id)").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.login_session WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.outbox_event WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.external_identity WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.identity_user WHERE company_id=:id").param("id", COMPANY_ID).update();
    }

    private record ActorFixture(UUID userId, IssuedSession session) {}
}
