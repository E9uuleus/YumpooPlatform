package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.catalog.api.ProjectPurgeQueue;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureProvisioner;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"yumpoo.outbox.enabled=false","yumpoo.projects.deletion.purge-poll-delay=1h"})
class ProjectDeletionHttpIT {
    private static final UUID COMPANY=UUID.fromString("00000000-0000-4000-8000-000000000001");
    private record Actor(UUID userId,IssuedSession session) {}
    private final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    @LocalServerPort int port;
    @Autowired IdentityAcceptanceFixtureProvisioner users;
    @Autowired SessionService sessions;
    @Autowired ProjectPurgeQueue purge;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    private Actor owner,member,admin;
    private JsonNode project;

    @BeforeEach void fixture() throws Exception {
        jdbc.sql("TRUNCATE TABLE yumpoo.project CASCADE").update();
        jdbc.sql("DELETE FROM yumpoo.project_purge_run").update();
        jdbc.sql("DELETE FROM yumpoo.outbox_event").update();
        try(var correlation=RequestCorrelationContext.open(RequestCorrelation.root("deletion-http-"+UUID.randomUUID()))) {
            owner=actor("owner");member=actor("member");admin=actor("admin");
            jdbc.sql("""
                    INSERT INTO yumpoo.platform_role_assignment(id,company_id,user_id,role_code,scope_type,scope_id,status,
                        granted_by_actor_type,granted_by_system_code,grant_reason,granted_at)
                    VALUES (:id,:company,:user,'COMPANY_ADMIN','COMPANY',:company,'ACTIVE','SYSTEM','TEST_FIXTURE',
                        '删除HTTP测试',transaction_timestamp())
                    """).param("id",UUID.randomUUID()).param("company",COMPANY).param("user",admin.userId()).update();
            admin=new Actor(admin.userId(),sessions.issueWebSession(admin.userId(),"deletion-http"));
        }
        var created=mutate("POST","/api/v1/projects",owner,"{\"name\":\"删除测试项目\"}",null,UUID.randomUUID(),true);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        project=json.readTree(created.body());
        jdbc.sql("""
                INSERT INTO yumpoo.project_membership(id,company_id,project_id,user_id,status,joined_at,joined_by_user_id)
                VALUES (:id,:company,:project,:user,'ACTIVE',transaction_timestamp(),:owner)
                """).param("id",UUID.randomUUID()).param("company",COMPANY).param("project",id())
                .param("user",member.userId()).param("owner",owner.userId()).update();
    }

    @Test void archiveConfirmationXsrfAndStrongHeadersProtectDeletion() throws Exception {
        var active=mutate("POST",deletion(),owner,body(),etag(),UUID.randomUUID(),true);
        assertThat(active.statusCode()).isEqualTo(409);
        assertThat(active.body()).contains("PROJECT_MUST_BE_ARCHIVED");
        archive(owner);
        assertThat(mutate("POST",deletion(),owner,body(),etag(),UUID.randomUUID(),false).statusCode()).isEqualTo(403);
        assertThat(mutate("POST",deletion(),null,body(),etag(),UUID.randomUUID(),true).statusCode()).isEqualTo(401);
        assertThat(mutate("POST",deletion(),owner,body(),null,UUID.randomUUID(),true).statusCode()).isEqualTo(428);
        assertThat(mutate("POST",deletion(),owner,body(),"W/"+etag(),UUID.randomUUID(),true).statusCode()).isEqualTo(400);
        assertThat(mutate("POST",deletion(),owner,body(),etag(),null,true).statusCode()).isEqualTo(400);
        var wrong=mutate("POST",deletion(),owner,"{\"confirmationCode\":\"WRONG\"}",etag(),UUID.randomUUID(),true);
        assertThat(wrong.statusCode()).isEqualTo(422);
        assertThat(wrong.body()).contains("confirmationCode","MISMATCH");
        assertThat(mutate("POST","/api/v1/projects/"+UUID.randomUUID()+"/deletion",owner,body(),null,null,true).statusCode()).isEqualTo(404);
        assertThat(count("catalog.project_deletion_scheduled")).isZero();
    }

    @Test void replayCancelAndRestoreRespectThePersistedDeadlineAndVersion() throws Exception {
        archive(owner);
        String version=etag();UUID key=UUID.randomUUID();
        var saved=mutate("POST",deletion(),owner,body(),version,key,true);
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        var replay=mutate("POST",deletion(),owner,body(),version,key,true);
        assertThat(replay.body()).isEqualTo(saved.body());
        assertThat(replay.headers().firstValue("ETag")).isEqualTo(saved.headers().firstValue("ETag"));
        assertThat(count("catalog.project_deletion_scheduled")).isOne();
        JsonNode scheduled=json.readTree(saved.body());
        assertThat(Duration.between(Instant.parse(scheduled.path("deletion").path("requestedAt").asText()),
                Instant.parse(scheduled.path("deletion").path("purgeAfter").asText()))).isEqualTo(Duration.ofDays(30));
        JsonNode detail=ok(get("/api/v1/projects/"+id(),owner));
        assertThat(detail.path("capabilities").path("canRestore").asBoolean()).isFalse();
        assertThat(detail.path("capabilities").path("canCancelDeletion").asBoolean()).isTrue();
        assertThat(detail.path("deletion")).isEqualTo(scheduled.path("deletion"));
        var restore=mutate("POST","/api/v1/projects/"+id()+"/restore",owner,"",scheduled.path("etag").asText(),UUID.randomUUID(),true);
        assertThat(restore.statusCode()).isEqualTo(409);assertThat(restore.body()).contains("DELETION_SCHEDULED");
        var stale=mutate("DELETE",deletion(),owner,"",version,UUID.randomUUID(),true);
        assertThat(stale.statusCode()).isEqualTo(412);
        UUID cancelKey=UUID.randomUUID();
        var cancelled=mutate("DELETE",deletion(),owner,"",scheduled.path("etag").asText(),cancelKey,true);
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        assertThat(json.readTree(cancelled.body()).has("deletion")).isFalse();
        assertThat(mutate("DELETE",deletion(),owner,"",scheduled.path("etag").asText(),cancelKey,true).body()).isEqualTo(cancelled.body());
        assertThat(count("catalog.project_deletion_cancelled")).isOne();
        assertThat(ok(get("/api/v1/projects/"+id(),owner)).has("deletion")).isFalse();
        assertThat(mutate("POST","/api/v1/projects/"+id()+"/restore",owner,"",json.readTree(cancelled.body()).path("etag").asText(),UUID.randomUUID(),true).statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.security_audit_event WHERE target_id=:id AND action IN ('PROJECT_DELETION_SCHEDULED','PROJECT_DELETION_CANCELLED')")
                .param("id",id().toString()).query(Long.class).single()).isEqualTo(2);
    }

    @Test void ordinaryMembersCannotDiscoverArchivedOrScheduledDeletion() throws Exception {
        archive(owner);
        assertThat(get("/api/v1/projects/"+id(),member).statusCode()).isEqualTo(404);
        assertThat(mutate("POST",deletion(),member,body(),null,null,true).statusCode()).isEqualTo(404);
        assertThat(mutate("DELETE",deletion(),member,"",null,null,true).statusCode()).isEqualTo(404);
        assertThat(mutate("POST",deletion(),owner,body(),etag(),UUID.randomUUID(),true).statusCode()).isEqualTo(200);
        JsonNode list=ok(get("/api/v1/projects?lifecycle=ARCHIVED",member));
        assertThat(list.path("items").size()).isZero();
    }

    @Test void companyAdministratorCanArchiveScheduleAndCancelAnotherOwnersProject() throws Exception {
        archive(admin);
        var saved=mutate("POST",deletion(),admin,body(),etag(),UUID.randomUUID(),true);
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(mutate("DELETE",deletion(),admin,"",json.readTree(saved.body()).path("etag").asText(),UUID.randomUUID(),true).statusCode()).isEqualTo(200);
    }

    @Test void purgeStartHidesTheStillExistingProjectFromEveryoneAndPrecedesHeaderErrors() throws Exception {
        archive(owner);
        var saved=mutate("POST",deletion(),owner,body(),etag(),UUID.randomUUID(),true);
        assertThat(saved.statusCode()).isEqualTo(200);
        jdbc.sql("UPDATE yumpoo.project SET deletion_requested_at=transaction_timestamp()-interval '31 days',purge_after=transaction_timestamp()-interval '1 day' WHERE id=:id")
                .param("id",id()).update();
        var lease=purge.claim(Instant.now(),"http-fixture",Duration.ofMinutes(3),true).orElseThrow();
        assertThat(lease.projectId()).isEqualTo(id());
        assertThat(jdbc.sql("SELECT count(*) FROM yumpoo.project WHERE id=:id").param("id",id()).query(Long.class).single()).isOne();
        for(Actor actor:new Actor[]{owner,member,admin}) {
            assertThat(get("/api/v1/projects/"+id(),actor).statusCode()).isEqualTo(404);
            assertThat(mutate("DELETE",deletion(),actor,"",null,null,true).statusCode()).isEqualTo(404);
            assertThat(ok(get("/api/v1/projects?lifecycle=ALL",actor)).path("items").size()).isZero();
        }
    }

    private Actor actor(String role) {
        UUID user=users.provision("delete-http-"+role+"-"+UUID.randomUUID(),"删除测试 "+role).userId();
        return new Actor(user,sessions.issueWebSession(user,"deletion-http"));
    }
    private UUID id() { return UUID.fromString(project.path("id").asText()); }
    private String deletion() { return "/api/v1/projects/"+id()+"/deletion"; }
    private String etag() throws Exception { return ok(get("/api/v1/projects/"+id(),owner)).path("etag").asText(); }
    private String body() throws Exception { return json.writeValueAsString(java.util.Map.of("confirmationCode",project.path("code").asText())); }
    private void archive(Actor actor) throws Exception {
        assertThat(mutate("POST","/api/v1/projects/"+id()+"/archive",actor,"",etag(),UUID.randomUUID(),true).statusCode()).isEqualTo(200);
    }
    private long count(String type) {
        return jdbc.sql("SELECT count(*) FROM yumpoo.outbox_event WHERE aggregate_id=:id AND event_type=:type")
                .param("id",id()).param("type",type).query(Long.class).single();
    }
    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body());
    }
    private HttpResponse<String> get(String path,Actor actor) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Cookie",cookies(actor)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> mutate(String method,String path,Actor actor,String body,String match,UUID key,boolean xsrf) throws Exception {
        var request=HttpRequest.newBuilder(uri(path));
        if(actor!=null) {
            request.header("Cookie",cookies(actor));
            if(xsrf) request.header("X-XSRF-TOKEN",actor.session().csrfCredential().value());
        }
        if(match!=null) request.header("If-Match",match);
        if(key!=null) request.header("Idempotency-Key",key.toString());
        if(!body.isEmpty()) request.header("Content-Type","application/json");
        return http.send(request.method(method,body.isEmpty()?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:"+port+path); }
    private static String cookies(Actor actor) {
        return "__Host-yumpoo-session="+actor.session().sessionCredential().value()+"; __Host-yumpoo-csrf="+actor.session().csrfCredential().value();
    }
}
