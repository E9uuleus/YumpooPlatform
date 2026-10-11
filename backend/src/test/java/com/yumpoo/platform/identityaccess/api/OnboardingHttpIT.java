package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureProvisioner;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "yumpoo.outbox.enabled=false")
class OnboardingHttpIT {
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    @LocalServerPort private int port;
    @Autowired private IdentityAcceptanceFixtureProvisioner provisioner;
    @Autowired private SessionService sessions;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper json;

    private IssuedSession session;

    @BeforeEach
    void setUp() {
        cleanUp();
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("onboarding-http-" + UUID.randomUUID()))) {
            UUID userId = provisioner.provision("onboarding-member", "Onboarding Member").userId();
            session = sessions.issueWebSession(userId, "onboarding-http");
        }
    }

    @AfterEach
    void tearDown() { cleanUp(); }

    @Test
    void newMemberStartsEmptyAndVersionsOnlyMoveForward() throws Exception {
        assertThat(ok(get()).path("tours").size()).isZero();

        JsonNode first = ok(put("welcome", 1)).path("tours").get(0);
        assertThat(first.path("tourId").asText()).isEqualTo("welcome");
        assertThat(first.path("version").asInt()).isEqualTo(1);

        JsonNode raised = ok(put("welcome", 2)).path("tours").get(0);
        assertThat(raised.path("version").asInt()).isEqualTo(2);

        JsonNode kept = ok(put("welcome", 1)).path("tours").get(0);
        assertThat(kept.path("version").asInt()).isEqualTo(2);
        assertThat(kept.path("completedAt").asText()).isEqualTo(raised.path("completedAt").asText());

        assertThat(ok(get()).path("tours").size()).isEqualTo(1);
    }

    @Test
    void rejectsMalformedTourIdAndVersion() throws Exception {
        assertThat(put("Welcome", 1).statusCode()).isEqualTo(422);
        assertThat(put("welcome", 0).statusCode()).isEqualTo(422);
    }

    private HttpResponse<String> get() throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/api/v1/me/onboarding")).header("Cookie", cookies()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String tourId, int version) throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/api/v1/me/onboarding/tours/" + tourId))
                .header("Cookie", cookies())
                .header("X-XSRF-TOKEN", session.csrfCredential().value())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"version\":" + version + "}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        return json.readTree(response.body());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private String cookies() {
        return "__Host-yumpoo-session=" + session.sessionCredential().value()
                + "; __Host-yumpoo-csrf=" + session.csrfCredential().value();
    }

    private void cleanUp() {
        jdbc.sql("DELETE FROM yumpoo.security_audit_event WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.login_session WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.outbox_event WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.platform_role_assignment WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.external_identity WHERE company_id=:id").param("id", COMPANY_ID).update();
        jdbc.sql("DELETE FROM yumpoo.identity_user WHERE company_id=:id").param("id", COMPANY_ID).update();
    }
}
