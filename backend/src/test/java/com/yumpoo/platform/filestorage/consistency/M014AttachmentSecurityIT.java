package com.yumpoo.platform.filestorage.consistency;

import com.yumpoo.platform.filestorage.api.AttachmentStatus;
import com.yumpoo.platform.filestorage.application.AttachmentUploadPolicy;
import com.yumpoo.platform.filestorage.application.MalwareScanVerdict;
import com.yumpoo.platform.filestorage.testing.M014AttachmentProbeConfiguration;
import com.yumpoo.platform.filestorage.testing.M014AttachmentProbeController;
import com.yumpoo.platform.filestorage.testing.M014AttachmentProbeRepository;
import com.yumpoo.platform.filestorage.testing.M014AttachmentProbeService;
import com.yumpoo.platform.filestorage.testing.M014ControllableMalwareScanner;
import com.yumpoo.platform.filestorage.testing.M014ParentAccessResolver;
import com.yumpoo.platform.filestorage.testing.M014StorageFixture;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({
        PostgreSqlTestContainerConfiguration.class,
        M014AttachmentProbeConfiguration.class,
        M014AttachmentProbeController.class
})
@ActiveProfiles("m0-14-probe")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "yumpoo.outbox.enabled=false"
)
@Sql(
        scripts = "/sql/m0-14-probe-schema.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS
)
@Sql(
        scripts = "/sql/m0-14-probe-drop.sql",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS
)
class M014AttachmentSecurityIT {

    private static final String API = "/api/v1/__test/m0-14/attachments";
    private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000014");
    private static final UUID ACTOR = UUID.fromString("20000000-0000-0000-0000-000000000014");
    private static final UUID OTHER_ACTOR = UUID.fromString(
            "30000000-0000-0000-0000-000000000014"
    );
    private static final byte[] PDF = (
            "%PDF-1.7\n1 0 obj<</Type/Catalog>>endobj\n%%EOF\n"
    ).getBytes(StandardCharsets.US_ASCII);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private M014AttachmentProbeRepository repository;

    @Autowired
    private M014AttachmentProbeService service;

    @Autowired
    private M014ParentAccessResolver accessResolver;

    @Autowired
    private M014ControllableMalwareScanner scanner;

    @Autowired
    private M014StorageFixture storageFixture;

    @BeforeEach
    void resetProbe() throws Exception {
        scanner.reset();
        service.awaitAll(Duration.ofSeconds(5));
        repository.clearFaults();
        jdbcClient.sql("TRUNCATE TABLE yumpoo.m014_attachment_probe").update();
        accessResolver.reset();
        storageFixture.clear();
        accessResolver.grantOwner(OWNER, ACTOR);
    }

    @Test
    void scanRunsOutsideDatabaseTransactionAndOnlyAvailableContentCanBeDownloaded()
            throws Exception {
        UUID attachmentId = create("report.pdf", "application/pdf", ACTOR);
        scanner.blockWithVerdict(MalwareScanVerdict.CLEAN);

        HttpResponse<String> accepted = put(attachmentId, ACTOR, PDF);

        assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(scanner.awaitScanEntered(Duration.ofSeconds(5))).isTrue();
        assertThat(repository.find(attachmentId)).hasValueSatisfying(row -> {
            assertThat(row.status()).isEqualTo(AttachmentStatus.UPLOADING);
            assertThat(row.processingStage().name()).isEqualTo("SCANNING");
        });
        assertThat(getContent(attachmentId, ACTOR).statusCode()).isEqualTo(404);

        CompletableFuture<Void> independentWrite = CompletableFuture.runAsync(
                () -> repository.incrementObservation(attachmentId)
        );
        independentWrite.get(2, TimeUnit.SECONDS);

        scanner.release();
        service.awaitProcessing(attachmentId, Duration.ofSeconds(5));
        JsonNode available = metadata(attachmentId, ACTOR);
        assertThat(available.get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(available.get("sizeBytes").asLong()).isEqualTo(PDF.length);
        assertThat(available.toString()).doesNotContain("storageKey", "quarantine");
        assertThat(repository.find(attachmentId).orElseThrow().probeObservation()).isOne();

        HttpResponse<byte[]> download = getContent(attachmentId, ACTOR);
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(PDF);
        assertThat(download.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/pdf");
        assertThat(download.headers().firstValue("X-Content-Type-Options").orElseThrow())
                .isEqualTo("nosniff");
        assertThat(download.headers().firstValue("Cache-Control").orElseThrow())
                .contains("no-store", "private");
        assertThat(download.headers().firstValue("X-Content-SHA256").orElseThrow())
                .isEqualTo(available.get("sha256").asText());
    }

    @Test
    void declaredAndStreamedPolicyFailuresAreFailClosed() throws Exception {
        UUID oversized = create("large.pdf", "application/pdf", ACTOR);

        String response = rawPut(
                oversized,
                ACTOR,
                AttachmentUploadPolicy.MAX_BYTES + 1,
                new byte[0]
        );

        assertThat(response).startsWith("HTTP/1.1 413");
        assertThat(response).contains("FILE_TOO_LARGE");
        assertThat(repository.find(oversized)).hasValueSatisfying(row -> {
            assertThat(row.status()).isEqualTo(AttachmentStatus.REJECTED);
            assertThat(row.rejectedCode().name()).isEqualTo("FILE_TOO_LARGE");
        });

        UUID mismatch = create("image.png", "image/png", ACTOR);
        assertThat(put(mismatch, ACTOR, PDF).statusCode()).isEqualTo(202);
        service.awaitProcessing(mismatch, Duration.ofSeconds(5));

        assertThat(metadata(mismatch, ACTOR).get("rejectedCode").asText())
                .isEqualTo("FILE_TYPE_NOT_ALLOWED");
        assertThat(getContent(mismatch, ACTOR).statusCode()).isEqualTo(404);
        assertThat(storageFixture.quarantineFileCount()).isZero();
        assertThat(storageFixture.blobFileCount()).isZero();
    }

    @Test
    void interruptedBodyDeletesPartAndLeavesIntentRetryable() throws Exception {
        UUID attachmentId = create("retry.pdf", "application/pdf", ACTOR);

        rawPut(attachmentId, ACTOR, PDF.length + 17L, PDF);
        awaitRow(
                attachmentId,
                row -> row.processingStage() == null,
                Duration.ofSeconds(5)
        );

        M014AttachmentProbeRepository.ProbeRow interrupted = repository.find(attachmentId)
                .orElseThrow();
        assertThat(interrupted.status()).isEqualTo(AttachmentStatus.UPLOADING);
        assertThat(interrupted.lastFailureCode().name()).isEqualTo("UPLOAD_INCOMPLETE");
        assertThat(storageFixture.quarantineFileCount()).isZero();
        assertThat(storageFixture.blobFileCount()).isZero();

        assertThat(put(attachmentId, ACTOR, PDF).statusCode()).isEqualTo(202);
        service.awaitProcessing(attachmentId, Duration.ofSeconds(5));
        assertThat(metadata(attachmentId, ACTOR).get("status").asText())
                .isEqualTo("AVAILABLE");
    }

    @Test
    void writeRevocationDuringScanRejectsAndReadRevocationHidesAvailableBytes()
            throws Exception {
        UUID revokedDuringScan = create("revoked.pdf", "application/pdf", ACTOR);
        scanner.blockWithVerdict(MalwareScanVerdict.CLEAN);
        assertThat(put(revokedDuringScan, ACTOR, PDF).statusCode()).isEqualTo(202);
        assertThat(scanner.awaitScanEntered(Duration.ofSeconds(5))).isTrue();

        accessResolver.revokeWrite(OWNER, ACTOR);
        scanner.release();
        service.awaitProcessing(revokedDuringScan, Duration.ofSeconds(5));

        assertThat(metadata(revokedDuringScan, ACTOR).get("rejectedCode").asText())
                .isEqualTo("PARENT_NOT_WRITABLE");
        assertThat(getContent(revokedDuringScan, ACTOR).statusCode()).isEqualTo(404);
        assertThat(storageFixture.blobFileCount()).isOne();

        accessResolver.grantWrite(OWNER, ACTOR);
        scanner.returnVerdict(MalwareScanVerdict.CLEAN);
        UUID available = create("available.pdf", "application/pdf", ACTOR);
        assertThat(put(available, ACTOR, PDF).statusCode()).isEqualTo(202);
        service.awaitProcessing(available, Duration.ofSeconds(5));
        assertThat(getContent(available, ACTOR).statusCode()).isEqualTo(200);

        accessResolver.revokeRead(OWNER, ACTOR);
        assertThat(getContent(available, ACTOR).statusCode()).isEqualTo(404);
        assertThat(metadataResponse(available, ACTOR).statusCode()).isEqualTo(404);
        assertThat(getContent(available, OTHER_ACTOR).statusCode()).isEqualTo(404);
    }

    @Test
    void finalDatabaseRollbackLeavesOnlyASafeUnreferencedBlob() throws Exception {
        UUID attachmentId = create("rollback.pdf", "application/pdf", ACTOR);
        repository.forceNextFinalizeRollback();

        assertThat(put(attachmentId, ACTOR, PDF).statusCode()).isEqualTo(202);

        assertThatThrownBy(() -> service.awaitProcessing(attachmentId, Duration.ofSeconds(5)))
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(M014AttachmentProbeRepository.ForcedFinalizeRollback.class);
        assertThat(repository.find(attachmentId)).hasValueSatisfying(row -> {
            assertThat(row.status()).isEqualTo(AttachmentStatus.UPLOADING);
            assertThat(row.processingStage().name()).isEqualTo("FINALIZING");
            assertThat(row.storageKey()).isNull();
        });
        assertThat(storageFixture.blobFileCount()).isOne();
        assertThat(getContent(attachmentId, ACTOR).statusCode()).isEqualTo(404);
    }

    @Test
    void scannerAmbiguityRetriesFinitelyAndNeverPublishes() throws Exception {
        UUID attachmentId = create("ambiguous.pdf", "application/pdf", ACTOR);
        scanner.returnVerdict(MalwareScanVerdict.INDETERMINATE);

        assertThat(put(attachmentId, ACTOR, PDF).statusCode()).isEqualTo(202);
        service.awaitProcessing(attachmentId, Duration.ofSeconds(5));

        assertThat(scanner.calls()).isEqualTo(3);
        assertThat(metadata(attachmentId, ACTOR).get("rejectedCode").asText())
                .isEqualTo("SCAN_UNAVAILABLE");
        assertThat(storageFixture.quarantineFileCount()).isOne();
        assertThat(storageFixture.blobFileCount()).isZero();
        assertThat(getContent(attachmentId, ACTOR).statusCode()).isEqualTo(404);
    }

    @Test
    void concurrentContentCompletionPublishesAtMostOnce() throws Exception {
        UUID attachmentId = create("once.pdf", "application/pdf", ACTOR);
        scanner.blockWithVerdict(MalwareScanVerdict.CLEAN);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<Integer> first = concurrentPut(attachmentId, start);
        CompletableFuture<Integer> second = concurrentPut(attachmentId, start);
        start.countDown();
        List<Integer> statuses = new ArrayList<>(List.of(first.get(), second.get()));
        statuses.sort(Comparator.naturalOrder());

        assertThat(statuses).containsExactly(202, 409);
        assertThat(scanner.awaitScanEntered(Duration.ofSeconds(5))).isTrue();
        scanner.release();
        service.awaitProcessing(attachmentId, Duration.ofSeconds(5));
        assertThat(repository.find(attachmentId).orElseThrow().status())
                .isEqualTo(AttachmentStatus.AVAILABLE);
        assertThat(storageFixture.blobFileCount()).isOne();
    }

    private CompletableFuture<Integer> concurrentPut(UUID attachmentId, CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                start.await();
                return put(attachmentId, ACTOR, PDF).statusCode();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private UUID create(String fileName, String mime, UUID actorId) throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
                "ownerId", OWNER,
                "fileName", fileName,
                "declaredMime", mime
        ));
        HttpRequest request = HttpRequest.newBuilder(uri(API))
                .header("Content-Type", "application/json")
                .header(M014AttachmentProbeController.ACTOR_HEADER, actorId.toString())
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(response.body()).get("id").asText());
    }

    private HttpResponse<String> put(UUID attachmentId, UUID actorId, byte[] bytes)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(API + "/" + attachmentId + "/content"))
                .header("Content-Type", "application/octet-stream")
                .header(M014AttachmentProbeController.ACTOR_HEADER, actorId.toString())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode metadata(UUID attachmentId, UUID actorId) throws Exception {
        HttpResponse<String> response = metadataResponse(attachmentId, actorId);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> metadataResponse(UUID attachmentId, UUID actorId)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(API + "/" + attachmentId))
                .header(M014AttachmentProbeController.ACTOR_HEADER, actorId.toString())
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getContent(UUID attachmentId, UUID actorId)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        uri(API + "/" + attachmentId + "/content")
                )
                .header(M014AttachmentProbeController.ACTOR_HEADER, actorId.toString())
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private String rawPut(
            UUID attachmentId,
            UUID actorId,
            long contentLength,
            byte[] partialBody
    ) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5_000);
            String headers = "PUT " + API + "/" + attachmentId + "/content HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + M014AttachmentProbeController.ACTOR_HEADER + ": " + actorId + "\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Content-Length: " + contentLength + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(partialBody);
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = socket.getInputStream().read(buffer)) != -1) {
                response.write(buffer, 0, read);
            }
            return response.toString(StandardCharsets.UTF_8);
        }
    }

    private void awaitRow(
            UUID attachmentId,
            java.util.function.Predicate<M014AttachmentProbeRepository.ProbeRow> condition,
            Duration timeout
    ) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (repository.find(attachmentId).filter(condition).isPresent()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("M0-14 probe row did not reach the expected state");
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
