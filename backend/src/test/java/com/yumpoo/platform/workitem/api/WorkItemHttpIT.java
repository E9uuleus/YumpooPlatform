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
    void discussionFormatsSurvivePublishReadEditAndRejectStaleVersionOrForgedMention() throws Exception {
        JsonNode item = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "富文本讨论验收"), null, UUID.randomUUID()));
        String collection = "/api/v1/work-items/" + item.path("id").asText() + "/updates";
        String fixture = java.nio.file.Files.readString(java.nio.file.Path.of("../contracts/examples/work-items/update-rich-text.json"));
        String body = fixture.replace("35000000-0000-4000-8000-000000000007", owner.userId().toString());
        JsonNode published = created(mutate("POST", collection, member, body, null, UUID.randomUUID()));
        String html = published.path("bodyHtml").asText();
        assertThat(html).contains("<h2", "<table>", "<pre><code>", "<u><s>", "font-size: 24px",
                "data-checked=\"true\"", "data-checked=\"false\"", "@Work Category Owner", "🎉");
        String path = "/api/v1/work-item-updates/" + published.path("id").asText();
        JsonNode read = ok(get(path, member));
        assertThat(read.path("bodyHtml").asText()).isEqualTo(html);
        String changed = json.writeValueAsString(java.util.Map.of("bodyHtml", html.replace("等待验收", "验收完成")));
        JsonNode edited = ok(mutate("PATCH", path, member, changed, read.path("etag").asText(), null));
        assertThat(edited.path("bodyHtml").asText()).isEqualTo(html.replace("等待验收", "验收完成"));
        assertThat(ok(get(path, member)).path("bodyHtml")).isEqualTo(edited.path("bodyHtml"));
        assertThat(mutate("PATCH", path, member, changed, read.path("etag").asText(), null).statusCode()).isEqualTo(412);
        assertThat(mutate("POST", collection, member, fixture, null, UUID.randomUUID()).statusCode()).isEqualTo(422);
        String tampered = json.writeValueAsString(java.util.Map.of("bodyHtml",
                "<p onclick='evil()'><a href='javascript:evil()'>安全正文</a><span style='position:fixed;color:#fff'>样式</span><script>evil()</script></p>"));
        JsonNode safe = created(mutate("POST", collection, member, tampered, null, UUID.randomUUID()));
        assertThat(safe.path("bodyHtml").asText()).doesNotContain("javascript", "onclick", "position", "script");
    }

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

    @Test
    void discussionThreadsEnforcePermissionsDepthAndUnlimitedAuthorEdits() throws Exception {
        JsonNode item = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "讨论串"), null, UUID.randomUUID()));
        String collection = "/api/v1/work-items/" + item.path("id").asText() + "/updates";
        JsonNode root = publishComment(collection, member, "主评论", null);
        String path = "/api/v1/work-item-updates/" + root.path("id").asText();
        jdbc.sql("UPDATE yumpoo.work_item_update SET created_at=created_at-interval '2 days' WHERE id=:id")
                .param("id", UUID.fromString(root.path("id").asText())).update();
        root = ok(mutate("PATCH", path, member, "{\"bodyHtml\":\"<p>两天后编辑</p>\"}", root.path("etag").asText(), null));
        assertThat(root.has("editDeadlineAt")).isFalse();
        assertThat(root.path("capabilities").path("canEdit").asBoolean()).isTrue();
        assertThat(mutate("PATCH", path, owner, "{\"bodyHtml\":\"<p>越权编辑</p>\"}", root.path("etag").asText(), null).statusCode()).isEqualTo(403);
        JsonNode child = publishComment(collection, owner, "管理员回复", root.path("id").asText());
        String childPath = "/api/v1/work-item-updates/" + child.path("id").asText();
        assertThat(mutate("DELETE", childPath, member, "{}", child.path("etag").asText(), null).statusCode()).isEqualTo(403);
        assertThat(mutate("POST", collection, member, commentBody("第三级", child.path("id").asText()), null, UUID.randomUUID()).statusCode()).isEqualTo(422);
        assertThat(mutate("PATCH", childPath + "/pin", owner, "{\"pinned\":true}", child.path("etag").asText(), null).statusCode()).isEqualTo(409);
        JsonNode otherItem = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "其他工作项"), null, UUID.randomUUID()));
        assertThat(mutate("POST", "/api/v1/work-items/" + otherItem.path("id").asText() + "/updates", member,
                commentBody("跨工作项", root.path("id").asText()), null, UUID.randomUUID()).statusCode()).isEqualTo(422);
        JsonNode withReplies = ok(get(path, member));
        assertThat(withReplies.path("replyCount").asInt()).isOne();
        assertThat(withReplies.path("replies").get(0).path("id").asText()).isEqualTo(child.path("id").asText());
        ok(mutate("DELETE", path, member, "{}", root.path("etag").asText(), null));
        assertThat(ok(get(collection, member)).path("items").size()).isZero();
        assertThat(ok(get(childPath, owner)).path("bodyHtml").isNull()).isTrue();
        assertThat(mutate("POST", collection, member, commentBody("已删父评论", root.path("id").asText()), null, UUID.randomUUID()).statusCode()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE event_type='workitem.work_item_update_deleted' AND event_version=2")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void discussionPinAndReplyPaginationAreStableAndIdempotent() throws Exception {
        JsonNode item = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "分页"), null, UUID.randomUUID()));
        String collection = "/api/v1/work-items/" + item.path("id").asText() + "/updates";
        JsonNode root = publishComment(collection, owner, "较早主评论", null);
        String path = "/api/v1/work-item-updates/" + root.path("id").asText();
        for (int index = 0; index < 22; index++) publishComment(collection, member, "主评论" + index, null);
        for (int index = 0; index < 22; index++) publishComment(collection, member, "回复" + index, root.path("id").asText());
        JsonNode pinned = ok(mutate("PATCH", path + "/pin", member, "{\"pinned\":true}", root.path("etag").asText(), null));
        JsonNode noop = ok(mutate("PATCH", path + "/pin", owner, "{\"pinned\":true}", pinned.path("etag").asText(), null));
        assertThat(noop.path("etag")).isEqualTo(pinned.path("etag"));
        assertThat(mutate("PATCH", path + "/pin", member, "{\"pinned\":false}", root.path("etag").asText(), null).statusCode()).isEqualTo(412);
        JsonNode page = ok(get(collection, member));
        assertThat(page.path("items").size()).isEqualTo(20);
        assertThat(page.path("pinnedItems").size()).isOne();
        JsonNode pinnedView = page.path("pinnedItems").get(0);
        assertThat(pinnedView.path("replyCount").asInt()).isEqualTo(22);
        assertThat(pinnedView.path("replies").size()).isEqualTo(20);
        JsonNode older = ok(get(collection + "?cursor=" + page.path("nextCursor").asText(), member));
        assertThat(older.path("items").size()).isEqualTo(3);
        assertThat(older.path("items").get(0).path("id")).isEqualTo(root.path("id"));
        JsonNode replyPage = ok(get(path + "/replies?cursor=" + pinnedView.path("repliesNextCursor").asText(), member));
        assertThat(replyPage.path("items").size()).isEqualTo(2);
        assertThat(replyPage.path("items").get(0).path("bodyText").asText()).isEqualTo("回复20");
        UUID key = UUID.randomUUID();
        String body = commentBody("幂等回复", root.path("id").asText());
        HttpResponse<String> first = mutate("POST", collection, member, body, null, key);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(mutate("POST", collection, member, body, null, key).body()).isEqualTo(first.body());
        ok(mutate("PATCH", path + "/pin", member, "{\"pinned\":false}", pinned.path("etag").asText(), null));
        assertThat(ok(get(collection, member)).path("pinnedItems").size()).isZero();
    }

    @Test
    void discussionConcurrentDeleteAndReplyNeverLeaveAnActiveOrphan() throws Exception {
        JsonNode item = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "并发删除"), null, UUID.randomUUID()));
        String collection = "/api/v1/work-items/" + item.path("id").asText() + "/updates";
        JsonNode root = publishComment(collection, member, "主评论", null);
        String path = "/api/v1/work-item-updates/" + root.path("id").asText();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var reply = executor.submit(() -> { start.await(); return mutate("POST", collection, owner,
                    commentBody("并发回复", root.path("id").asText()), null, UUID.randomUUID()); });
            var delete = executor.submit(() -> { start.await(); return mutate("DELETE", path, member, "{}", root.path("etag").asText(), null); });
            start.countDown();
            assertThat(delete.get().statusCode()).isEqualTo(200);
            assertThat(reply.get().statusCode()).isIn(201, 409);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_update WHERE parent_update_id=:parent AND status<>'DELETED'")
                .param("parent", UUID.fromString(root.path("id").asText())).query(Long.class).single()).isZero();
    }

    @Test
    void archivedProjectAllowsOnlyOwnerDeletionAndNoPin() throws Exception {
        JsonNode item = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "归档权限"), null, UUID.randomUUID()));
        String collection = "/api/v1/work-items/" + item.path("id").asText() + "/updates";
        JsonNode root = publishComment(collection, member, "归档前评论", null);
        String path = "/api/v1/work-item-updates/" + root.path("id").asText();
        ok(mutate("POST", "/api/v1/work-items/" + item.path("id").asText() + "/transitions", member,
                "{\"toStatus\":\"" + transitionTo(item, "DONE") + "\"}", item.path("etag").asText(), UUID.randomUUID()));
        JsonNode project = ok(get("/api/v1/projects/" + PROJECT_ID, owner));
        ok(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/archive", owner,
                "{\"reason\":\"验收归档\"}", project.path("etag").asText(), UUID.randomUUID()));
        JsonNode view = ok(get(path, member));
        assertThat(view.path("capabilities").path("canDelete").asBoolean()).isFalse();
        assertThat(view.path("capabilities").path("canPin").asBoolean()).isFalse();
        assertThat(mutate("DELETE", path, member, "{}", root.path("etag").asText(), null).statusCode()).isEqualTo(403);
        assertThat(mutate("PATCH", path + "/pin", owner, "{\"pinned\":true}", root.path("etag").asText(), null).statusCode()).isEqualTo(409);
        ok(mutate("DELETE", path, owner, "{}", root.path("etag").asText(), null));
    }

    @Test
    void discussionCascadeRollsBackWhenAChildOutboxWriteFails() throws Exception {
        JsonNode item = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "事务回滚"), null, UUID.randomUUID()));
        String collection = "/api/v1/work-items/" + item.path("id").asText() + "/updates";
        JsonNode root = publishComment(collection, member, "主评论", null);
        JsonNode child = publishComment(collection, owner, "回复", root.path("id").asText());
        String path = "/api/v1/work-item-updates/" + root.path("id").asText();
        jdbc.sql("""
                CREATE FUNCTION yumpoo.discussion_fail_child_delete() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.event_type='workitem.work_item_update_deleted'
                        AND NEW.payload_json->>'parentUpdateId' IS NOT NULL THEN
                        RAISE EXCEPTION 'discussion rollback probe';
                    END IF;
                    RETURN NEW;
                END $$
                """).update();
        jdbc.sql("CREATE TRIGGER discussion_fail_child_delete BEFORE INSERT ON yumpoo.outbox_event "
                + "FOR EACH ROW EXECUTE FUNCTION yumpoo.discussion_fail_child_delete()").update();
        try {
            assertThat(mutate("DELETE", path, member, "{}", root.path("etag").asText(), null).statusCode()).isEqualTo(500);
            assertThat(ok(get(path, member)).path("bodyText").asText()).isEqualTo("主评论");
            assertThat(ok(get("/api/v1/work-item-updates/" + child.path("id").asText(), member)).path("bodyText").asText()).isEqualTo("回复");
            assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.security_audit_event WHERE action='WORK_ITEM_UPDATE_DELETED'")
                    .query(Long.class).single()).isZero();
        } finally {
            jdbc.sql("DROP TRIGGER discussion_fail_child_delete ON yumpoo.outbox_event").update();
            jdbc.sql("DROP FUNCTION yumpoo.discussion_fail_child_delete()").update();
        }
    }


    @Test
    void timerIsIdempotentAtomicAndIndependentOfWorkItemVersion() throws Exception {
        JsonNode item = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "计时事务"), null, UUID.randomUUID()));
        String id = item.path("id").asText();
        String startBody = "{\"workItemId\":\"" + id + "\"}";
        UUID key = UUID.randomUUID();
        JsonNode initial = ok(get("/api/v1/me/time-tracker", member));
        HttpResponse<String> started = mutate("POST", "/api/v1/me/time-tracker/start", member, startBody, initial.path("etag").asText(), key);
        JsonNode running = ok(started);
        assertThat(mutate("POST", "/api/v1/me/time-tracker/start", member, startBody, initial.path("etag").asText(), key).body()).isEqualTo(started.body());
        assertThat(mutate("POST", "/api/v1/me/time-tracker/start", member, startBody, initial.path("etag").asText(), UUID.randomUUID()).statusCode()).isEqualTo(412);
        JsonNode ownerRunning = ok(mutate("POST", "/api/v1/me/time-tracker/start", owner, startBody, "\"0\"", UUID.randomUUID()));
        JsonNode history = ok(get("/api/v1/work-items/" + id + "/time-sessions", member));
        assertThat(history.path("items").size()).isEqualTo(2);
        assertThat(history.path("summary").path("runningSessions").size()).isEqualTo(2);
        assertThat(ok(get("/api/v1/work-items/" + id, member)).path("etag").asText()).isEqualTo(item.path("etag").asText());
        JsonNode other = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "切换目标"), null, UUID.randomUUID()));
        JsonNode switched = ok(mutate("POST", "/api/v1/me/time-tracker/switch", member,
                "{\"workItemId\":\"" + other.path("id").asText() + "\",\"sessionId\":\"" + running.path("session").path("id").asText() + "\"}", running.path("etag").asText(), UUID.randomUUID()));
        assertThat(switched.path("session").path("workItemId").asText()).isEqualTo(other.path("id").asText());
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_time_session WHERE user_id=:id AND stopped_at IS NULL")
                .param("id", member.userId()).query(Long.class).single()).isEqualTo(1);
        ok(mutate("POST", "/api/v1/me/time-tracker/stop", member, "{\"sessionId\":\"" + switched.path("session").path("id").asText() + "\"}", switched.path("etag").asText(), UUID.randomUUID()));
        ok(mutate("POST", "/api/v1/me/time-tracker/stop", owner, "{\"sessionId\":\"" + ownerRunning.path("session").path("id").asText() + "\"}", ownerRunning.path("etag").asText(), UUID.randomUUID()));
    }

    @Test
    void timerHistoryRejectsOverlapAndAuditsLongSessionCorrection() throws Exception {
        JsonNode item = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "跨日补录"), null, UUID.randomUUID()));
        String path = "/api/v1/work-items/" + item.path("id").asText() + "/time-sessions";
        String body = "{\"startedAt\":\"2025-01-01T23:00:00Z\",\"stoppedAt\":\"2025-01-03T01:00:00Z\"}";
        JsonNode manual = ok(mutate("POST", path, member, body, null, UUID.randomUUID()));
        assertThat(manual.path("durationMs").asLong()).isEqualTo(26 * 3600000L);
        assertThat(mutate("POST", path, member, body, null, UUID.randomUUID()).statusCode()).isEqualTo(422);
        String record = path + "/" + manual.path("id").asText();
        String corrected = "{\"startedAt\":\"2025-01-01T23:00:00Z\",\"stoppedAt\":\"2025-01-03T02:00:00Z\"}";
        assertThat(mutate("PATCH", record, owner, corrected, manual.path("etag").asText(), UUID.randomUUID()).statusCode()).isEqualTo(403);
        JsonNode edited = ok(mutate("PATCH", record, member, corrected, manual.path("etag").asText(), UUID.randomUUID()));
        assertThat(edited.path("durationMs").asLong()).isEqualTo(27 * 3600000L);
        assertThat(mutate("DELETE", record, member, "{}", edited.path("etag").asText(), UUID.randomUUID()).statusCode()).isEqualTo(422);
        ok(mutate("DELETE", record, member, "{\"reason\":\"验收删除\"}", edited.path("etag").asText(), UUID.randomUUID()));
        assertThat(ok(get(path, member)).path("summary").path("totalDurationMs").asLong()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.security_audit_event WHERE action LIKE 'TIME_TRACKING_%'").query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void timerCursorFreezesDurationAndInvalidatesAfterCommands() throws Exception {
        JsonNode first = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "计时排序一"), null, UUID.randomUUID()));
        JsonNode second = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "计时排序二"), null, UUID.randomUUID()));
        ok(mutate("POST", "/api/v1/work-items/" + first.path("id").asText() + "/time-sessions", member,
                "{\"startedAt\":\"2025-01-01T00:00:00Z\",\"stoppedAt\":\"2025-01-01T01:00:00Z\"}", null, UUID.randomUUID()));
        String collection = "/api/v1/projects/" + PROJECT_ID + "/work-items?sort=TIME_TRACKING,DESC&limit=1";
        JsonNode page = ok(get(collection, member));
        assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(first.path("id").asText());
        String cursor = page.path("nextCursor").asText();
        assertThat(ok(get(collection + "&cursor=" + cursor, member)).path("items").get(0).path("id").asText()).isEqualTo(second.path("id").asText());
        ok(mutate("POST", "/api/v1/me/time-tracker/start", member, "{\"workItemId\":\"" + second.path("id").asText() + "\"}", "\"0\"", UUID.randomUUID()));
        assertThat(get(collection + "&cursor=" + cursor, member).statusCode()).isEqualTo(422);
        JsonNode filtered = ok(get("/api/v1/projects/" + PROJECT_ID + "/work-items?timeTrackingMinMs=3600000", member));
        assertThat(filtered.path("items").size()).isEqualTo(1);
    }

    @Test
    void timerCanStopAfterItemDeletionAndProjectArchival() throws Exception {
        JsonNode item = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "关闭前停止"), null, UUID.randomUUID()));
        String id = item.path("id").asText();
        JsonNode running = ok(mutate("POST", "/api/v1/me/time-tracker/start", member, "{\"workItemId\":\"" + id + "\"}", "\"0\"", UUID.randomUUID()));
        ok(mutate("DELETE", "/api/v1/work-items/" + id, member, "{\"reason\":\"计时删除验收\"}", item.path("etag").asText(), UUID.randomUUID()));
        JsonNode redacted = ok(get("/api/v1/me/time-tracker", member));
        assertThat(redacted.path("session").path("workItemId").isNull()).isTrue();
        JsonNode project = ok(get("/api/v1/projects/" + PROJECT_ID, owner));
        ok(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/archive", owner, "{\"reason\":\"验收归档\"}", project.path("etag").asText(), UUID.randomUUID()));
        JsonNode stopped = ok(mutate("POST", "/api/v1/me/time-tracker/stop", member, "{\"sessionId\":\"" + running.path("session").path("id").asText() + "\"}", running.path("etag").asText(), UUID.randomUUID()));
        assertThat(stopped.path("session").isNull()).isTrue();
    }


    @Test
    void concurrentDevicesCreateExactlyOneRunningSessionAndRevocationStillAllowsStop() throws Exception {
        JsonNode item = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member,
                workItemBody(tasksId, "并发设备"), null, UUID.randomUUID()));
        String body = "{\"workItemId\":\"" + item.path("id").asText() + "\"}";
        ActorFixture secondDevice = actor(member.userId());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<HttpResponse<String>> one = () -> { start.await(); return mutate("POST", "/api/v1/me/time-tracker/start", member, body, "\"0\"", UUID.randomUUID()); };
            java.util.concurrent.Callable<HttpResponse<String>> two = () -> { start.await(); return mutate("POST", "/api/v1/me/time-tracker/start", secondDevice, body, "\"0\"", UUID.randomUUID()); };
            var first = executor.submit(one); var second = executor.submit(two); start.countDown();
            assertThat(java.util.List.of(first.get().statusCode(), second.get().statusCode())).containsExactlyInAnyOrder(200, 412);
            assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_time_session WHERE user_id=:id AND stopped_at IS NULL")
                    .param("id", member.userId()).query(Long.class).single()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
        JsonNode running = ok(get("/api/v1/me/time-tracker", member));
        jdbc.sql("UPDATE yumpoo.project_membership SET status='REMOVED',removed_at=transaction_timestamp(),removed_by_user_id=:owner,remove_reason='计时撤权验收' WHERE project_id=:project AND user_id=:member")
                .param("project",PROJECT_ID).param("member",member.userId()).param("owner",owner.userId()).update();
        JsonNode minimal = ok(get("/api/v1/me/time-tracker", member));
        assertThat(minimal.path("session").path("projectId").isNull()).isTrue();
        assertThat(minimal.path("workItemTitle").isNull()).isTrue();
        ok(mutate("POST", "/api/v1/me/time-tracker/stop", secondDevice, "{\"sessionId\":\"" + running.path("session").path("id").asText() + "\"}", running.path("etag").asText(), UUID.randomUUID()));
    }


    @Test
    void switchAcrossProjectsStopsThePreviousSessionInTheSameTransaction() throws Exception {
        UUID otherProject=UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO yumpoo.project (id,company_id,workspace_id,project_code,name,project_type,lifecycle,owner_user_id,template_key,template_version,row_version,created_at,created_by_user_id,updated_at,updated_by_user_id,activated_at) "
                    + "SELECT :new,company_id,workspace_id,'TIMER_OTHER','Timer Other',project_type,lifecycle,owner_user_id,template_key,template_version,0,created_at,created_by_user_id,updated_at,updated_by_user_id,activated_at FROM yumpoo.project WHERE id=:original")
                    .param("new",otherProject).param("original",PROJECT_ID).update();
            jdbc.sql("INSERT INTO yumpoo.project_membership (id,company_id,project_id,user_id,status,joined_at,joined_by_user_id,row_version) "
                    + "SELECT gen_random_uuid(),company_id,:new,user_id,'ACTIVE',joined_at,joined_by_user_id,0 FROM yumpoo.project_membership WHERE project_id=:original")
                    .param("new",otherProject).param("original",PROJECT_ID).update();
            jdbc.sql("INSERT INTO yumpoo.content_catalog_version(project_id,company_id) VALUES (:p,:c)")
                    .param("p",otherProject).param("c",COMPANY_ID).update();
            labels.initialize(COMPANY_ID,otherProject,"RND",1,clock.instant());
        });
        JsonNode category=created(mutate("POST","/api/v1/projects/"+otherProject+"/contents",owner,
                "{\"name\":\"跨项目计时\",\"colorToken\":\"BRIGHT_GREEN\"}",null,UUID.randomUUID()));
        JsonNode first=created(mutate("POST","/api/v1/projects/"+PROJECT_ID+"/work-items",member,workItemBody(tasksId,"原项目计时"),null,UUID.randomUUID()));
        JsonNode target=created(mutate("POST","/api/v1/projects/"+otherProject+"/work-items",member,workItemBody(UUID.fromString(category.path("id").asText()),"目标项目计时"),null,UUID.randomUUID()));
        String candidates="/api/v1/me/time-tracker/candidates";
        assertThat(ok(get(candidates,member)).path("items").size()).isZero();
        jdbc.sql("UPDATE yumpoo.work_item SET assignee_user_id=:user WHERE id=:item")
                .param("user",member.userId()).param("item",UUID.fromString(first.path("id").asText())).update();
        assertThat(ok(get(candidates,member)).path("items").get(0).path("workItemId").asText()).isEqualTo(first.path("id").asText());
        JsonNode search=ok(get(candidates+"?scope=ALL&q=Timer%20Other",member));
        assertThat(search.path("items").size()).isEqualTo(1);
        assertThat(search.path("items").get(0).path("projectName").asText()).isEqualTo("Timer Other");
        assertThat(search.path("items").get(0).path("contentCode").asText()).isEqualTo(category.path("code").asText()).isNotBlank();
        assertThat(search.path("items").get(0).path("contentColorToken").asText()).isEqualTo("BRIGHT_GREEN");
        assertThat(search.path("items").get(0).path("workItemId").asText()).isEqualTo(target.path("id").asText());
        assertThat(ok(get(candidates+"?scope=ALL&q=%25",member)).path("items").size()).isZero();
        assertThat(get(candidates+"?limit=51",member).statusCode()).isEqualTo(422);
        JsonNode running=ok(mutate("POST","/api/v1/me/time-tracker/start",member,"{\"workItemId\":\""+first.path("id").asText()+"\"}","\"0\"",UUID.randomUUID()));
        JsonNode switched=ok(mutate("POST","/api/v1/me/time-tracker/switch",member,
                "{\"workItemId\":\""+target.path("id").asText()+"\",\"sessionId\":\""+running.path("session").path("id").asText()+"\"}",running.path("etag").asText(),UUID.randomUUID()));
        assertThat(switched.path("session").path("projectId").asText()).isEqualTo(otherProject.toString());
        assertThat(switched.path("recentItems").size()).isEqualTo(2);
        JsonNode personal=ok(get(candidates,member));
        assertThat(personal.path("items").size()).isEqualTo(2);
        assertThat(personal.path("items").get(0).path("workItemId").asText()).isEqualTo(target.path("id").asText());
        JsonNode firstPage=ok(get(candidates+"?limit=1",member));
        assertThat(firstPage.path("nextOffset").asInt()).isEqualTo(1);
        JsonNode secondPage=ok(get(candidates+"?limit=1&offset=1",member));
        assertThat(secondPage.path("items").get(0).path("workItemId").asText()).isEqualTo(first.path("id").asText());
        assertThat(secondPage.path("nextOffset").isNull()).isTrue();
        assertThat(ok(get("/api/v1/work-items/"+first.path("id").asText()+"/time-sessions",member)).path("items").get(0).path("stoppedAt").isNull()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_time_session WHERE user_id=:id AND stopped_at IS NULL")
                .param("id",member.userId()).query(Long.class).single()).isEqualTo(1);
        jdbc.sql("UPDATE yumpoo.project_membership SET status='REMOVED',removed_at=transaction_timestamp(),removed_by_user_id=:owner,remove_reason='候选权限验收' WHERE project_id=:project AND user_id=:member")
                .param("project",otherProject).param("member",member.userId()).param("owner",owner.userId()).update();
        assertThat(ok(get(candidates+"?scope=ALL&q=Timer%20Other",member)).path("items").size()).isZero();
        assertThat(ok(get(candidates,member)).path("items").size()).isEqualTo(1);
        ok(mutate("POST","/api/v1/me/time-tracker/stop",member,"{\"sessionId\":\""+switched.path("session").path("id").asText()+"\"}",switched.path("etag").asText(),UUID.randomUUID()));
        ok(mutate("POST","/api/v1/work-items/"+first.path("id").asText()+"/transitions",member,
                "{\"toStatus\":\""+transitionTo(first,"DONE")+"\"}",first.path("etag").asText(),UUID.randomUUID()));
        JsonNode project=ok(get("/api/v1/projects/"+PROJECT_ID,owner));
        ok(mutate("POST","/api/v1/projects/"+PROJECT_ID+"/archive",owner,"{\"reason\":\"候选归档验收\"}",project.path("etag").asText(),UUID.randomUUID()));
        assertThat(ok(get(candidates+"?scope=ALL",member)).path("items").size()).isZero();
    }

    private String commentBody(String text, String parent) throws Exception {
        var body = json.createObjectNode().put("bodyHtml", "<p>" + text + "</p>");
        if (parent != null) body.put("parentUpdateId", parent);
        return json.writeValueAsString(body);
    }

    private JsonNode publishComment(String collection, ActorFixture actor, String text, String parent) throws Exception {
        return created(mutate("POST", collection, actor, commentBody(text, parent), null, UUID.randomUUID()));
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
        for (String table : java.util.List.of("work_item_time_session", "work_item_timer_state", "work_item_time_revision"))
            jdbc.sql("DELETE FROM yumpoo." + table + " WHERE company_id=:id").param("id", COMPANY_ID).update();
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
