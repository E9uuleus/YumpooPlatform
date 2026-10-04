package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.ConnectionFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "yumpoo.outbox.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConnectionHttpIT {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private WorkItemService items;
    @Autowired private ConnectColumnService columns;
    @Autowired private ContentRepository contents;
    @Autowired private WorkItemLabelRepository labels;
    @Autowired private ObjectMapper json;
    @Autowired private SessionService sessions;
    @Autowired private PlatformTransactionManager transactionManager;
    private Actor owner;
    private Actor sourceMember;
    private Actor targetOwner;
    private Actor outsider;
    private Project source;
    private Project target;
    private Project spare;
    private UUID sourceItem;
    private UUID targetItem;
    private String collection;

    @BeforeEach
    void setUp() {
        var fixture = new ConnectionFixture(jdbc, items, columns, contents, labels, json);
        try (var ignored = correlation()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                owner = actor(fixture.user("HTTP 来源负责人"));
                sourceMember = actor(fixture.user("HTTP 来源成员"));
                targetOwner = actor(fixture.user("HTTP 目标负责人"));
                outsider = actor(fixture.user("HTTP 外部成员"));
                source = fixture.project(owner.user(), "HTTP 来源项目");
                target = fixture.project(targetOwner.user(), "HTTP 目标项目");
                spare = fixture.project(owner.user(), "HTTP 备用项目");
                fixture.member(source.id(), sourceMember.user());
                fixture.member(target.id(), owner.user());
                sourceItem = fixture.item(owner.user(), source, "HTTP 来源工作项").id();
                targetItem = fixture.item(targetOwner.user(), target, "HTTP 目标工作项").id();
            });
        }
        collection = "/projects/" + source.id() + "/connect-columns";
    }

    @Test
    void connectionFiltersApplyToListsOptionsAndRemainActorScoped() throws Exception {
        String columnId = column().path("id").asText();
        body(send("POST", "/work-items/" + sourceItem + "/connections", owner,
                Map.of("columnId", columnId, "targetWorkItemId", targetItem), null, UUID.randomUUID()), 201);
        String sourceList = "/projects/" + source.id() + "/work-items";
        assertThat(body(send("GET", sourceList + "?connectedColumnIds=" + columnId, sourceMember, null, null, null), 200)
                .path("items").get(0).path("id").asText()).isEqualTo(sourceItem.toString());
        assertThat(body(send("GET", sourceList + "?unconnectedColumnIds=" + columnId, sourceMember, null, null, null), 200)
                .path("items").size()).isZero();
        String targetList = "/projects/" + target.id() + "/work-items";
        assertThat(body(send("GET", targetList + "?incomingProjectIds=" + source.id(), targetOwner, null, null, null), 200)
                .path("items").get(0).path("id").asText()).isEqualTo(targetItem.toString());
        assertThat(body(send("GET", targetList + "/filter-options?field=INCOMING_PROJECT&incomingProjectIds=" + source.id(),
                targetOwner, null, null, null), 200).path("items").get(0).path("count").asInt()).isEqualTo(1);
        assertThat(send("GET", targetList + "?incomingProjectIds=" + source.id(), outsider, null, null, null).statusCode()).isEqualTo(404);
    }

    @Test
    void connectionFilterArraysAcceptTwentyAndRejectTwentyOneBeforeDeduplication() throws Exception {
        String list = "/projects/" + source.id() + "/work-items";
        var ids = java.util.stream.IntStream.range(0, 21).mapToObj(index -> UUID.randomUUID().toString()).toList();
        for (String field : List.of("connectedColumnIds", "unconnectedColumnIds", "incomingProjectIds")) {
            for (String path : List.of(list + "?", list + "/filter-options?field=INCOMING_PROJECT&")) {
                String twenty = ids.subList(0, 20).stream().map(id -> field + "=" + id)
                        .collect(java.util.stream.Collectors.joining("&"));
                body(send("GET", path + twenty, owner, null, null, null), 200);
                for (String values : List.of(twenty + "&" + field + "=" + ids.get(20),
                        java.util.Collections.nCopies(21, field + "=" + ids.getFirst()).stream()
                                .collect(java.util.stream.Collectors.joining("&")))) {
                    var problem = body(send("GET", path + values, owner, null, null, null), 422);
                    assertThat(problem.path("fieldErrors").get(0).path("field").asText()).isEqualTo(field);
                    assertThat(problem.path("fieldErrors").get(0).path("code").asText()).isEqualTo("TOO_MANY");
                }
            }
        }
    }

    @Test
    void allThirteenRoutesPreserveStatusHeadersPaginationAndIdempotency() throws Exception {
        var created = column();
        String id = created.path("id").asText();
        String path = collection + "/" + id;
        assertThat(body(send("GET", collection, owner, null, null, null), 200).path("items").size()).isEqualTo(1);
        assertThat(body(send("GET", "/projects/connect-targets?query=" + target.code(), outsider, null, null, null), 200)
                .path("items").get(0).path("id").asText()).isEqualTo(target.id().toString());
        assertThat(body(send("PATCH", path, owner, columnBody("已修改", target.id()), "\"0\"", null), 200)
                .path("rowVersion").asInt()).isEqualTo(1);
        assertThat(body(send("GET", path + "/create-options?targetProjectId=" + target.id(), sourceMember, null, null, null), 200)
                .path("defaultContentId").asText()).isEqualTo(target.contentId().toString());
        assertThat(body(send("GET", path + "/candidates?targetProjectId=" + target.id() + "&sourceWorkItemId=" + sourceItem + "&q=HTTP",
                owner, null, null, null), 200).path("items").size()).isEqualTo(1);
        var linked = send("POST", "/work-items/" + sourceItem + "/connections", owner,
                Map.of("columnId", id, "targetWorkItemId", targetItem), null, UUID.randomUUID());
        var connection = body(linked, 201);
        String connectionPath = "/work-item-connections/" + connection.path("id").asText();
        assertThat(linked.headers().firstValue("etag")).contains("\"0\"");
        assertThat(linked.headers().firstValue("location")).contains("/api/v1" + connectionPath);
        assertThat(body(send("POST", "/work-items/" + sourceItem + "/connections", owner,
                Map.of("columnId", id, "targetWorkItemId", targetItem), null, UUID.randomUUID()), 200).path("id")).isEqualTo(connection.path("id"));
        assertThat(body(send("GET", connectionPath, sourceMember, null, null, null), 200).path("target").path("canOpen").asBoolean()).isFalse();
        assertThat(body(send("GET", "/work-items/" + targetItem + "/incoming-connections?size=1", targetOwner, null, null, null), 200)
                .path("totalElements").asInt()).isEqualTo(1);
        var cells = body(send("GET", "/projects/" + source.id() + "/work-item-connections?workItemIds=" + sourceItem + "&workItemIds=" + targetItem,
                sourceMember, null, null, null), 200).path("items");
        assertThat(cells.size()).isEqualTo(1);
        assertThat(cells.get(0).path("outgoing").get(0).path("connections").size()).isEqualTo(1);
        UUID createKey = UUID.randomUUID();
        var createBody = Map.of("columnId", id, "targetProjectId", target.id(), "title", "  非目标成员提交  ");
        var submitted = send("POST", "/work-items/" + sourceItem + "/connected-work-items", sourceMember, createBody, null, createKey);
        assertThat(body(submitted, 201).path("origin").asText()).isEqualTo("CREATED");
        assertThat(send("POST", "/work-items/" + sourceItem + "/connected-work-items", sourceMember, createBody, null, createKey).body()).isEqualTo(submitted.body());
        UUID unlinkKey = UUID.randomUUID();
        var removed = send("DELETE", connectionPath, targetOwner, null, "\"0\"", unlinkKey);
        assertThat(body(removed, 200).path("active").asBoolean()).isFalse();
        assertThat(send("DELETE", connectionPath, targetOwner, null, "\"0\"", unlinkKey).body()).isEqualTo(removed.body());
        assertThat(send("GET", connectionPath, sourceMember, null, null, null).statusCode()).isEqualTo(404);
        assertThat(body(send("DELETE", connectionPath, sourceMember, null, "\"0\"", UUID.randomUUID()), 409)
                .path("details").path("reason").asText()).isEqualTo("CONNECTION_NOT_ACTIVE");
        assertThat(body(send("DELETE", path, owner, null, "\"1\"", UUID.randomUUID()), 200).path("removedConnectionCount").asInt()).isEqualTo(1);
        String submittedPath = "/work-item-connections/" + body(submitted, 201).path("id").asText();
        assertThat(body(send("DELETE", submittedPath, sourceMember, null, "\"0\"", UUID.randomUUID()), 409)
                .path("details").path("reason").asText()).isEqualTo("CONNECTION_NOT_ACTIVE");
    }

    @Test
    void reverseRoutesSearchAndCreateFromTheTargetSideWithFieldAndSortParameters() throws Exception {
        String id = column().path("id").asText();
        String reverse = "/work-items/" + targetItem;
        var browse = body(send("GET", reverse + "/reverse-connect-candidates?columnId=" + id + "&sort=TITLE", owner, null, null, null), 200);
        assertThat(browse.path("items").get(0).path("card").path("workItemId").asText()).isEqualTo(sourceItem.toString());
        assertThat(send("GET", reverse + "/reverse-connect-candidates?columnId=" + id, targetOwner, null, null, null).statusCode())
                .isEqualTo(403);
        assertThat(send("GET", reverse + "/reverse-connect-candidates?columnId=" + id + "&q=x&fields=DUE_DATE", owner, null, null, null)
                .statusCode()).isEqualTo(422);
        String status = java.net.URLEncoder.encode("未开始", java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body(send("GET", collection + "/" + id + "/candidates?targetProjectId=" + target.id() + "&sourceWorkItemId="
                + sourceItem + "&q=" + status + "&fields=STATUS&fields=NAME", owner, null, null, null), 200).path("items").size()).isEqualTo(1);
        assertThat(body(send("GET", reverse + "/reverse-connect-create-options?columnId=" + id, owner, null, null, null), 200)
                .path("targetProjectId").asText()).isEqualTo(source.id().toString());
        UUID key = UUID.randomUUID();
        var createBody = Map.of("columnId", id, "title", "HTTP 反向新建");
        var created = send("POST", reverse + "/reverse-connected-work-items", owner, createBody, null, key);
        var view = body(created, 201);
        assertThat(view.path("origin").asText()).isEqualTo("LINKED");
        assertThat(view.path("source").path("projectId").asText()).isEqualTo(source.id().toString());
        assertThat(created.headers().firstValue("location")).contains("/api/v1/work-item-connections/" + view.path("id").asText());
        assertThat(send("POST", reverse + "/reverse-connected-work-items", owner, createBody, null, key).body()).isEqualTo(created.body());
        assertThat(send("POST", reverse + "/reverse-connected-work-items", owner,
                Map.of("columnId", id, "title", "未知字段", "targetProjectId", source.id()), null, UUID.randomUUID()).statusCode()).isIn(400, 422);
        assertThat(send("POST", reverse + "/reverse-connected-work-items", targetOwner, createBody, null, UUID.randomUUID()).statusCode())
                .isEqualTo(403);
        assertThat(body(send("GET", reverse + "/incoming-connections?columnId=" + id, targetOwner, null, null, null), 200)
                .path("totalElements").asInt()).isEqualTo(1);
        var cells = body(send("GET", "/projects/" + target.id() + "/work-item-connections?workItemIds=" + targetItem,
                targetOwner, null, null, null), 200);
        assertThat(cells.path("items").get(0).path("incomingByColumn").get(0).path("total").asInt()).isEqualTo(1);
    }

    @Test
    void authenticationCsrfVisibilityPreconditionsAndClosedRequestSchemasAreEnforced() throws Exception {
        assertThat(send("GET", collection, null, null, null, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", collection, outsider, null, null, null).statusCode()).isEqualTo(404);
        var noCsrf = HttpRequest.newBuilder(uri(collection)).header("Cookie", cookies(owner))
                .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(columnBody("CSRF", target.id())))).build();
        assertThat(client.send(noCsrf, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(send("POST", collection, owner, columnBody("缺幂等键", target.id()), null, null).statusCode()).isEqualTo(400);
        assertThat(send("POST", collection, owner, Map.of("name", "未知字段", "targetProjectIds", List.of(target.id()), "position", 1),
                null, UUID.randomUUID()).statusCode()).isIn(400, 422);
        String id = column().path("id").asText();
        String path = collection + "/" + id;
        assertThat(send("PATCH", path, owner, columnBody("缺版本", target.id()), null, null).statusCode()).isEqualTo(428);
        assertThat(send("PATCH", path, outsider, columnBody("不可见", target.id()), null, null).statusCode()).isEqualTo(404);
        assertThat(send("PATCH", path, owner, columnBody("版本过期", target.id()), "\"9\"", null).statusCode()).isEqualTo(412);
        assertThat(send("DELETE", path, sourceMember, null, "\"0\"", UUID.randomUUID()).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/work-items/" + sourceItem + "/connections", sourceMember,
                Map.of("columnId", id, "targetWorkItemId", targetItem), null, UUID.randomUUID()).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/work-items/" + sourceItem + "/connected-work-items", sourceMember,
                Map.of("columnId", id, "targetProjectId", target.id(), "title", "未知字段", "assigneeUserId", owner.user().userId()),
                null, UUID.randomUUID()).statusCode()).isIn(400, 422);
        assertThat(send("GET", "/projects/" + source.id() + "/work-item-connections?workItemIds=" + sourceItem + "&workItemIds=bad",
                owner, null, null, null).statusCode()).isEqualTo(400);
        assertThat(send("GET", path + "/candidates?targetProjectId=" + target.id() + "&sourceWorkItemId=" + sourceItem + "&q=HTTP&size=21",
                owner, null, null, null).statusCode()).isEqualTo(422);
    }

    @Test
    void targetInUseConflictReturnsOnlyTheApprovedAdditionalDetails() throws Exception {
        String id = column().path("id").asText();
        body(send("POST", "/work-items/" + sourceItem + "/connections", owner,
                Map.of("columnId", id, "targetWorkItemId", targetItem), null, UUID.randomUUID()), 201);
        var conflict = body(send("PATCH", collection + "/" + id, owner, columnBody("仍有连接", spare.id()), "\"0\"", null), 409);
        assertThat(conflict.path("details").propertyNames()).containsExactlyInAnyOrder("reason", "targetProjectId", "activeConnectionCount");
        assertThat(conflict.path("details").path("reason").asText()).isEqualTo("CONNECT_TARGET_IN_USE");
        assertThat(conflict.path("details").path("targetProjectId").asText()).isEqualTo(target.id().toString());
        assertThat(conflict.path("details").path("activeConnectionCount").asLong()).isEqualTo(1);
    }

    @Test
    void unicodeDuplicatesAndMissingEnabledCategoriesReturn422FieldErrors() throws Exception {
        String name = "ΟΔΟΣ";
        var column = body(send("POST", collection, owner, columnBody(name, target.id()), null, UUID.randomUUID()), 201);
        String normalized = jdbc.sql("SELECT lower(:name)").param("name", name).query(String.class).single();
        var duplicate = body(send("POST", collection, owner, columnBody(normalized, target.id()), null, UUID.randomUUID()), 422);
        assertThat(duplicate.path("fieldErrors").get(0).path("field").asText()).isEqualTo("name");
        assertThat(duplicate.path("fieldErrors").get(0).path("code").asText()).isEqualTo("DUPLICATE");
        jdbc.sql("UPDATE yumpoo.content SET active=false WHERE project_id=:id").param("id", target.id()).update();
        String path = collection + "/" + column.path("id").asText();
        var options = body(send("GET", path + "/create-options?targetProjectId=" + target.id(), sourceMember, null, null, null), 422);
        assertThat(options.path("fieldErrors").get(0).path("code").asText()).isEqualTo("CONTENT_NOT_ACTIVE");
        var creating = body(send("POST", "/work-items/" + sourceItem + "/connected-work-items", sourceMember,
                Map.of("columnId", column.path("id").asText(), "targetProjectId", target.id(), "title", "无启用类别"), null, UUID.randomUUID()), 422);
        assertThat(creating.path("fieldErrors").get(0).path("code").asText()).isEqualTo("CONTENT_NOT_ACTIVE");
    }

    private JsonNode column() throws Exception {
        var result = send("POST", collection, owner, columnBody("跟进项目", target.id()), null, UUID.randomUUID());
        var value = body(result, 201);
        assertThat(result.headers().firstValue("location")).contains("/api/v1" + collection + "/" + value.path("id").asText());
        assertThat(result.headers().firstValue("etag")).contains("\"0\"");
        return value;
    }

    private Map<String, Object> columnBody(String name, UUID targetId) { return Map.of("name", name, "targetProjectIds", List.of(targetId)); }
    private Actor actor(CurrentActor user) { return new Actor(user, sessions.issueWebSession(user.userId(), "up3-http")); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/v1" + path); }
    private static String cookies(Actor actor) {
        return "__Host-yumpoo-session=" + actor.session().sessionCredential().value()
                + "; __Host-yumpoo-csrf=" + actor.session().csrfCredential().value();
    }
    private HttpResponse<String> send(String method, String path, Actor actor, Object input, String etag, UUID key) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
        if (actor != null) request.header("Cookie", cookies(actor)).header("X-XSRF-TOKEN", actor.session().csrfCredential().value());
        if (etag != null) request.header("If-Match", etag);
        if (key != null) request.header("Idempotency-Key", key.toString());
        if (input != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, input == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode body(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("cache-control").orElseThrow()).contains("no-store");
        return json.readTree(response.body());
    }
    private record Actor(CurrentActor user, IssuedSession session) {}
}
