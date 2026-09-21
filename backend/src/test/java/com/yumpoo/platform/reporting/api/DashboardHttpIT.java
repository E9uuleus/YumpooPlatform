package com.yumpoo.platform.reporting.api;

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
class DashboardHttpIT {
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID WORKSPACE_ID = UUID.fromString("a460aa25-7180-490b-ab14-f9ec09049024");
    private static final UUID PROJECT_ID = UUID.fromString("2a000000-0000-4000-8000-000000000391");
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
            DirectoryMemberProvisioningResult ownerUser = provisioner.provision("dashboard-owner", "Work Category Owner");
            DirectoryMemberProvisioningResult memberUser = provisioner.provision("dashboard-member", "Work Category Member");
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
    void privateDashboardsPersistAndRequireOwnerAndVersion() throws Exception {
        var body = dashboardBody();
        UUID key = UUID.randomUUID();
        var first = created(mutate("POST", "/api/v1/me/dashboards", member, body, null, key));
        String path = "/api/v1/me/dashboards/" + first.path("id").asText();
        assertThat(created(mutate("POST", "/api/v1/me/dashboards", member, body, null, key))).isEqualTo(first);
        assertThat(ok(get("/api/v1/me/dashboards", member)).path("items").size()).isEqualTo(1);
        assertThat(ok(get("/api/v1/me/dashboards", owner)).path("items").size()).isZero();
        assertThat(get(path, owner).statusCode()).isEqualTo(404);
        assertThat(mutate("PATCH", path, owner, body, "\"0\"", UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(mutate("PATCH", path, member, body, null, UUID.randomUUID()).statusCode()).isEqualTo(428);
        var updated = ok(mutate("PATCH", path, member, body.replace("我的仪表板", "第二版"), "\"0\"", UUID.randomUUID()));
        assertThat(updated.path("name").asText()).isEqualTo("第二版");
        assertThat(mutate("PATCH", path, member, body, "\"0\"", UUID.randomUUID()).statusCode()).isEqualTo(412);
        assertThat(mutate("POST", path + "/query", owner, "{}", null, null).statusCode()).isEqualTo(404);
        UUID deleteKey = UUID.randomUUID();
        ok(mutate("DELETE", path, member, "", "\"1\"", deleteKey));
        ok(mutate("DELETE", path, member, "", "\"1\"", deleteKey));
        assertThat(get(path, member).statusCode()).isEqualTo(404);
    }

    @Test
    void statisticsCountParentsAndChildrenOnceAndSumRawTimeWithFilters() throws Exception {
        var parent = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member, workItemBody(tasksId, "父项"), null, UUID.randomUUID()));
        var child = created(mutate("POST", "/api/v1/work-items/" + parent.path("id").asText() + "/subitems", member, workItemBody(tasksId, "子项"), null, UUID.randomUUID()));
        created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member, workItemBody(requirementsId, "无类型限制"), null, UUID.randomUUID()));
        jdbc.sql("UPDATE yumpoo.work_item SET assignee_user_id=:user WHERE id=:id").param("user", member.userId()).param("id", UUID.fromString(child.path("id").asText())).update();
        String times = "/api/v1/work-items/" + parent.path("id").asText() + "/time-sessions";
        String timeBody = "{\"startedAt\":\"2026-01-01T00:00:00Z\",\"stoppedAt\":\"2026-01-01T01:00:00Z\"}";
        ok(mutate("POST", times, member, timeBody, null, UUID.randomUUID()));
        var second = ok(mutate("POST", times, member, timeBody.replace("2026-01-01", "2026-01-02"), null, UUID.randomUUID()));
        ok(mutate("POST", "/api/v1/work-items/" + child.path("id").asText() + "/time-sessions", member, timeBody.replace("2026-01-01", "2026-01-03"), null, UUID.randomUUID()));
        String path = "/api/v1/me/dashboards/" + created(mutate("POST", "/api/v1/me/dashboards", member, dashboardBody(), null, UUID.randomUUID())).path("id").asText();
        var all = ok(mutate("POST", path + "/query", member, "{}", null, null));
        assertThat(total(all).path("count").asLong()).isEqualTo(3);
        assertThat(total(all).path("durationMs").asLong()).isEqualTo(10800000);
        var filtered = ok(mutate("POST", path + "/query", member, "{\"filters\":{\"includeArchived\":false,\"hasTime\":false,\"assignees\":[\"" + member.userId() + "\"]}}", null, null));
        assertThat(total(filtered).path("count").asLong()).isEqualTo(1);
        assertThat(total(filtered).path("durationMs").asLong()).isEqualTo(3600000);
        var unassigned = ok(mutate("POST", path + "/query", member, "{\"filters\":{\"includeArchived\":false,\"hasTime\":false,\"assignees\":[\"UNASSIGNED\"]}}", null, null));
        assertThat(total(unassigned).path("count").asLong()).isEqualTo(2);
        var page = ok(mutate("POST", path + "/items/query", member, "{\"offset\":0,\"limit\":1}", null, null));
        assertThat(page.path("items").size()).isEqualTo(1);
        var editable = page.path("items").get(0).path("workItem");
        assertThat(editable.path("id").asText()).isEqualTo(page.path("items").get(0).path("id").asText());
        assertThat(editable.path("etag").asText()).isNotBlank();
        assertThat(editable.path("capabilities").path("canEditFields").asBoolean()).isTrue();
        assertThat(editable.path("timeTracking").isObject()).isTrue();
        assertThat(page.path("totalElements").asLong()).isEqualTo(3);
        assertThat(total(ok(mutate("POST", path + "/query", member, "{\"filters\":{\"includeArchived\":false,\"hasTime\":false,\"query\":\"%\"}}", null, null))).path("count").asLong()).isZero();
        ok(mutate("DELETE", times + "/" + second.path("id").asText(), member, "{}", second.path("etag").asText(), UUID.randomUUID()));
        assertThat(total(ok(mutate("POST", path + "/query", member, "{}", null, null))).path("durationMs").asLong()).isEqualTo(7200000);
        jdbc.sql("UPDATE yumpoo.work_item SET archived=true WHERE id=:id")
                .param("id", UUID.fromString(child.path("id").asText())).update();
        assertThat(total(ok(mutate("POST", path + "/query", member, "{}", null, null))).path("count").asLong()).isEqualTo(2);
        assertThat(total(ok(mutate("POST", path + "/query", member, "{\"filters\":{\"includeArchived\":true,\"hasTime\":false}}", null, null))).path("count").asLong()).isEqualTo(3);
    }

    @Test
    void editableDetailsRespectVersionFilterAndReadOnlyProject() throws Exception {
        var item = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member, workItemBody(tasksId, "可编辑明细"), null, UUID.randomUUID()));
        String path = "/api/v1/me/dashboards/" + created(mutate("POST", "/api/v1/me/dashboards", member, dashboardBody(), null, UUID.randomUUID())).path("id").asText();
        String query = "{\"offset\":0,\"limit\":25,\"filters\":{\"includeArchived\":false,\"hasTime\":false,\"assignees\":[\"UNASSIGNED\"]}}";
        var row = ok(mutate("POST", path + "/items/query", member, query, null, null)).path("items").get(0).path("workItem");
        String editPath = "/api/v1/work-items/" + item.path("id").asText() + "/assignee";
        String body = "{\"assigneeUserId\":\"" + member.userId() + "\"}";
        ok(mutate("PATCH", editPath, member, body, row.path("etag").asText(), UUID.randomUUID()));
        assertThat(mutate("PATCH", editPath, member, body, row.path("etag").asText(), UUID.randomUUID()).statusCode()).isEqualTo(412);
        assertThat(ok(mutate("POST", path + "/items/query", member, query, null, null)).path("totalElements").asLong()).isZero();
        jdbc.sql("UPDATE yumpoo.project SET lifecycle='ARCHIVED',archived_at=transaction_timestamp(),updated_at=transaction_timestamp() WHERE id=:id").param("id", PROJECT_ID).update();
        var readOnly = ok(mutate("POST", path + "/items/query", member, "{\"offset\":0,\"limit\":25}", null, null)).path("items").get(0).path("workItem");
        assertThat(readOnly.path("capabilities").path("canEditFields").asBoolean()).isFalse();
    }

    @Test
    void sharedTableUsesScopedCursorSortingAndFacets() throws Exception {
        for (String title : java.util.List.of("B", "A"))
            created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member, workItemBody(tasksId, title), null, UUID.randomUUID()));
        created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member, workItemBody(requirementsId, "outside"), null, UUID.randomUUID()));
        String path = "/api/v1/me/dashboards/" + created(mutate("POST", "/api/v1/me/dashboards", member, dashboardBody(), null, UUID.randomUUID())).path("id").asText();
        var widget = chartWidget();
        ((tools.jackson.databind.node.ObjectNode) widget.path("chart")).put("dimension", "CONTENT");
        var criteria = json.createObjectNode().put("limit", 1).set("sort", json.createArrayNode().add("TITLE,ASC"));
        var query = json.createObjectNode().put("projectId", PROJECT_ID.toString()).set("widget", widget).set("table", criteria)
                .set("selection", json.createObjectNode().put("key", tasksId.toString()));
        var first = ok(mutate("POST", path + "/table/query", member, query.toString(), null, null));
        assertThat(first.path("items").get(0).path("title").asText()).isEqualTo("A");
        assertThat(first.path("items").get(0).path("etag").asText()).isNotBlank();
        assertThat(first.path("items").get(0).path("capabilities").path("canEditFields").asBoolean()).isTrue();
        assertThat(first.path("nextCursor").isTextual()).isTrue();
        criteria.put("cursor", first.path("nextCursor").asText());
        var second = ok(mutate("POST", path + "/table/query", member, query.toString(), null, null));
        assertThat(second.path("items").get(0).path("title").asText()).isEqualTo("B");
        assertThat(second.path("nextCursor").isNull()).isTrue();
        query.set("selection", json.createObjectNode().put("key", requirementsId.toString()));
        assertThat(mutate("POST", path + "/table/query", member, query.toString(), null, null).statusCode()).isEqualTo(422);
        query.set("selection", json.createObjectNode().put("key", tasksId.toString()));
        criteria.remove("cursor"); criteria.put("field", "CONTENT");
        var options = ok(mutate("POST", path + "/table/query", member, query.toString(), null, null)).path("options");
        assertThat(options.size()).isEqualTo(1);
        assertThat(options.get(0).path("value").asText()).isEqualTo(tasksId.toString());
        assertThat(options.get(0).path("count").asLong()).isEqualTo(2);
        criteria.remove("field"); criteria.set("contentId", json.createArrayNode().add(requirementsId.toString()));
        assertThat(ok(mutate("POST", path + "/table/query", member, query.toString(), null, null)).path("items").isEmpty()).isTrue();
        criteria.remove("contentId"); criteria.put("q", "%");
        assertThat(ok(mutate("POST", path + "/table/query", member, query.toString(), null, null)).path("items").isEmpty()).isTrue();
        criteria.remove("q");
        query.set("filters", json.createObjectNode().put("includeArchived", false).put("hasTime", false).set("contentIds", json.createArrayNode().add(requirementsId.toString())));
        assertThat(ok(mutate("POST", path + "/table/query", member, query.toString(), null, null)).path("items").isEmpty()).isTrue();
    }

    @Test
    void sharedTablePreservesMatchingChildrenAndRefreshesAfterEdits() throws Exception {
        var parent = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member, workItemBody(requirementsId, "父项上下文"), null, UUID.randomUUID()));
        var child = created(mutate("POST", "/api/v1/work-items/" + parent.path("id").asText() + "/subitems", member, workItemBody(tasksId, "匹配子项"), null, UUID.randomUUID()));
        created(mutate("POST", "/api/v1/work-items/" + parent.path("id").asText() + "/subitems", member, workItemBody(requirementsId, "范围外子项"), null, UUID.randomUUID()));
        String path = "/api/v1/me/dashboards/" + created(mutate("POST", "/api/v1/me/dashboards", member, dashboardBody(), null, UUID.randomUUID())).path("id").asText();
        var widget = chartWidget();
        ((tools.jackson.databind.node.ObjectNode) widget.path("chart")).put("dimension", "CONTENT");
        var criteria = json.createObjectNode();
        var query = json.createObjectNode().put("projectId", PROJECT_ID.toString()).set("widget", widget).set("table", criteria)
                .set("selection", json.createObjectNode().put("key", tasksId.toString()));
        var roots = ok(mutate("POST", path + "/table/query", member, query.toString(), null, null));
        assertThat(roots.path("items").size()).isEqualTo(1);
        assertThat(roots.path("items").get(0).path("id").asText()).isEqualTo(parent.path("id").asText());
        assertThat(roots.path("items").get(0).path("subitemCount").asLong()).isEqualTo(2);
        assertThat(roots.path("subitemCounts").path(parent.path("id").asText()).asLong()).isEqualTo(1);
        assertThat(roots.path("contextIds").get(0).asText()).isEqualTo(parent.path("id").asText());
        criteria.put("parentWorkItemId", parent.path("id").asText());
        var children = ok(mutate("POST", path + "/table/query", member, query.toString(), null, null)).path("items");
        assertThat(children.size()).isEqualTo(1);
        assertThat(children.get(0).path("id").asText()).isEqualTo(child.path("id").asText());
        ok(mutate("PATCH", "/api/v1/work-items/" + child.path("id").asText() + "/content", member,
                json.createObjectNode().put("contentId", requirementsId.toString()).toString(), children.get(0).path("etag").asText(), UUID.randomUUID()));
        criteria.remove("parentWorkItemId");
        assertThat(ok(mutate("POST", path + "/table/query", member, query.toString(), null, null)).path("items").isEmpty()).isTrue();
    }

    @Test
    void sharedTableRejectsOtherOwnersUnconnectedProjectsAndRevokedMemberships() throws Exception {
        String path = "/api/v1/me/dashboards/" + created(mutate("POST", "/api/v1/me/dashboards", member, dashboardBody(), null, UUID.randomUUID())).path("id").asText();
        var query = json.createObjectNode().put("projectId", PROJECT_ID.toString()).set("widget", chartWidget()).set("table", json.createObjectNode());
        assertThat(mutate("POST", path + "/table/query", owner, query.toString(), null, null).statusCode()).isEqualTo(404);
        query.put("projectId", UUID.randomUUID().toString());
        assertThat(mutate("POST", path + "/table/query", member, query.toString(), null, null).statusCode()).isEqualTo(404);
        query.put("projectId", PROJECT_ID.toString());
        jdbc.sql("UPDATE yumpoo.project_membership SET status='REMOVED',removed_at=transaction_timestamp(),removed_by_user_id=:owner,remove_reason='dashboard test' WHERE project_id=:project AND user_id=:user")
                .param("owner", owner.userId()).param("project", PROJECT_ID).param("user", member.userId()).update();
        assertThat(mutate("POST", path + "/table/query", member, query.toString(), null, null).statusCode()).isEqualTo(404);
    }

    @Test
    void removedMembershipImmediatelyHidesProjectAndStatistics() throws Exception {
        String path = "/api/v1/me/dashboards/" + created(mutate("POST", "/api/v1/me/dashboards", member, dashboardBody(), null, UUID.randomUUID())).path("id").asText();
        assertThat(ok(get("/api/v1/me/dashboard-projects", member)).path("totalElements").asLong()).isEqualTo(1);
        jdbc.sql("UPDATE yumpoo.project_membership SET status='REMOVED',removed_at=transaction_timestamp(),removed_by_user_id=:owner,remove_reason='dashboard test' WHERE project_id=:project AND user_id=:user")
                .param("owner", owner.userId()).param("project", PROJECT_ID).param("user", member.userId()).update();
        assertThat(ok(get("/api/v1/me/dashboard-projects", member)).path("items").size()).isZero();
        var view = ok(get(path, member));
        assertThat(view.path("projects").get(0).path("available").asBoolean()).isFalse();
        assertThat(ok(mutate("POST", path + "/items/query", member, "{\"offset\":0,\"limit\":25}", null, null)).path("items").size()).isZero();
        assertThat(view.path("projects").get(0).path("name").isNull()).isTrue();
        assertThat(ok(mutate("POST", path + "/query", member, "{}", null, null)).path("buckets").size()).isZero();
        ok(mutate("PATCH", path, member, dashboardBody().replace("我的仪表板", "仍可编辑"), "\"0\"", UUID.randomUUID()));
        assertThat(mutate("POST", "/api/v1/me/dashboards", member, dashboardBody(), null, UUID.randomUUID()).statusCode()).isEqualTo(404);
    }

    @Test
    void customChartsPreviewDatesSeriesTimeCalculationsAndIndependentScopes() throws Exception {
        var a = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member, workItemBody(tasksId, "图表 A"), null, UUID.randomUUID()));
        var b = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member, workItemBody(tasksId, "图表 B"), null, UUID.randomUUID()));
        var c = created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member, workItemBody(requirementsId, "图表 C"), null, UUID.randomUUID()));
        jdbc.sql("UPDATE yumpoo.work_item SET due_date=DATE '2026-09-17' WHERE id IN (:ids)")
                .param("ids", java.util.List.of(UUID.fromString(a.path("id").asText()), UUID.fromString(b.path("id").asText()))).update();
        jdbc.sql("UPDATE yumpoo.work_item SET due_date=DATE '2026-10-01' WHERE id=:id").param("id", UUID.fromString(c.path("id").asText())).update();
        for (var item : java.util.List.of(a, b)) ok(mutate("POST", "/api/v1/work-items/" + item.path("id").asText() + "/time-sessions", member,
                "{\"startedAt\":\"2026-01-" + (item == a ? "01" : "02") + "T00:00:00Z\",\"stoppedAt\":\"2026-01-" + (item == a ? "01" : "02") + "T" + (item == a ? "01" : "03") + ":00:00Z\"}", null, UUID.randomUUID()));
        String path = "/api/v1/me/dashboards/" + created(mutate("POST", "/api/v1/me/dashboards", member, dashboardBody(), null, UUID.randomUUID())).path("id").asText();
        var widget = chartWidget();
        var chart = (tools.jackson.databind.node.ObjectNode) widget.path("chart");
        chart.put("dimension", "DUE"); chart.put("series", "CONTENT");
        var points = preview(path, widget, null).path("charts").get(0).path("points");
        assertThat(points.size()).isEqualTo(2);
        assertThat(points.get(0).path("key").asText()).isEqualTo("2026-09-01");
        assertThat(points.get(0).path("value").asDouble()).isEqualTo(2);
        var query = json.createObjectNode().put("offset", 0).put("limit", 25).set("widget", widget);
        query.set("selection", json.createObjectNode().put("key", "2026-09-01").put("seriesKey", tasksId.toString()));
        assertThat(ok(mutate("POST", path + "/items/query", member, query.toString(), null, null)).path("totalElements").asLong()).isEqualTo(2);
        query.set("selection", json.createObjectNode().put("key", "2026-09-01").put("seriesKey", requirementsId.toString()));
        assertThat(ok(mutate("POST", path + "/items/query", member, query.toString(), null, null)).path("totalElements").asLong()).isZero();
        chart.put("dateInterval", "WEEK");
        assertThat(preview(path, widget, null).path("charts").get(0).path("points").get(0).path("key").asText()).isEqualTo("2026-09-14");
        chart.put("dateInterval", "DAY");
        assertThat(preview(path, widget, null).path("charts").get(0).path("points").get(0).path("key").asText()).isEqualTo("2026-09-17");
        jdbc.sql("UPDATE yumpoo.work_item SET created_at=TIMESTAMPTZ '2026-09-16 18:00:00Z' WHERE project_id=:id").param("id", PROJECT_ID).update();
        chart.put("dimension", "CREATED");
        assertThat(preview(path, widget, null).path("charts").get(0).path("points").get(0).path("key").asText()).isEqualTo("2026-09-17");
        chart.put("timezone", "UTC");
        assertThat(preview(path, widget, null).path("charts").get(0).path("points").get(0).path("key").asText()).isEqualTo("2026-09-16");
        chart.put("dimension", "PRIORITY"); chart.put("showEmpty", false);
        assertThat(preview(path, widget, null).path("charts").get(0).path("points").isEmpty()).isTrue();
        chart.put("showEmpty", true);
        chart.put("dimension", "PROJECT"); chart.put("series", "NONE");
        var expected = java.util.Map.of("SUM", 14400000d, "AVG", 4800000d, "MEDIAN", 3600000d, "MIN", 0d, "MAX", 10800000d);
        for (var entry : expected.entrySet()) {
            chart.set("measure", json.createObjectNode().put("metric", "DURATION").put("calculation", entry.getKey()));
            assertThat(preview(path, widget, null).path("charts").get(0).path("points").get(0).path("value").asDouble()).isEqualTo(entry.getValue());
        }
        chart.put("series", "CONTENT");
        chart.set("measure", json.createObjectNode().put("metric", "DURATION").put("calculation", "AVG"));
        for (var point : preview(path, widget, null).path("charts").get(0).path("points"))
            assertThat(point.path("categoryValue").asDouble()).isEqualTo(4800000);
        chart.put("series", "NONE");
        chart.set("filters", json.createObjectNode().put("includeArchived", true).put("hasTime", false).set("contentIds", json.createArrayNode().add(tasksId.toString())));
        var global = json.createObjectNode().put("includeArchived", false).put("hasTime", false).set("contentIds", json.createArrayNode().add(requirementsId.toString()));
        assertThat(preview(path, widget, global).path("charts").get(0).path("points").isEmpty()).isTrue();
        chart.set("projectIds", json.createArrayNode());
        assertThat(preview(path, widget, null).path("charts").get(0).path("points").isEmpty()).isTrue();
        chart.putNull("projectIds"); chart.putNull("filters");
        chart.put("type", "BUBBLE");
        chart.set("xMeasure", json.createObjectNode().put("metric", "TOTAL").put("calculation", "SUM"));
        chart.set("sizeMeasure", json.createObjectNode().put("metric", "DURATION").put("calculation", "SUM"));
        var bubble = preview(path, widget, null).path("charts").get(0).path("points").get(0);
        assertThat(bubble.path("xValue").asDouble()).isEqualTo(3);
        assertThat(bubble.path("sizeValue").asDouble()).isEqualTo(14400000);
        var body = (tools.jackson.databind.node.ObjectNode) json.readTree(dashboardBody());
        ((tools.jackson.databind.node.ObjectNode) body.path("configuration")).set("widgets", json.createArrayNode().add(widget));
        ok(mutate("PATCH", path, member, body.toString(), "\"0\"", UUID.randomUUID()));
        assertThat(ok(get(path, member)).path("configuration").path("widgets").get(0).path("chart").path("type").asText()).isEqualTo("BUBBLE");
        chart.put("timezone", "invalid/timezone");
        assertThat(mutate("POST", path + "/query", member, json.createObjectNode().set("widgets", json.createArrayNode().add(widget)).toString(), null, null).statusCode()).isEqualTo(422);
    }

    @Test
    void legacyWidgetsAndDraftChartsRespectRevocation() throws Exception {
        created(mutate("POST", "/api/v1/projects/" + PROJECT_ID + "/work-items", member, workItemBody(tasksId, "兼容"), null, UUID.randomUUID()));
        String path = "/api/v1/me/dashboards/" + created(mutate("POST", "/api/v1/me/dashboards", member, dashboardBody(), null, UUID.randomUUID())).path("id").asText();
        var widget = chartWidget(); widget.remove("chart"); widget.put("kind", "METRIC");
        assertThat(preview(path, widget, null).path("charts").get(0).path("points").get(0).path("value").asDouble()).isEqualTo(1);
        jdbc.sql("UPDATE yumpoo.project_membership SET status='REMOVED',removed_at=transaction_timestamp(),removed_by_user_id=:owner,remove_reason='chart test' WHERE project_id=:project AND user_id=:user")
                .param("owner", owner.userId()).param("project", PROJECT_ID).param("user", member.userId()).update();
        assertThat(preview(path, chartWidget(), null).path("charts").get(0).path("points").isEmpty()).isTrue();
        var body = json.createObjectNode().put("offset", 0).put("limit", 25).set("widget", widget);
        assertThat(ok(mutate("POST", path + "/items/query", member, body.toString(), null, null)).path("totalElements").asLong()).isZero();
    }

    private JsonNode preview(String path, JsonNode widget, JsonNode filters) throws Exception {
        var body = json.createObjectNode().set("widgets", json.createArrayNode().add(widget));
        if (filters != null) body.set("filters", filters);
        return ok(mutate("POST", path + "/query", member, body.toString(), null, null));
    }

    private tools.jackson.databind.node.ObjectNode chartWidget() throws Exception {
        return (tools.jackson.databind.node.ObjectNode) json.readTree("""
            {"id":"%s","kind":"CHART","title":"图表","metric":"TOTAL","grouping":"STATUS","sort":"DESC","showLegend":true,"showValues":true,
             "wide":{"x":0,"y":0,"w":2,"h":3},"medium":{"x":0,"y":0,"w":2,"h":3},
             "chart":{"type":"COLUMN","dimension":"STATUS","series":"NONE","dateInterval":"MONTH","timezone":"Asia/Shanghai",
             "measure":{"metric":"TOTAL","calculation":"SUM"},"stacked":false,"showLegend":true,"showValues":true,"valueFormat":"VALUE",
             "sort":"VALUE_DESC","limit":0,"showEmpty":true,"projectIds":null,"filters":null,"labels":[],"detailColumns":["status"]}}
            """.formatted(UUID.randomUUID()));
    }

    private JsonNode total(JsonNode snapshot) {
        for (var bucket : snapshot.path("buckets")) if (bucket.path("kind").asText().equals("TOTAL")) return bucket;
        throw new AssertionError(snapshot.toString());
    }
    private String dashboardBody() {
        return "{\"name\":\"我的仪表板\",\"configuration\":{\"projectIds\":[\"" + PROJECT_ID + "\"],\"widgets\":[],\"filters\":{\"includeArchived\":false,\"hasTime\":false}}}";
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
        jdbc.sql("DELETE FROM yumpoo.personal_dashboard WHERE company_id=:id").param("id", COMPANY_ID).update();
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
