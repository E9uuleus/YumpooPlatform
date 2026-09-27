package com.yumpoo.platform.operations.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureProvisioner;
import com.yumpoo.platform.operations.application.OperationsAlertService;
import com.yumpoo.platform.operations.application.OperationsModels.Alert;
import com.yumpoo.platform.operations.application.OperationsModels.Rule;
import com.yumpoo.platform.operations.application.OperationsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = { "yumpoo.outbox.enabled=false", "yumpoo.operations.sampling-enabled=false" }
)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationsHttpIT {

    static PostgreSQLContainer postgres;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String url = System.getProperty("yumpoo.test.operations.jdbc-url");
        if (url == null) {
            postgres = new PostgreSQLContainer("postgres:17.10-alpine")
                .withDatabaseName("yumpoo_test")
                .withUsername("yumpoo_test")
                .withPassword("yumpoo_test");
            postgres.start();
            url = postgres.getJdbcUrl();
        }
        final String jdbcUrl = url;
        properties.add("spring.datasource.url", () -> jdbcUrl);
        properties.add("spring.datasource.username", () -> "yumpoo_test");
        properties.add("spring.datasource.password", () -> "yumpoo_test");
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @LocalServerPort
    int port;

    @Autowired
    IdentityAcceptanceFixtureProvisioner provisioner;

    @Autowired
    SessionService sessions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OperationsAlertService alerts;

    @Autowired
    OperationsRepository repository;

    @Autowired
    ObjectMapper json;

    @Autowired
    MeterRegistry registry;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactions;

    @Autowired
    com.yumpoo.platform.identityaccess.api.ActiveSessionQuery activeSessions;

    final HttpClient client = HttpClient.newHttpClient();
    IssuedSession manager, admin, member;

    @BeforeAll
    void fixtures() {
        try (
            var scope = RequestCorrelationContext.open(RequestCorrelation.root("operations-it-" + UUID.randomUUID()))
        ) {
            manager = actor("APP_MANAGER");
            admin = actor("COMPANY_ADMIN");
            member = actor(null);
        }
    }

    @BeforeEach
    void resetAlerts() {
        jdbc.update("DELETE FROM yumpoo.ops_alert");
        jdbc.update(
            "UPDATE yumpoo.ops_alert_rule SET enabled=true,warning_threshold=1,critical_threshold=10,for_seconds=0,row_version=row_version+1 WHERE code='OUTBOX_DEAD'"
        );
    }

    IssuedSession actor(String role) {
        UUID id = provisioner.provision("ops-" + UUID.randomUUID(), "运维测试成员").userId();
        if (role != null) jdbc.update(
            """
            INSERT INTO yumpoo.platform_role_assignment(id,company_id,user_id,role_code,scope_type,scope_id,status,granted_by_actor_type,granted_by_system_code,grant_reason,granted_at)
            VALUES(?,?,?,?,?,?,'ACTIVE','SYSTEM','OPS_TEST','operations integration test',transaction_timestamp())
            """,
            UUID.randomUUID(),
            COMPANY,
            id,
            role,
            role.equals("APP_MANAGER") ? "PLATFORM" : "COMPANY",
            COMPANY
        );
        return sessions.issueWebSession(id, "operations-test");
    }

    @Test
    void managerOnlyReadsNoStoreSessionsMetricsAndHttpObservation() throws Exception {
        for (String path : List.of(
            "/overview",
            "/host",
            "/metrics?range=1h",
            "/sessions",
            "/sessions/summary",
            "/logs?modules=&q=",
            "/logs/tail?modules=",
            "/alerts",
            "/alerts/summary",
            "/alert-rules"
        )) {
            var allowed = request("GET", path, manager, null, null, true);
            assertThat(allowed.statusCode())
                .as(path + " " + allowed.body())
                .isEqualTo(200);
            assertThat(allowed.headers().firstValue("cache-control")).contains("no-store");
            assertThat(request("GET", path, admin, null, null, true).statusCode())
                .as(path)
                .isEqualTo(403);
            assertThat(request("GET", path, member, null, null, true).statusCode())
                .as(path)
                .isEqualTo(403);
        }
        assertThat(request("GET", "/overview", null, null, null, true).statusCode()).isEqualTo(401);
        var counts = json.readTree(request("GET", "/sessions/summary", manager, null, null, true).body());
        assertThat(counts.path("activeSessions").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(request("GET", "/sessions", manager, null, null, true).body()).doesNotContain(
            manager.sessionCredential().value(),
            manager.csrfCredential().value(),
            "credentialHash"
        );
        assertThat(registry.find("http.server.requests").timers()).isNotEmpty();
        assertThat(
            registry
                .find("http.server.requests")
                .timers()
                .stream()
                .anyMatch(t -> t.takeSnapshot().percentileValues().length > 0)
        ).isTrue();
    }

    @Test
    void rulesRequireCsrfAndEtagAndConcurrentEditsHaveOneWinner() throws Exception {
        Rule rule = alerts.rule("HOST_CPU_HIGH");
        String body = "{\"enabled\":true,\"warningThreshold\":0.8,\"criticalThreshold\":0.95,\"forSeconds\":30}";
        assertThat(
            request("PUT", "/alert-rules/HOST_CPU_HIGH", manager, body, rule.etag(), false).statusCode()
        ).isEqualTo(403);
        assertThat(request("PUT", "/alert-rules/HOST_CPU_HIGH", manager, body, null, true).statusCode()).isEqualTo(428);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() ->
                request("PUT", "/alert-rules/HOST_CPU_HIGH", manager, body, rule.etag(), true).statusCode()
            );
            var second = pool.submit(() ->
                request("PUT", "/alert-rules/HOST_CPU_HIGH", manager, body, rule.etag(), true).statusCode()
            );
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 412);
        }
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM yumpoo.security_audit_event WHERE action='OPS_ALERT_RULE_UPDATED'",
                Long.class
            )
        ).isGreaterThanOrEqualTo(1);
    }

    @Test
    void acknowledgeIsIdempotentEscalationReopensAttentionAndRecoveryPersistsHistory() throws Exception {
        Instant start = Instant.now().minusSeconds(300);
        alerts.evaluate(Map.of("outbox.dead", 2d), List.of(), start);
        Alert firing = repository
            .firing()
            .stream()
            .filter(a -> a.ruleCode().equals("OUTBOX_DEAD"))
            .findFirst()
            .orElseThrow();
        assertThat(
            request(
                "POST",
                "/alerts/" + firing.id() + "/acknowledge",
                manager,
                "{\"note\":\"正在处理\"}",
                null,
                true
            ).statusCode()
        ).isEqualTo(200);
        assertThat(
            request(
                "POST",
                "/alerts/" + firing.id() + "/acknowledge",
                manager,
                "{\"note\":\"重复\"}",
                null,
                true
            ).statusCode()
        ).isEqualTo(200);
        assertThat(
            repository
                .events(firing.id())
                .stream()
                .filter(e -> e.eventType().equals("ACKNOWLEDGED"))
        ).hasSize(1);
        assertThat(alerts.summary().warning()).isZero();
        alerts.evaluate(Map.of("outbox.dead", 12d), List.of(), start.plusSeconds(15));
        assertThat(repository.findAlert(firing.id(), false).orElseThrow().acknowledgedAt()).isNull();
        assertThat(alerts.summary().critical()).isOne();
        for (int t = 30; t <= 90; t += 15) alerts.evaluate(Map.of("outbox.dead", 0d), List.of(), start.plusSeconds(t));
        assertThat(repository.findAlert(firing.id(), false).orElseThrow().status()).isEqualTo("RESOLVED");
        assertThat(alerts.summary().critical()).isZero();
        assertThat(
            json
                .readTree(request("GET", "/alerts/" + firing.id(), manager, null, null, true).body())
                .path("events")
                .size()
        ).isEqualTo(4);
    }

    @Test
    void minuteStorageIsIdempotentAndGapsAndRestartsSurviveNewQueries() throws Exception {
        Instant minute = Instant.now().minusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        UUID boot = UUID.randomUUID();
        repository.saveMinute(boot, minute, minute, Map.of("cpu.system", .5, "http.requests", 12d), 4);
        repository.saveMinute(boot, minute, minute, Map.of("cpu.system", .6, "http.requests", 15d), 4);
        assertThat(repository.metrics(minute, minute.plusSeconds(60))).hasSize(1);
        assertThat(
            repository.metrics(minute, minute.plusSeconds(60)).getFirst().values().get("http.requests")
        ).isEqualTo(15d);
        var result = json.readTree(
            request("GET", "/metrics?range=1h&keys=cpu.system,http.requests", manager, null, null, true).body()
        );
        assertThat(result.path("restarts").size()).isGreaterThanOrEqualTo(1);
        assertThat(
            result
                .path("series")
                .get(0)
                .path("values")
                .valueStream()
                .anyMatch(v -> v.isNull())
        ).isTrue();
    }

    @Test
    void rolledBackEvaluationDoesNotPublishPhantomAlerts() {
        alerts.evaluate(Map.of(), List.of(), Instant.now());
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            alerts.evaluate(Map.of("outbox.dead", 2d), List.of(), Instant.now());
            assertThat(repository.firing()).hasSize(1);
            status.setRollbackOnly();
        });
        assertThat(repository.firing()).isEmpty();
        assertThat(alerts.cached()).isEmpty();
    }

    @Test
    void failedAlertCommitDoesNotConsumeThePendingWindow() {
        jdbc.update(
            "UPDATE yumpoo.ops_alert_rule SET for_seconds=30,row_version=row_version+1 WHERE code='OUTBOX_DEAD'"
        );
        Instant start = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        alerts.evaluate(Map.of("outbox.dead", 2d), List.of(), start);
        alerts.evaluate(Map.of("outbox.dead", 2d), List.of(), start.plusSeconds(15));
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            alerts.evaluate(Map.of("outbox.dead", 2d), List.of(), start.plusSeconds(30));
            assertThat(repository.firing()).hasSize(1);
            status.setRollbackOnly();
        });
        assertThat(repository.firing()).isEmpty();
        alerts.evaluate(Map.of("outbox.dead", 2d), List.of(), start.plusSeconds(30));
        assertThat(repository.firing())
            .singleElement()
            .satisfies(alert -> assertThat(alert.startedAt()).isEqualTo(start.plusSeconds(30)));
    }

    @Test
    void disablingRuleResolvesImmediatelyAndDeniesCompanyAdministratorMutation() throws Exception {
        alerts.evaluate(Map.of("outbox.dead", 2d), List.of(), Instant.now());
        Rule rule = alerts.rule("OUTBOX_DEAD");
        String body = "{\"enabled\":false,\"warningThreshold\":1,\"criticalThreshold\":10,\"forSeconds\":0}";
        assertThat(request("PUT", "/alert-rules/OUTBOX_DEAD", admin, body, rule.etag(), true).statusCode()).isEqualTo(
            403
        );
        assertThat(request("PUT", "/alert-rules/OUTBOX_DEAD", manager, body, rule.etag(), true).statusCode()).isEqualTo(
            200
        );
        assertThat(repository.firing()).isEmpty();
        assertThat(alerts.cached()).isEmpty();
        assertThat(repository.alerts("RESOLVED", null, 0, 20).items()).anyMatch(a ->
            a.resolution().equals("RULE_DISABLED")
        );
    }

    @Test
    void sessionDetailsAreScopedInTheRepositoryWhileSamplingOnlyCounts() {
        assertThat(activeSessions.findActive(UUID.randomUUID(), Instant.now())).isEmpty();
        assertThat(activeSessions.findActive(COMPANY, Instant.now()))
            .isNotEmpty()
            .allMatch(session -> session.companyId().equals(COMPANY));
        assertThat(activeSessions.countActive(Instant.now()).online()).isGreaterThanOrEqualTo(3);
    }

    HttpResponse<String> request(
        String method,
        String suffix,
        IssuedSession actor,
        String body,
        String etag,
        boolean csrf
    ) throws Exception {
        var builder = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + port + "/api/v1/admin/operations" + suffix)
        ).timeout(Duration.ofSeconds(20));
        if (actor != null) {
            builder.header(
                "Cookie",
                "__Host-yumpoo-session=" +
                    actor.sessionCredential().value() +
                    "; __Host-yumpoo-csrf=" +
                    actor.csrfCredential().value()
            );
            if (csrf) builder.header("X-XSRF-TOKEN", actor.csrfCredential().value());
        }
        if (etag != null) builder.header("If-Match", etag);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(
            builder
                .method(
                    method,
                    body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)
                )
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }
}
