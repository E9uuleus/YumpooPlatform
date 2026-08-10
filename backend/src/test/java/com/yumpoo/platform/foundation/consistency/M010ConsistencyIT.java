package com.yumpoo.platform.foundation.consistency;

import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.foundation.testing.M010ProbeApplicationService;
import com.yumpoo.platform.foundation.testing.M010ProbeController;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({
        PostgreSqlTestContainerConfiguration.class,
        M010ProbeApplicationService.class,
        M010ProbeController.class
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql(
        scripts = "/sql/m0-10-probe-schema.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS
)
@Sql(
        scripts = "/sql/m0-10-probe-drop.sql",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS
)
class M010ConsistencyIT {

    private static final String PROBE_PATH = "/api/v1/__test/m0-10/probes";
    private static final UUID OTHER_ACTOR_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000011"
    );

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdempotencyRequestHasher requestHasher;

    @Autowired
    private M010ProbeApplicationService applicationService;

    @BeforeEach
    void resetProbeFacts() {
        jdbcClient.sql("TRUNCATE TABLE yumpoo.m010_probe, yumpoo.idempotency_record")
                .update();
    }

    @Test
    void sameKeyAndBodyReplayThePersistedResultWhileDifferentBodyConflicts() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();

        HttpResponse<String> first = postProbe(idempotencyKey, "alpha");
        HttpResponse<String> replay = postProbe(idempotencyKey, "alpha");
        HttpResponse<String> reused = postProbe(idempotencyKey, "beta");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(readJson(replay.body())).isEqualTo(readJson(first.body()));
        assertThat(replay.headers().firstValue(HttpHeaders.ETAG))
                .contains(first.headers().firstValue(HttpHeaders.ETAG).orElseThrow());
        assertError(reused, 409, StandardErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(probeCount()).isOne();
        assertThat(idempotencyRecordCount()).isOne();
    }

    @Test
    void twentyConcurrentRetriesCreateOneFactAndReturnOneSemanticResult() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();

        List<HttpResponse<String>> responses = runConcurrently(
                20,
                () -> postProbe(idempotencyKey, "concurrent")
        );

        assertThat(responses).allSatisfy(response ->
                assertThat(response.statusCode()).isEqualTo(201)
        );
        JsonNode expectedBody = readJson(responses.getFirst().body());
        assertThat(responses).allSatisfy(response -> {
            try {
                assertThat(readJson(response.body())).isEqualTo(expectedBody);
                assertThat(response.headers().firstValue(HttpHeaders.ETAG)).contains("\"0\"");
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
        assertThat(probeCount()).isOne();
        assertThat(idempotencyRecordCount()).isOne();
    }

    @Test
    void twoClientsUpdatingTheSameVersionAllowExactlyOneWinner() throws Exception {
        HttpResponse<String> created = postProbe(UUID.randomUUID(), "before");
        UUID probeId = UUID.fromString(readJson(created.body()).get("id").stringValue());
        AtomicInteger sequence = new AtomicInteger();

        List<HttpResponse<String>> responses = runConcurrently(
                2,
                () -> patchProbe(
                        probeId,
                        IfMatchParser.format(0),
                        "candidate-" + sequence.incrementAndGet()
                )
        );

        assertThat(responses.stream().map(HttpResponse::statusCode).toList())
                .containsExactlyInAnyOrder(200, 412);
        HttpResponse<String> conflict = responses.stream()
                .filter(response -> response.statusCode() == 412)
                .findFirst()
                .orElseThrow();
        assertError(conflict, 412, StandardErrorCode.VERSION_CONFLICT);

        M010ProbeApplicationService.ProbeResponse stored = applicationService.findVisible(
                M010ProbeController.FIXED_ACTOR_ID,
                probeId
        ).orElseThrow();
        assertThat(stored.rowVersion()).isOne();
        assertThat(stored.name()).isIn("candidate-1", "candidate-2");
    }

    @Test
    void hiddenResourceWinsBeforePreconditionChecksAndVisibleFailuresStayDistinct() throws Exception {
        UUID hiddenId = UUID.randomUUID();
        applicationService.insertDirect(OTHER_ACTOR_ID, hiddenId, "hidden", "ACTIVE", 0);

        HttpResponse<String> hiddenWithoutIfMatch = patchProbe(hiddenId, null, "changed");

        assertError(hiddenWithoutIfMatch, 404, StandardErrorCode.RESOURCE_NOT_FOUND);

        UUID visibleId = UUID.randomUUID();
        applicationService.insertDirect(
                M010ProbeController.FIXED_ACTOR_ID,
                visibleId,
                "visible",
                "ACTIVE",
                0
        );
        assertError(
                patchProbe(visibleId, null, "changed"),
                428,
                StandardErrorCode.PRECONDITION_REQUIRED
        );
        assertError(
                patchProbe(visibleId, "W/\"0\"", "changed"),
                400,
                StandardErrorCode.MALFORMED_REQUEST
        );

        UUID inactiveId = UUID.randomUUID();
        applicationService.insertDirect(
                M010ProbeController.FIXED_ACTOR_ID,
                inactiveId,
                "inactive",
                "ARCHIVED",
                0
        );
        assertError(
                patchProbe(inactiveId, IfMatchParser.format(0), "changed"),
                409,
                StandardErrorCode.INVALID_STATE_TRANSITION
        );
    }

    @Test
    void rollbackRemovesBothTheClaimAndTheBusinessFactSoTheKeyCanRetry() {
        UUID idempotencyKey = UUID.randomUUID();
        RequestHash requestHash = requestHashFor("rollback");

        assertThatThrownBy(() -> applicationService.createThenFail(
                M010ProbeController.FIXED_ACTOR_ID,
                idempotencyKey,
                requestHash,
                "rollback"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("forced M0-10 transaction rollback");

        assertThat(probeCount()).isZero();
        assertThat(idempotencyRecordCount()).isZero();

        IdempotencyExecutionResult retry = applicationService.create(
                M010ProbeController.FIXED_ACTOR_ID,
                idempotencyKey,
                requestHash,
                "rollback"
        );
        assertThat(retry.replayed()).isFalse();
        assertThat(probeCount()).isOne();
        assertThat(idempotencyRecordCount()).isOne();
    }

    @Test
    void aCommittedProcessingClaimReturnsRetryableConflictWithRetryAfter() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();
        RequestHash requestHash = requestHashFor("processing");
        jdbcClient.sql("""
                        INSERT INTO yumpoo.idempotency_record (
                            id, actor_user_id, http_method, route_key, idempotency_key,
                            request_hash, state, lease_until, created_at, expires_at
                        ) VALUES (
                            :id, :actorUserId, 'POST', :routeKey, :idempotencyKey,
                            :requestHash, 'PROCESSING', CURRENT_TIMESTAMP + INTERVAL '1 minute',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '7 days'
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("actorUserId", M010ProbeController.FIXED_ACTOR_ID)
                .param("routeKey", M010ProbeApplicationService.CREATE_ROUTE_KEY)
                .param("idempotencyKey", idempotencyKey)
                .param("requestHash", requestHash.value())
                .update();

        HttpResponse<String> response = postProbe(idempotencyKey, "processing");

        assertError(response, 409, StandardErrorCode.REQUEST_IN_PROGRESS);
        assertThat(response.headers().firstValue(HttpHeaders.RETRY_AFTER)).contains("1");
        assertThat(probeCount()).isZero();
    }

    @Test
    void databaseConstraintMatchesTheStrongNonNegativeLongEtagBoundary() {
        assertThat(insertCompletedRecordWithEtag("\"9223372036854775807\""))
                .isOne();
        assertThat(idempotencyRecordCount()).isOne();

        assertStoredEtagRejected("W/\"0\"");
        assertStoredEtagRejected("\"9223372036854775808\"");
        assertThat(idempotencyRecordCount()).isOne();
    }

    private void assertStoredEtagRejected(String responseEtag) {
        assertThatThrownBy(() -> insertCompletedRecordWithEtag(responseEtag))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_idempotency_record_response_etag");
    }

    private int insertCompletedRecordWithEtag(String responseEtag) {
        return jdbcClient.sql("""
                        INSERT INTO yumpoo.idempotency_record (
                            id, actor_user_id, http_method, route_key, idempotency_key,
                            request_hash, state, http_status, response_json, response_etag,
                            created_at, completed_at, expires_at
                        ) VALUES (
                            :id, :actorUserId, 'POST', :routeKey, :idempotencyKey,
                            :requestHash, 'COMPLETED', 200, CAST('{}' AS jsonb), :responseEtag,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                            CURRENT_TIMESTAMP + INTERVAL '7 days'
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("actorUserId", M010ProbeController.FIXED_ACTOR_ID)
                .param("routeKey", M010ProbeApplicationService.CREATE_ROUTE_KEY)
                .param("idempotencyKey", UUID.randomUUID())
                .param("requestHash", "0".repeat(64))
                .param("responseEtag", responseEtag)
                .update();
    }

    @Test
    void theSameKeyIsIndependentAcrossActors() {
        UUID idempotencyKey = UUID.randomUUID();
        RequestHash requestHash = requestHashFor("actor-scoped");

        IdempotencyExecutionResult first = applicationService.create(
                M010ProbeController.FIXED_ACTOR_ID,
                idempotencyKey,
                requestHash,
                "actor-scoped"
        );
        IdempotencyExecutionResult second = applicationService.create(
                OTHER_ACTOR_ID,
                idempotencyKey,
                requestHash,
                "actor-scoped"
        );

        assertThat(first.result().resourceId()).isNotEqualTo(second.result().resourceId());
        assertThat(probeCount()).isEqualTo(2);
        assertThat(idempotencyRecordCount()).isEqualTo(2);
    }

    private HttpResponse<String> postProbe(UUID idempotencyKey, String name) throws Exception {
        HttpRequest request = baseRequest(PROBE_PATH)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(IdempotencyKeyParser.HEADER_NAME, idempotencyKey.toString())
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of("name", name)),
                        StandardCharsets.UTF_8
                ))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> patchProbe(UUID probeId, String ifMatch, String name) throws Exception {
        HttpRequest.Builder request = baseRequest(PROBE_PATH + "/" + probeId)
                .header(HttpHeaders.CONTENT_TYPE, "application/json");
        if (ifMatch != null) {
            request.header(IfMatchParser.HEADER_NAME, ifMatch);
        }
        request.method(
                "PATCH",
                HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of("name", name)),
                        StandardCharsets.UTF_8
                )
        );
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path));
    }

    private RequestHash requestHashFor(String name) {
        return requestHasher.hash(
                M010ProbeApplicationService.CREATE_ROUTE_KEY,
                Map.of(),
                objectMapper.valueToTree(Map.of("name", name))
        );
    }

    private JsonNode readJson(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private void assertError(
            HttpResponse<String> response,
            int expectedStatus,
            StandardErrorCode expectedCode
    ) throws Exception {
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        assertThat(readJson(response.body()).get("code").stringValue())
                .isEqualTo(expectedCode.name());
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
    }

    private long probeCount() {
        return jdbcClient.sql("SELECT count(*) FROM yumpoo.m010_probe")
                .query(Long.class)
                .single();
    }

    private long idempotencyRecordCount() {
        return jdbcClient.sql("SELECT count(*) FROM yumpoo.idempotency_record")
                .query(Long.class)
                .single();
    }

    private static <T> List<T> runConcurrently(int count, Callable<T> callable) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                futures.add(executorService.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent test start timed out");
                    }
                    return callable.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executorService.shutdownNow();
            assertThat(executorService.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
