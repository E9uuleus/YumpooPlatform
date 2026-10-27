package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.administration.application.ProjectPurgeService;
import com.yumpoo.platform.catalog.api.ProjectPurgeQueue;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureProvisioner;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
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
import java.util.List;
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
    @Autowired private CompanyConfigurationQuery company;
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

    @Test
    void parentSummaryIncludesDirectChildTimeAndHistoryDisclosesItsSource() throws Exception {
        JsonNode child=createItem(source.id(),"子项计时");
        String childId=child.path("id").asText();
        var relation=mutate("POST","/api/v1/work-items/"+source.itemId()+"/relations",member,
                json.writeValueAsString(Map.of("relationType","PARENT_CHILD","currentRole","PARENT",
                        "targetProjectId",source.id(),"targetWorkItemId",childId)),null);
        assertThat(relation.statusCode()).as(relation.body()).isEqualTo(201);
        ok(mutate("POST","/api/v1/work-items/"+childId+"/time-sessions",member,
                json.writeValueAsString(Map.of("startedAt","2026-09-01T09:00:00Z","stoppedAt","2026-09-01T10:00:00Z")),null));

        JsonNode summary=ok(get("/api/v1/projects/"+source.id()+"/time-tracking-summaries?workItemId="+source.itemId(),member)).path("items").get(0);
        assertThat(summary.path("allocatedDurationMs").asLong()).isZero();
        assertThat(summary.path("rollupDurationMs").asLong()).isEqualTo(3600000);
        assertThat(ok(get("/api/v1/work-items/"+source.itemId()+"/time-sessions",member)).path("items")).isEmpty();
        JsonNode history=ok(get("/api/v1/work-items/"+source.itemId()+"/time-sessions?includeChildren=true",member)).path("items");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("workItemId").asText()).isEqualTo(childId);
        assertThat(history.get(0).path("workItemNo").asText()).isEqualTo(child.path("itemNo").asText());
        assertThat(history.get(0).path("workItemTitle").asText()).isEqualTo("子项计时");
    }

    @Test
    void parallelTimersShareTheirDurationAndStopTogetherWithoutInflatingTheTimesheet() throws Exception {
        JsonNode running=ok(timer("start",Map.of("workItemIds",List.of(source.itemId(),target.itemId())),"\"0\""));
        assertThat(running.path("runningSessions")).hasSize(2);
        for(Project project:List.of(source,target)) {
            JsonNode summary=ok(get("/api/v1/projects/"+project.id()+"/time-tracking-summaries?workItemId="+project.itemId(),member)).path("items").get(0);
            assertThat(summary.path("rollupRunningRate").asDouble()).isEqualTo(0.5);
        }
        JsonNode stopped=ok(mutate("POST","/api/v1/me/time-tracker/stop-all",member,"",running.path("etag").asText()));
        assertThat(stopped.path("runningSessions")).isEmpty();
        assertThat(stopped.path("resumeItems")).hasSize(2);
        long actualDuration=0;
        Instant startedAt=null,stoppedAt=null;
        for(Project project:List.of(source,target)) {
            JsonNode record=ok(get("/api/v1/work-items/"+project.itemId()+"/time-sessions",member)).path("items").get(0);
            actualDuration=record.path("durationMs").asLong();
            assertThat(record.path("allocatedDurationMs").asLong()).isEqualTo(actualDuration/2);
            assertThat(record.path("parallelItemCount").asInt()).isOne();
            startedAt=Instant.parse(record.path("startedAt").asText()); stoppedAt=Instant.parse(record.path("stoppedAt").asText());
        }
        var zone=company.current().timezone();
        JsonNode sheet=ok(get("/api/v1/me/timesheet?from="+startedAt.atZone(zone).toLocalDate()+"&to="+stoppedAt.atZone(zone).toLocalDate(),member));
        long total=0;
        for(JsonNode entry:sheet.path("entries")) total+=entry.path("durationMs").asLong();
        assertThat(total).isBetween(actualDuration-1,actualDuration);
    }

    @Test
    void fillSupportsParallelReplacementAndGapsWithIdempotentReplay() throws Exception {
        ok(mutate("POST","/api/v1/work-items/"+source.itemId()+"/time-sessions",member,
                json.writeValueAsString(Map.of("startedAt","2026-09-01T08:00:00Z","stoppedAt","2026-09-01T12:00:00Z")),null));
        UUID key=UUID.randomUUID();
        String parallel=fillBody("PARALLEL",target.itemId(),"09:00","10:00");
        var first=mutate("POST","/api/v1/me/time-sessions/fill",member,parallel,null,key);
        assertThat(ok(first).path("createdCount").asInt()).isOne();
        assertThat(mutate("POST","/api/v1/me/time-sessions/fill",member,parallel,null,key).body()).isEqualTo(first.body());
        JsonNode replaced=ok(mutate("POST","/api/v1/me/time-sessions/fill",member,
                fillBody("REPLACE",target.itemId(),"10:00","11:00"),null));
        assertThat(replaced.path("createdCount").asInt()).isEqualTo(2);
        assertThat(replaced.path("changedCount").asInt()).isOne();
        assertThat(replaced.path("deletedCount").asInt()).isZero();
        var sourceRows=ok(get("/api/v1/work-items/"+source.itemId()+"/time-sessions",member)).path("items");
        assertThat(sourceRows).hasSize(2);
        long sourceAllocated=0;
        for(JsonNode row:sourceRows) sourceAllocated+=row.path("allocatedDurationMs").asLong();
        assertThat(sourceAllocated).isEqualTo(9000000);
        UUID third=UUID.fromString(createItem(source.id(),"补录空白").path("id").asText());
        JsonNode gaps=ok(mutate("POST","/api/v1/me/time-sessions/fill",member,fillBody("GAPS",third,"07:00","13:00"),null));
        assertThat(gaps.path("createdCount").asInt()).isEqualTo(2);
        assertThat(gaps.path("skippedDurationMs").asLong()).isEqualTo(4*3600000L);
        JsonNode day=ok(get("/api/v1/me/time-sessions?from=2026-09-01T07:00:00Z&to=2026-09-01T13:00:00Z",member));
        assertThat(day.path("items")).hasSize(6);
        long total=0;
        for(JsonNode row:day.path("items")) total+=row.path("session").path("allocatedDurationMs").asLong();
        assertThat(total).isEqualTo(6*3600000L);
        assertThat(stateVersion()).isZero();
    }

    @Test
    void exclusiveExistingRecordDeletesOtherRecordsWithoutCreatingOrChangingTheTarget() throws Exception {
        String path="/api/v1/me/time-sessions/fill";
        ok(mutate("POST",path,member,fillBody("PARALLEL",source.itemId(),"09:00","10:00"),null));
        ok(mutate("POST",path,member,fillBody("PARALLEL",target.itemId(),"09:00","10:00"),null));
        JsonNode result=ok(mutate("POST",path,member,fillBody("REPLACE",target.itemId(),"09:00","10:00"),null));
        assertThat(result.path("createdCount").asInt()).isZero();
        assertThat(result.path("changedCount").asInt()).isZero();
        assertThat(result.path("deletedCount").asInt()).isOne();
        assertThat(result.path("skippedDurationMs").asLong()).isEqualTo(3600000);
        assertThat(ok(get("/api/v1/work-items/"+source.itemId()+"/time-sessions",member)).path("items")).isEmpty();
        JsonNode kept=ok(get("/api/v1/work-items/"+target.itemId()+"/time-sessions",member)).path("items");
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).path("allocatedDurationMs").asLong()).isEqualTo(3600000);
        JsonNode unchanged=ok(mutate("POST",path,member,fillBody("REPLACE",target.itemId(),"09:00","10:00"),null));
        assertThat(unchanged.path("createdCount").asInt()+unchanged.path("changedCount").asInt()
                +unchanged.path("deletedCount").asInt()).isZero();
    }

    @Test
    void disablingTheTimerStopsRunningSessionsAndRejectsStartsUntilReEnabled() throws Exception {
        JsonNode initial=ok(get("/api/v1/me/time-tracker",member)).path("preferences");
        assertThat(initial.path("timerEnabled").asBoolean()).isTrue();
        assertThat(initial.path("workdayStartMinute").asInt()).isEqualTo(540);
        assertThat(initial.path("workdayEndMinute").asInt()).isEqualTo(1080);
        JsonNode running=ok(timer("start",Map.of("workItemIds",List.of(source.itemId(),target.itemId())),"\"0\""));
        assertThat(running.path("runningSessions")).hasSize(2);
        String off=json.writeValueAsString(Map.of("timerEnabled",false,"workdayStartMinute",510,"workdayEndMinute",1050));
        String path="/api/v1/me/time-tracker/preferences";
        assertThat(mutate("PUT",path,member,off,null).statusCode()).isEqualTo(428);
        assertThat(mutate("PUT",path,member,off,"\"0\"").statusCode()).isEqualTo(412);
        UUID key=UUID.randomUUID();
        var response=mutate("PUT",path,member,off,running.path("etag").asText(),key);
        JsonNode disabled=ok(response);
        assertThat(disabled.path("runningSessions")).isEmpty();
        assertThat(disabled.path("preferences").path("timerEnabled").asBoolean()).isFalse();
        assertThat(disabled.path("preferences").path("workdayStartMinute").asInt()).isEqualTo(510);
        assertThat(disabled.path("rowVersion").asLong()).isEqualTo(running.path("rowVersion").asLong()+1);
        assertThat(mutate("PUT",path,member,off,running.path("etag").asText(),key).body()).isEqualTo(response.body());
        for(Project project:List.of(source,target)) {
            JsonNode record=ok(get("/api/v1/work-items/"+project.itemId()+"/time-sessions",member)).path("items").get(0);
            assertThat(record.path("stoppedAt").isNull()).isFalse();
        }
        for(String action:List.of("start","switch")) {
            var rejected=timer(action,Map.of("workItemId",target.itemId()),disabled.path("etag").asText());
            assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(409);
            assertThat(json.readTree(rejected.body()).path("details").path("reason").asText()).isEqualTo("TIMER_DISABLED");
        }
        String on=json.writeValueAsString(Map.of("timerEnabled",true,"workdayStartMinute",510,"workdayEndMinute",1050));
        JsonNode enabled=ok(mutate("PUT",path,member,on,disabled.path("etag").asText()));
        assertThat(enabled.path("preferences").path("timerEnabled").asBoolean()).isTrue();
        assertThat(enabled.path("runningSessions")).isEmpty();
        assertThat(ok(get("/api/v1/me/time-tracker",member)).path("runningSessions")).isEmpty();
        assertThat(ok(timer("start",Map.of("workItemId",target.itemId()),enabled.path("etag").asText())).path("runningSessions")).hasSize(1);
    }

    @Test
    void hideCompletedIsKeptWhenARequestOmitsIt() throws Exception {
        String path="/api/v1/me/time-tracker/preferences";
        JsonNode initial=ok(get("/api/v1/me/time-tracker",member));
        assertThat(initial.path("preferences").path("hideCompleted").asBoolean()).isFalse();
        JsonNode hidden=ok(mutate("PUT",path,member,json.writeValueAsString(Map.of("timerEnabled",true,"workdayStartMinute",540,
                "workdayEndMinute",1080,"hideCompleted",true)),initial.path("etag").asText()));
        assertThat(hidden.path("preferences").path("hideCompleted").asBoolean()).isTrue();
        JsonNode kept=ok(mutate("PUT",path,member,json.writeValueAsString(Map.of("timerEnabled",true,"workdayStartMinute",510,
                "workdayEndMinute",1080)),hidden.path("etag").asText()));
        assertThat(kept.path("preferences").path("workdayStartMinute").asInt()).isEqualTo(510);
        assertThat(kept.path("preferences").path("hideCompleted").asBoolean()).isTrue();
    }

    @Test
    void reassignMovesAStoppedRecordToAnotherItemWithoutChangingTheDayTotal() throws Exception {
        JsonNode moved=ok(mutate("POST","/api/v1/work-items/"+source.itemId()+"/time-sessions",member,
                json.writeValueAsString(Map.of("startedAt","2026-09-02T09:00:00Z","stoppedAt","2026-09-02T10:00:00Z")),null));
        ok(mutate("POST","/api/v1/work-items/"+target.itemId()+"/time-sessions",member,
                json.writeValueAsString(Map.of("startedAt","2026-09-02T09:30:00Z","stoppedAt","2026-09-02T10:30:00Z")),null));
        long sourceEvents=events(source), targetEvents=events(target);
        String path="/api/v1/me/time-sessions/"+moved.path("id").asText()+"/reassign";
        String body=json.writeValueAsString(Map.of("workItemId",target.itemId()));
        assertThat(mutate("POST",path,member,body,null).statusCode()).isEqualTo(428);
        assertThat(mutate("POST",path,member,body,"\"9\"").statusCode()).isEqualTo(412);
        UUID key=UUID.randomUUID();
        var response=mutate("POST",path,member,body,moved.path("etag").asText(),key);
        JsonNode result=ok(response);
        assertThat(result.path("deletedCount").asInt()).isOne();
        assertThat(result.path("createdCount").asInt()).isOne();
        assertThat(result.path("skippedDurationMs").asLong()).isEqualTo(1800000);
        assertThat(mutate("POST",path,member,body,moved.path("etag").asText(),key).body()).isEqualTo(response.body());
        assertThat(events(source)).isEqualTo(sourceEvents+1);
        assertThat(events(target)).isEqualTo(targetEvents+1);
        assertThat(ok(get("/api/v1/work-items/"+source.itemId()+"/time-sessions",member)).path("items")).isEmpty();
        JsonNode day=ok(get("/api/v1/me/time-sessions?from=2026-09-02T08:00:00Z&to=2026-09-02T12:00:00Z",member)).path("items");
        assertThat(day).hasSize(2);
        long total=0;
        for(JsonNode row:day) {
            assertThat(row.path("session").path("workItemId").asText()).isEqualTo(target.itemId().toString());
            total+=row.path("session").path("allocatedDurationMs").asLong();
        }
        assertThat(total).isEqualTo(90*60000L);
        JsonNode kept=day.get(0).path("session");
        var unchanged=mutate("POST","/api/v1/me/time-sessions/"+kept.path("id").asText()+"/reassign",member,body,kept.path("etag").asText());
        assertThat(unchanged.statusCode()).as(unchanged.body()).isEqualTo(422);
    }

    @Test
    void categoryTimersStartSwitchStopAndResumeWithoutDomainEvents() throws Exception {
        JsonNode started=ok(timer("start",Map.of("workItemId",source.itemId()),"\"0\""));
        long outbox=allEvents();
        JsonNode meeting=ok(timer("switch",Map.of("timeCategory","MEETING"),started.path("etag").asText()));
        assertThat(meeting.path("runningSessions")).hasSize(1);
        JsonNode session=meeting.path("runningSessions").get(0).path("session");
        assertThat(session.path("timeCategory").asText()).isEqualTo("MEETING");
        assertThat(session.path("projectId").isNull()).isTrue();
        assertThat(session.path("workItemId").isNull()).isTrue();
        assertThat(meeting.path("runningSessions").get(0).path("workItemTitle").isNull()).isTrue();
        assertThat(allEvents()).as("only the stopped work item publishes").isEqualTo(outbox+1);
        JsonNode again=ok(timer("start",Map.of("timeCategory","MEETING"),meeting.path("etag").asText()));
        assertThat(again.path("runningSessions")).hasSize(1);
        JsonNode stopped=ok(mutate("POST","/api/v1/me/time-tracker/stop-all",member,"",again.path("etag").asText()));
        assertThat(stopped.path("resumeCategories").toString()).isEqualTo("[\"MEETING\"]");
        assertThat(stopped.path("resumeItems")).isEmpty();
        JsonNode mixed=ok(timer("start",Map.of("workItemIds",List.of(target.itemId()),"timeCategories",List.of("MEETING","LEARNING")),
                stopped.path("etag").asText()));
        assertThat(mixed.path("runningSessions")).hasSize(3);
        assertThat(mixed.path("runningSessions").toString()).contains("\"LEARNING\"").contains(target.itemId().toString());
        var categories=java.util.stream.IntStream.range(0,11).mapToObj(i -> "MEETING").toList();
        var tooMany=timer("start",Map.of("timeCategories",categories),mixed.path("etag").asText());
        assertThat(tooMany.statusCode()).as(tooMany.body()).isIn(400,422);
        assertThat(audits("TIME_TRACKING_STARTED")).isGreaterThanOrEqualTo(4);
    }

    @Test
    void categoryFillReturnsItsLinkedProjectInMySessions() throws Exception {
        String body=json.writeValueAsString(Map.of("mode","PARALLEL","entries",List.of(Map.of(
                "timeCategory","MEETING","categoryProjectId",source.id(),
                "startedAt","2026-09-01T09:00:00Z","stoppedAt","2026-09-01T09:30:00Z"))));
        JsonNode filled=ok(mutate("POST","/api/v1/me/time-sessions/fill",member,body,null));
        assertThat(filled.path("createdCount").asInt()).isOne();
        JsonNode rows=ok(get("/api/v1/me/time-sessions?from=2026-09-01T08:00:00Z&to=2026-09-01T12:00:00Z",member)).path("items");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("projectName").asText()).isEqualTo("原计时项目");
        assertThat(rows.get(0).path("session").path("categoryProjectId").asText()).isEqualTo(source.id().toString());
    }

    @Test
    void categoryUnderAnotherProjectNeverOverlapsItsSlot() throws Exception {
        String path="/api/v1/me/time-sessions/fill";
        var linked=Map.of("timeCategory","MEETING","categoryProjectId",source.id(),
                "startedAt","2026-09-01T09:00:00Z","stoppedAt","2026-09-01T10:00:00Z");
        var unlinked=Map.of("timeCategory","MEETING",
                "startedAt","2026-09-01T09:30:00Z","stoppedAt","2026-09-01T10:30:00Z");
        ok(mutate("POST",path,member,json.writeValueAsString(Map.of("mode","PARALLEL","entries",List.of(linked))),null));
        JsonNode result=ok(mutate("POST",path,member,
                json.writeValueAsString(Map.of("mode","PARALLEL","entries",List.of(unlinked))),null));
        assertThat(result.path("createdCount").asInt()).isOne();
        assertThat(result.path("skippedDurationMs").asLong()).isEqualTo(30*60000L);
        var rejected=mutate("POST",path,member,
                json.writeValueAsString(Map.of("mode","PARALLEL","entries",List.of(linked,unlinked))),null);
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(422);
        assertThat(rejected.body()).contains("CATEGORY_OVERLAP");
    }

    @Test
    void categoryRecordsAreFilledEditedReassignedAndDeletedByTheirOwnerOnly() throws Exception {
        String fill="/api/v1/me/time-sessions/fill";
        JsonNode created=ok(mutate("POST",fill,member,categoryFillBody("GAPS","MEETING","09:00","10:00"),null));
        assertThat(created.path("createdCount").asInt()).isOne();
        JsonNode overlap=ok(mutate("POST",fill,member,categoryFillBody("PARALLEL","MEETING","09:30","11:00"),null));
        assertThat(overlap.path("skippedDurationMs").asLong()).isEqualTo(1800000);
        String day="/api/v1/me/time-sessions?from=2026-09-01T08:00:00Z&to=2026-09-01T12:00:00Z";
        JsonNode rows=ok(get(day,member)).path("items");
        assertThat(rows).hasSize(2);
        JsonNode first=rows.get(0).path("session");
        assertThat(first.path("timeCategory").asText()).isEqualTo("MEETING");
        assertThat(first.path("canEdit").asBoolean()).isTrue();
        assertThat(rows.get(0).path("title").isNull()).isTrue();
        String own="/api/v1/me/time-sessions/"+first.path("id").asText();
        String edit=json.writeValueAsString(Map.of("startedAt","2026-09-01T08:30:00Z","stoppedAt","2026-09-01T10:00:00Z"));
        assertThat(mutate("PATCH",own,member,edit,null).statusCode()).isEqualTo(428);
        assertThat(mutate("PATCH",own,member,edit,"\"9\"").statusCode()).isEqualTo(412);
        assertThat(mutate("PATCH",own,owner,edit,first.path("etag").asText()).statusCode()).isEqualTo(404);
        JsonNode edited=ok(mutate("PATCH",own,member,edit,first.path("etag").asText()));
        assertThat(edited.path("startedAt").asText()).isEqualTo("2026-09-01T08:30:00Z");
        var overlapping=mutate("PATCH",own,member,json.writeValueAsString(Map.of("startedAt","2026-09-01T08:30:00Z",
                "stoppedAt","2026-09-01T10:30:00Z")),edited.path("etag").asText());
        assertThat(overlapping.statusCode()).as(overlapping.body()).isEqualTo(422);
        assertThat(overlapping.body()).contains("TIME_OVERLAP");

        String reassign=own+"/reassign";
        var unchanged=mutate("POST",reassign,member,json.writeValueAsString(Map.of("timeCategory","MEETING")),edited.path("etag").asText());
        assertThat(unchanged.statusCode()).as(unchanged.body()).isEqualTo(422);
        JsonNode toItem=ok(mutate("POST",reassign,member,json.writeValueAsString(Map.of("workItemId",source.itemId())),edited.path("etag").asText()));
        assertThat(toItem.path("deletedCount").asInt()).isOne();
        assertThat(toItem.path("createdCount").asInt()).isOne();
        JsonNode moved=ok(get("/api/v1/work-items/"+source.itemId()+"/time-sessions",member)).path("items").get(0);
        JsonNode back=ok(mutate("POST","/api/v1/me/time-sessions/"+moved.path("id").asText()+"/reassign",member,
                json.writeValueAsString(Map.of("timeCategory","LEARNING")),moved.path("etag").asText()));
        assertThat(back.path("createdCount").asInt()).isOne();
        assertThat(ok(get("/api/v1/work-items/"+source.itemId()+"/time-sessions",member)).path("items")).isEmpty();
        JsonNode learning=null;
        for(JsonNode row:ok(get(day,member)).path("items"))
            if(row.path("session").path("timeCategory").asText().equals("LEARNING")) learning=row.path("session");
        assertThat(learning).isNotNull();
        JsonNode deleted=ok(mutate("DELETE","/api/v1/me/time-sessions/"+learning.path("id").asText(),member,"{}",learning.path("etag").asText()));
        assertThat(deleted.path("deleted").asBoolean()).isTrue();
        assertThat(ok(get(day,member)).path("items")).hasSize(1);
    }

    @Test
    void fillRejectsEntriesWithBothOrNeitherSubjectAndTheSchemaRejectsMixedRows() throws Exception {
        for(Map<String,Object> entry:List.<Map<String,Object>>of(
                Map.of("startedAt","2026-09-01T09:00:00Z","stoppedAt","2026-09-01T10:00:00Z"),
                Map.of("workItemId",source.itemId(),"timeCategory","MEETING","startedAt","2026-09-01T09:00:00Z","stoppedAt","2026-09-01T10:00:00Z"))) {
            var rejected=mutate("POST","/api/v1/me/time-sessions/fill",member,
                    json.writeValueAsString(Map.of("mode","GAPS","entries",List.of(entry))),null);
            assertThat(rejected.statusCode()).as(rejected.body()).isIn(400,422);
        }
        for(String values:List.of(":project,:item,'MEETING'","NULL,NULL,NULL",":project,NULL,'MEETING'","NULL,NULL,'OTHER'")) {
            String insert="INSERT INTO yumpoo.work_item_time_session(id,company_id,project_id,work_item_id,time_category,user_id,started_at,stopped_at,source) "
                    +"VALUES (:id,:company,"+values+",:user,transaction_timestamp()-interval '1 hour',transaction_timestamp(),'MANUAL')";
            var statement=jdbc.sql(insert).param("id",UUID.randomUUID()).param("company",COMPANY).param("user",member.userId());
            if(values.contains(":project")) statement.param("project",source.id());
            if(values.contains(":item")) statement.param("item",source.itemId());
            assertThat(org.assertj.core.api.Assertions.catchThrowable(statement::update)).as(values).isNotNull();
        }
    }

    @Test
    void personalCandidatesIncludeItemsTrackedOnlyByManualRecords() throws Exception {
        UUID manualOnly=UUID.fromString(createItem(source.id(),"只手动补录").path("id").asText());
        String candidates="/api/v1/me/time-tracker/candidates?scope=PERSONAL";
        assertThat(ok(get(candidates,member)).path("items").toString()).doesNotContain(manualOnly.toString());
        ok(mutate("POST","/api/v1/me/time-sessions/fill",member,fillBody("GAPS",manualOnly,"09:00","10:00"),null));
        JsonNode first=ok(get(candidates,member)).path("items").get(0);
        assertThat(first.path("workItemId").asText()).isEqualTo(manualOnly.toString());
        assertThat(first.path("lastTrackedAt").asText()).isEqualTo("2026-09-01T09:00:00Z");
    }

    @Test
    void candidatesCarryProjectStatusNamesAndColorsIncludingCustomStatuses() throws Exception {
        UUID item=UUID.fromString(createItem(source.id(),"status-label-check").path("id").asText());
        String path="/api/v1/me/time-tracker/candidates?scope=ALL&q=status-label-check";
        JsonNode initial=ok(get(path,member)).path("items").get(0);
        assertThat(initial.path("statusCode").asText()).isEqualTo("NOT_STARTED");
        assertThat(initial.path("statusName").asText()).isEqualTo("未开始");
        assertThat(initial.path("statusColorToken").asText()).isNotBlank();
        jdbc.sql("""
            INSERT INTO yumpoo.project_work_item_status_label
              (company_id,project_id,status_code,display_name,color_token,status_category,sort_order)
            SELECT :company,:project,'PLANNED','已排期','BRIGHT_BLUE','TODO',COALESCE(MAX(sort_order),0)+1
            FROM yumpoo.project_work_item_status_label WHERE project_id=:project
            """).param("company",COMPANY).param("project",source.id()).update();
        jdbc.sql("UPDATE yumpoo.work_item SET status_code='PLANNED',status_category='TODO' WHERE id=:id")
                .param("id",item).update();
        JsonNode custom=ok(get(path,member)).path("items").get(0);
        assertThat(custom.path("statusCategory").asText()).isEqualTo("TODO");
        assertThat(custom.path("statusCode").asText()).isEqualTo("PLANNED");
        assertThat(custom.path("statusName").asText()).isEqualTo("已排期");
        assertThat(custom.path("statusColorToken").asText()).isEqualTo("BRIGHT_BLUE");
        jdbc.sql("UPDATE yumpoo.project_work_item_status_label SET display_name='等待评审',color_token='PURPLE',active=false WHERE project_id=:project AND status_code='PLANNED'")
                .param("project",source.id()).update();
        JsonNode renamed=ok(get(path,member)).path("items").get(0);
        assertThat(renamed.path("statusName").asText()).isEqualTo("等待评审");
        assertThat(renamed.path("statusColorToken").asText()).isEqualTo("PURPLE");
    }

    @Test
    void candidatesMarkCompletionByThePersonalProjectDefinition() throws Exception {
        createItem(source.id(),"completion-check");
        String candidates="/api/v1/me/time-tracker/candidates?scope=ALL&q=completion-check";
        assertThat(ok(get(candidates,member)).path("items").get(0).path("completed").asBoolean()).isFalse();
        String settings="""
            {"pinnedColumnCount":0,"headerHeight":"SINGLE","rowHeight":"SINGLE","coloringRules":[],
             "defaultValues":{"assigneeUserIds":[],"statusCode":null,"priority":null,"contentId":null,"dueDateOffsetDays":null},
             "completedStatusCodes":["NOT_STARTED"]}
            """;
        ok(mutate("PUT","/api/v1/me/projects/"+source.id()+"/work-item-table-settings",member,settings,"\"0\""));
        assertThat(ok(get(candidates,member)).path("items").get(0).path("completed").asBoolean()).isTrue();
        assertThat(ok(get(candidates,owner)).path("items").get(0).path("completed").asBoolean()).isFalse();
    }

    @Test
    void candidateProjectCountsIncludeUntrackedAssignmentsBeyondTheFirstPage() throws Exception {
        for(int i=0;i<26;i++) {
            UUID item=UUID.fromString(createItem(source.id(),"已计时 "+i).path("id").asText());
            ok(mutate("POST","/api/v1/me/time-sessions/fill",member,fillBody("PARALLEL",item,"09:00","10:00"),null));
        }
        UUID assigned=UUID.fromString(createItem(target.id(),"从未计时的分配项",List.of(member.userId())).path("id").asText());
        jdbc.sql("UPDATE yumpoo.work_item SET status_category='IN_PROGRESS',status_code='IN_PROGRESS' WHERE id=:id")
                .param("id",assigned).update();
        String path="/api/v1/me/time-tracker/candidates";
        JsonNode first=ok(get(path,member));
        assertThat(first.path("items").size()).isEqualTo(25);
        assertThat(first.path("items").toString()).doesNotContain(assigned.toString());
        assertThat(first.path("projects").size()).isEqualTo(2);
        assertThat(first.path("projects").get(1).path("projectId").asText()).isEqualTo(target.id().toString());
        assertThat(first.path("projects").get(1).path("count").asInt()).isOne();
        JsonNode filtered=ok(get(path+"?projectId="+target.id(),member));
        assertThat(filtered.path("items").size()).isOne();
        assertThat(filtered.path("items").get(0).path("workItemId").asText()).isEqualTo(assigned.toString());
        assertThat(filtered.path("projects")).isEqualTo(first.path("projects"));
        assertThat(filtered.path("hiddenCompletedCount").asInt()).isZero();
    }

    @Test
    void candidateCompletionFilteringUsesPersonalDefinitionAndOnlyFirstPageHasCounts() throws Exception {
        createItem(source.id(),"按本人定义完成");
        String settings="""
            {"pinnedColumnCount":0,"headerHeight":"SINGLE","rowHeight":"SINGLE","coloringRules":[],
             "defaultValues":{"assigneeUserIds":[],"statusCode":null,"priority":null,"contentId":null,"dueDateOffsetDays":null},
             "completedStatusCodes":["NOT_STARTED"]}
            """;
        ok(mutate("PUT","/api/v1/me/projects/"+source.id()+"/work-item-table-settings",member,settings,"\"0\""));
        String path="/api/v1/me/time-tracker/candidates?scope=ALL&excludeCompleted=true";
        JsonNode filtered=ok(get(path,member));
        assertThat(filtered.path("items").size()).isOne();
        assertThat(filtered.path("items").get(0).path("projectId").asText()).isEqualTo(target.id().toString());
        assertThat(filtered.path("hiddenCompletedCount").asInt()).isEqualTo(2);
        assertThat(filtered.path("projects").size()).isOne();
        JsonNode project=ok(get(path+"&projectId="+target.id(),member));
        assertThat(project.path("hiddenCompletedCount").asInt()).isZero();
        JsonNode ownerPage=ok(get(path,owner));
        assertThat(ownerPage.path("items").size()).isEqualTo(3);
        assertThat(ownerPage.path("hiddenCompletedCount").asInt()).isZero();
        JsonNode later=ok(get(path+"&offset=25",member));
        assertThat(later.has("projects")).isFalse();
        assertThat(later.has("hiddenCompletedCount")).isFalse();
    }

    @Test
    void candidateAssignmentFilterRunsBeforePagination() throws Exception {
        UUID assigned=UUID.fromString(createItem(target.id(),"分配给本人",List.of(member.userId())).path("id").asText());
        JsonNode page=ok(get("/api/v1/me/time-tracker/candidates?scope=ALL&assignedToMe=true",member));
        assertThat(page.path("items").size()).isOne();
        assertThat(page.path("items").get(0).path("workItemId").asText()).isEqualTo(assigned.toString());
        assertThat(page.path("projects").size()).isOne();
        assertThat(page.path("projects").get(0).path("count").asInt()).isOne();
    }

    @Test
    void inaccessibleCandidateProjectReturnsEmptyItemsButKeepsAccessibleCounts() throws Exception {
        jdbc.sql("DELETE FROM yumpoo.project_membership WHERE project_id=:project AND user_id=:user")
                .param("project",source.id()).param("user",member.userId()).update();
        JsonNode page=ok(get("/api/v1/me/time-tracker/candidates?scope=ALL&projectId="+source.id(),member));
        assertThat(page.path("items").isEmpty()).isTrue();
        assertThat(page.path("nextOffset").isNull()).isTrue();
        assertThat(page.path("projects").size()).isOne();
        assertThat(page.path("projects").get(0).path("projectId").asText()).isEqualTo(target.id().toString());
    }

    @Test
    void candidatesRejectMoreThanTwentyProjectFilters() throws Exception {
        String query=java.util.stream.IntStream.range(0,21).mapToObj(i -> "projectId="+UUID.randomUUID())
                .collect(java.util.stream.Collectors.joining("&"));
        var response=get("/api/v1/me/time-tracker/candidates?"+query,member);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
        assertThat(response.body()).contains("INVALID_SIZE","projectId");
    }

    private String categoryFillBody(String mode,String category,String start,String stop) {
        return json.writeValueAsString(Map.of("mode",mode,"entries",List.of(Map.of("timeCategory",category,
                "startedAt","2026-09-01T"+start+":00Z","stoppedAt","2026-09-01T"+stop+":00Z"))));
    }

    private String fillBody(String mode,UUID item,String start,String stop) {
        return json.writeValueAsString(Map.of("mode",mode,"entries",List.of(Map.of("workItemId",item,
                "startedAt","2026-09-01T"+start+":00Z","stoppedAt","2026-09-01T"+stop+":00Z"))));
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
        return new Project(id, created.path("code").asText(), UUID.fromString(createItem(id,name+"工作项").path("id").asText()));
    }

    private JsonNode createItem(UUID projectId,String title) throws Exception {
        return createItem(projectId,title,List.of());
    }

    private JsonNode createItem(UUID projectId,String title,List<UUID> assignees) throws Exception {
        UUID content = jdbc.sql("SELECT id FROM yumpoo.content WHERE project_id=:project AND code='TASKS'")
                .param("project", projectId).query(UUID.class).single();
        var itemBody = json.createObjectNode().put("contentId", content.toString()).put("title", title);
        itemBody.putNull("priority").putNull("description").putNull("notes");
        var assigned=itemBody.putArray("assigneeUserIds");
        assignees.forEach(id -> assigned.add(id.toString()));
        var item = mutate("POST", "/api/v1/projects/" + projectId + "/work-items", member,
                json.writeValueAsString(itemBody), null);
        assertThat(item.statusCode()).as(item.body()).isEqualTo(201);
        return json.readTree(item.body());
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
    private long allEvents() {
        return jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE company_id=:company").param("company", COMPANY).query(Long.class).single();
    }
    private long audits(String action) {
        return jdbc.sql("SELECT count(*) FROM yumpoo.security_audit_event WHERE company_id=:company AND action=:action")
                .param("company", COMPANY).param("action", action).query(Long.class).single();
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
        return mutate(method,path,actor,body,etag,UUID.randomUUID());
    }
    private HttpResponse<String> mutate(String method, String path, Actor actor, String body, String etag,UUID key) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30))
                .header("Cookie", cookies(actor)).header("X-XSRF-TOKEN", actor.session().csrfCredential().value())
                .header("Idempotency-Key", key.toString());
        if (etag != null) request.header("If-Match", etag);
        if (!body.isEmpty()) request.header("Content-Type", "application/json");
        return http.send(request.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private static String cookies(Actor actor) {
        return "__Host-yumpoo-session=" + actor.session().sessionCredential().value() + "; __Host-yumpoo-csrf=" + actor.session().csrfCredential().value();
    }
}
