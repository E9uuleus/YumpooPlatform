package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.administration.application.ProjectPurgeService;
import com.yumpoo.platform.catalog.api.ProjectPurgeQueue;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureProvisioner;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "yumpoo.outbox.enabled=false", "yumpoo.projects.deletion.purge-poll-delay=24h",
        "yumpoo.attachments.scan-enabled=false", "yumpoo.attachments.maintenance-initial-delay=24h"})
class TimeTrackingHttpIT {
    private static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private record Actor(UUID userId, IssuedSession session) {}
    private record Project(UUID id, String code, UUID itemId) {}
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    @LocalServerPort private int port;
    @Autowired private IdentityAcceptanceFixtureProvisioner users;
    @Autowired private SessionService sessions;
    @Autowired private ProjectPurgeQueue queue;
    @Autowired private ProjectPurgeService purger;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private PlatformTransactionManager transactions;
    private Actor owner, member;
    private Project source, target;

    @BeforeEach
    void fixture() throws Exception {
        jdbc.sql("TRUNCATE yumpoo.project, yumpoo.project_purge_run, yumpoo.work_item_timer_state, "
                + "yumpoo.outbox_event, yumpoo.activity_event, yumpoo.notification_event, "
                + "yumpoo.attachment_project_purge CASCADE").update();
        try (var ignored = RequestCorrelationContext.open(RequestCorrelation.root("timer-purge-" + UUID.randomUUID()))) {
            owner = actor("owner");
            member = actor("member");
        }
        source = project("原计时项目");
        target = project("保留计时项目");
    }

    @ParameterizedTest
    @ValueSource(strings = {"stop", "switch"})
    void purgingProjectRejectsLateTimerStopAndSwitchWithoutRecreatingOutboxOrRevision(String action) throws Exception {
        JsonNode running = start(source);
        // Projects archived before archive stopped timers can still carry a running session into purge.
        markDueProject(source);
        advanceToWorkItem(source);
        UUID session = UUID.fromString(running.path("session").path("id").asText());
        var before = jdbc.sql("SELECT * FROM yumpoo.work_item_time_session WHERE id=:id").param("id", session).query().singleRow();
        long sourceRevision = revision(source), targetRevision = revision(target), targetEvents = events(target);

        var response = timer(action, action.equals("stop") ? Map.of("sessionId", session)
                : Map.of("sessionId", session, "workItemId", target.itemId()), running.path("etag").asText());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT * FROM yumpoo.work_item_time_session WHERE id=:id").param("id", session).query().singleRow()).isEqualTo(before);
        assertThat(events(source)).isZero();
        assertThat(events(target)).isEqualTo(targetEvents);
        assertThat(revision(source)).isEqualTo(sourceRevision);
        assertThat(revision(target)).isEqualTo(targetRevision);
        assertThat(stateVersion()).isEqualTo(running.path("rowVersion").asLong());
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_time_session WHERE user_id=:user AND stopped_at IS NULL")
                .param("user", member.userId()).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT stage FROM yumpoo.project_purge_run WHERE project_id=:project")
                .param("project", source.id()).query(String.class).single()).isEqualTo("WORKITEM");
    }

    @Test
    void archivingProjectStopsRunningTimersAsTheArchivingActor() throws Exception {
        JsonNode running = start(source);
        UUID session = UUID.fromString(running.path("session").path("id").asText());

        archive(source);

        JsonNode current = ok(get("/api/v1/me/time-tracker", member));
        assertThat(current.path("session").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT stopped_at IS NOT NULL FROM yumpoo.work_item_time_session WHERE id=:id")
                .param("id", session).query(Boolean.class).single()).isTrue();
        assertThat(stateVersion()).isEqualTo(running.path("rowVersion").asLong() + 1);
        assertThat(revision(source)).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT actor_user_id FROM yumpoo.outbox_event WHERE event_type='workitem.time_tracking_stopped'
                  AND payload_json->>'sessionId'=:session
                """).param("session", session.toString()).query(UUID.class).single()).isEqualTo(owner.userId());
        var lateStop = timer("stop", Map.of("sessionId", session), running.path("etag").asText());
        assertThat(lateStop.statusCode()).as(lateStop.body()).isEqualTo(412);
        JsonNode restarted = ok(timer("start", Map.of("workItemId", target.itemId()), current.path("etag").asText()));
        assertThat(restarted.path("session").path("projectId").asText()).isEqualTo(target.id().toString());
    }

    @Test
    void formerMemberCanStillStopTheirOwnTimer() throws Exception {
        JsonNode running = start(source);
        jdbc.sql("""
                UPDATE yumpoo.project_membership SET status='REMOVED',removed_at=transaction_timestamp(),
                    removed_by_user_id=:owner,remove_reason='计时停止失权回归' WHERE project_id=:project AND user_id=:user
                """).param("owner", owner.userId()).param("project", source.id()).param("user", member.userId()).update();
        JsonNode current = ok(get("/api/v1/me/time-tracker", member));
        assertThat(current.path("session").path("projectId").isNull()).isTrue();

        JsonNode stopped = ok(timer("stop", Map.of("sessionId", running.path("session").path("id").asText()), running.path("etag").asText()));

        assertThat(stopped.path("session").isNull()).isTrue();
        assertThat(revision(source)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE event_type='workitem.time_tracking_stopped' AND payload_json->>'projectId'=:project")
                .param("project", source.id().toString()).query(Long.class).single()).isOne();
    }

    @Test
    void stopKeepsProjectShareLockUntilCommitSoPurgeCannotStartWhileWaitingForTimerState() throws Exception {
        JsonNode running = start(source);
        markDueProject(source);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                jdbc.sql("SELECT row_version FROM yumpoo.work_item_timer_state WHERE company_id=:company AND user_id=:user FOR UPDATE")
                        .param("company", COMPANY).param("user", member.userId()).query(Long.class).single();
                locked.countDown();
                await(release);
                return null;
            }));
            try {
                await(locked);
                var stop = pool.submit(() -> timer("stop", Map.of("sessionId", running.path("session").path("id").asText()), running.path("etag").asText()));
                awaitBlockedLock("yumpoo.work_item_timer_state", "FOR UPDATE");
                assertThat(queue.claim(Instant.now(), "purge-during-stop", Duration.ofMinutes(3), true)).isEmpty();
                assertThat(stop.isDone()).isFalse();
                release.countDown();
                holder.get(15, TimeUnit.SECONDS);
                assertThat(ok(stop.get(15, TimeUnit.SECONDS)).path("session").isNull()).isTrue();
            } finally { release.countDown(); }
        }
        assertThat(queue.claim(Instant.now(), "purge-after-stop", Duration.ofMinutes(3), true))
                .get().satisfies(lease -> assertThat(lease.projectId()).isEqualTo(source.id()));
    }

    @Test
    void startRechecksPurgeMarkerAfterTheInitialActiveProjectReadWaitsForItsLock() throws Exception {
        long before = events(source);
        var locked = new CountDownLatch(1);
        var mark = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var purgeStart = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                jdbc.sql("SELECT id FROM yumpoo.project WHERE id=:project FOR UPDATE").param("project", source.id()).query(UUID.class).single();
                locked.countDown();
                await(mark);
                markDueProject(source);
                return queue.claim(Instant.now(), "purge-start-winner", Duration.ofMinutes(3), true).orElseThrow();
            }));
            try {
                await(locked);
                var start = pool.submit(() -> startResponse(source));
                awaitBlockedLock("yumpoo.project", "FOR SHARE");
                assertThat(start.isDone()).isFalse();
                mark.countDown();
                assertThat(purgeStart.get(15, TimeUnit.SECONDS).projectId()).isEqualTo(source.id());
                var response = start.get(15, TimeUnit.SECONDS);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
            } finally { mark.countDown(); }
        }
        assertThat(events(source)).isEqualTo(before);
        assertThat(revision(source)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_time_session WHERE user_id=:user")
                .param("user", member.userId()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.work_item_timer_state WHERE user_id=:user")
                .param("user", member.userId()).query(Long.class).single()).isZero();
    }

    private Actor actor(String name) {
        UUID user = users.provision("timer-purge-" + name + "-" + UUID.randomUUID(), "计时清除 " + name).userId();
        return new Actor(user, sessions.issueWebSession(user, "timer-purge-http"));
    }

    private Project project(String name) throws Exception {
        var response = mutate("POST", "/api/v1/projects", owner, json.writeValueAsString(Map.of("name", name)), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var created = json.readTree(response.body());
        UUID id = UUID.fromString(created.path("id").asText());
        jdbc.sql("""
                INSERT INTO yumpoo.project_membership(id,company_id,project_id,user_id,status,joined_at,joined_by_user_id)
                VALUES (:id,:company,:project,:user,'ACTIVE',transaction_timestamp(),:owner)
                """).param("id", UUID.randomUUID()).param("company", COMPANY).param("project", id)
                .param("user", member.userId()).param("owner", owner.userId()).update();
        UUID content = jdbc.sql("SELECT id FROM yumpoo.content WHERE project_id=:project AND code='TASKS'")
                .param("project", id).query(UUID.class).single();
        var itemBody = json.createObjectNode().put("contentId", content.toString()).put("title", name + "工作项");
        itemBody.putNull("priority").putNull("description").putNull("notes");
        var item = mutate("POST", "/api/v1/projects/" + id + "/work-items", member,
                json.writeValueAsString(itemBody), null);
        assertThat(item.statusCode()).as(item.body()).isEqualTo(201);
        return new Project(id, created.path("code").asText(), UUID.fromString(json.readTree(item.body()).path("id").asText()));
    }

    private JsonNode start(Project project) throws Exception { return ok(startResponse(project)); }
    private HttpResponse<String> startResponse(Project project) throws Exception {
        return timer("start", Map.of("workItemId", project.itemId()), "\"0\"");
    }
    private HttpResponse<String> timer(String action, Map<String, ?> body, String etag) throws Exception {
        return mutate("POST", "/api/v1/me/time-tracker/" + action, member, json.writeValueAsString(body), etag);
    }

    private void archive(Project project) throws Exception {
        String etag = ok(get("/api/v1/projects/" + project.id(), owner)).path("etag").asText();
        ok(mutate("POST", "/api/v1/projects/" + project.id() + "/archive", owner, "", etag));
    }

    private void markDueProject(Project project) {
        jdbc.sql("""
                UPDATE yumpoo.project SET lifecycle='ARCHIVED',archived_at=transaction_timestamp(),
                    deletion_requested_at=transaction_timestamp()-interval '31 days',deletion_requested_by=:owner,
                    purge_after=transaction_timestamp()-interval '1 day',updated_at=transaction_timestamp(),row_version=row_version+1
                WHERE id=:project
                """).param("project", project.id()).param("owner", owner.userId()).update();
    }

    private void advanceToWorkItem(Project project) {
        jdbc.sql("UPDATE yumpoo.outbox_event SET status='COMPLETED',next_attempt_at=NULL,completed_at=transaction_timestamp() WHERE status='PENDING'").update();
        for (int i = 0; i < 20; i++) {
            var lease = purger.claim("timer-purge-worker").orElseThrow();
            assertThat(lease.projectId()).isEqualTo(project.id());
            if (lease.stage().equals("WORKITEM")) {
                assertThat(queue.release(lease, Instant.now())).isTrue();
                return;
            }
            try (var ignored = RequestCorrelationContext.open(RequestCorrelation.root("timer-purge-batch-" + UUID.randomUUID()))) {
                assertThat(purger.process(lease)).isTrue();
            }
        }
        throw new AssertionError("purge did not reach WORKITEM");
    }

    private long revision(Project project) {
        return jdbc.sql("SELECT revision FROM yumpoo.work_item_time_revision WHERE company_id=:company AND project_id=:project")
                .param("company", COMPANY).param("project", project.id()).query(Long.class).optional().orElse(0L);
    }
    private long stateVersion() {
        return jdbc.sql("SELECT row_version FROM yumpoo.work_item_timer_state WHERE company_id=:company AND user_id=:user")
                .param("company", COMPANY).param("user", member.userId()).query(Long.class).single();
    }
    private long events(Project project) {
        return jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE company_id=:company AND payload_json->>'projectId'=:project")
                .param("company", COMPANY).param("project", project.id().toString()).query(Long.class).single();
    }
    private void awaitBlockedLock(String table, String mode) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE :table AND query ILIKE :mode)")
                    .param("table", "%" + table + "%").param("mode", "%" + mode + "%").query(Boolean.class).single()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("timer command did not wait for " + table + " " + mode);
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("timer test latch timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
    private HttpResponse<String> get(String path, Actor actor) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).header("Cookie", cookies(actor)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> mutate(String method, String path, Actor actor, String body, String etag) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30))
                .header("Cookie", cookies(actor)).header("X-XSRF-TOKEN", actor.session().csrfCredential().value())
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (etag != null) request.header("If-Match", etag);
        if (!body.isEmpty()) request.header("Content-Type", "application/json");
        return http.send(request.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private static String cookies(Actor actor) {
        return "__Host-yumpoo-session=" + actor.session().sessionCredential().value() + "; __Host-yumpoo-csrf=" + actor.session().csrfCredential().value();
    }
}
