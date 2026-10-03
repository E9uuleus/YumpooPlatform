package com.yumpoo.platform.foundation.infrastructure.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.yumpoo.platform.foundation.application.logging.LogQueryPort;
import com.yumpoo.platform.foundation.application.logging.LogRecord;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class OperationsLoggingTest {

    @TempDir
    Path directory;

    private final LogQueryPort.Filter filter = new LogQueryPort.Filter(null, Set.of(), null, null, null, null);

    private LoggingEvent event(String text, Throwable cause) {
        Logger logger = (Logger) LoggerFactory.getLogger("com.yumpoo.platform.operations.LogTest");
        LoggingEvent event = new LoggingEvent(getClass().getName(), logger, Level.INFO, text, cause, null);
        event.setMDCPropertyMap(
            Map.of(
                "requestId",
                "request-123",
                "correlationId",
                "request-123",
                "userId",
                "12345678-1234-4123-8123-123456789012"
            )
        );
        return event;
    }

    @Test
    void sharesTypedSanitizedFieldsAcrossJsonConsoleAndBuffer() {
        var event = event(
            "中文 token=do-not-store email=person@example.test\nCookie: sid=secret; csrf=also-secret",
            new IllegalStateException("password=hunter2")
        );
        event.addKeyValuePair(new KeyValuePair("event", "operations.test"));
        event.addKeyValuePair(new KeyValuePair("status", 503));
        event.addKeyValuePair(new KeyValuePair("access_token", 123456));
        event.addKeyValuePair(new KeyValuePair("msg", "must-not-replace"));
        String json = new YumpooJsonLogFormatter().format(event);
        var parsed = JsonMapper.builder().build().readTree(json);
        assertThat(parsed.path("schema").asText()).isEqualTo("yumpoo-log/1");
        assertThat(parsed.path("status").isNumber()).isTrue();
        assertThat(parsed.path("access_token").asText()).isEqualTo("***");
        assertThat(parsed.has("correlationId")).isFalse();
        assertThat(parsed.path("ctx_msg").asText()).isEqualTo("must-not-replace");
        assertThat(json)
            .contains("中文")
            .doesNotContain("do-not-store", "hunter2", "sid=secret", "also-secret", "person@");
        assertThat(json.lines()).hasSize(1);
        String console = new YumpooConsoleLogFormatter().format(event);
        assertThat(console)
            .contains("INFO", "operations", "req=request-", "\\n")
            .doesNotContain("hunter2", "sid=secret");
    }

    @Test
    void stripsDatabaseDetailsPathsAndHandlesOversizedUnknownFields() {
        String text = LogSanitizer.text(
            "SQL [select secret from accounts]\n  Detail: Key(email)=(alice@test.com)\n/tmp/secrets/key C:\\private\\passwords",
            8192
        );
        assertThat(text).doesNotContain("select secret", "Key(email)", "/tmp/secrets", "private");
        var event = event("x".repeat(40000), null);
        for (int i = 0; i < 32; i++) event.addKeyValuePair(new KeyValuePair("field" + i, "汉".repeat(2048)));
        String json = new YumpooJsonLogFormatter().format(event);
        assertThat(json.getBytes(StandardCharsets.UTF_8).length).isLessThan(65536);
    }

    @Test
    void paginatesStableFileSnapshotAndIgnoresIncompleteTail() throws Exception {
        Path file = directory.resolve("server.log");
        Instant from = Instant.now().minusSeconds(600),
            to = Instant.now();
        Files.writeString(
            file,
            line(from.plusSeconds(1), "old") +
                line(from.plusSeconds(2), "middle") +
                line(from.plusSeconds(3), "new") +
                "{incomplete"
        );
        var adapter = new LogQueryAdapter(new MockEnvironment().withProperty("logging.file.name", file.toString()));
        var first = adapter.search(from, to, filter, null, 2);
        assertThat(first.items())
            .extracting(e -> e.record().msg())
            .containsExactly("new", "middle");
        Files.writeString(file, " completion}\n" + line(from.plusSeconds(4), "appended"), StandardOpenOption.APPEND);
        var second = adapter.search(from, to, filter, first.nextCursor(), 2);
        assertThat(second.items())
            .extracting(e -> e.record().msg())
            .containsExactly("old");
        assertThatThrownBy(() -> adapter.search(from.minusSeconds(1), to, filter, first.nextCursor(), 2)).isInstanceOf(
            com.yumpoo.platform.foundation.application.error.ApplicationException.class
        );
        Files.move(file, directory.resolve("moved"));
        var removed = adapter.search(from, to, filter, first.nextCursor(), 2);
        assertThat(removed.partial()).isTrue();
        assertThat(removed.partialReason()).isEqualTo("SOURCE_REMOVED");
    }

    @Test
    void readsOnlyOwnedGzipAndReportsCorruptLines() throws Exception {
        Instant from = Instant.now().minusSeconds(100),
            to = Instant.now();
        Path active = directory.resolve("server.log"),
            archive = directory.resolve("server.log." + LocalDate.now(ZoneOffset.UTC) + ".0.gz");
        try (var stream = new GZIPOutputStream(Files.newOutputStream(archive))) {
            stream.write((line(from.plusSeconds(1), "archive") + "not-json\n").getBytes(StandardCharsets.UTF_8));
        }
        Files.writeString(directory.resolve("other.log"), line(from.plusSeconds(2), "unrelated"));
        var result = new LogQueryAdapter(
            new MockEnvironment().withProperty("logging.file.name", active.toString())
        ).search(from, to, filter, null, 100);
        assertThat(result.items())
            .extracting(e -> e.record().msg())
            .containsExactly("archive");
        assertThat(result.skippedLines()).isEqualTo(1);
        assertThat(result.partial()).isTrue();
    }

    @Test
    void tailAdvancesEvenWhenFilteredAndDetectsRestart() {
        var adapter = new LogQueryAdapter(new MockEnvironment());
        adapter.start();
        try {
            Logger logger = (Logger) LoggerFactory.getLogger("com.yumpoo.platform.operations.LogTest");
            var noMatch = new LogQueryPort.Filter(Set.of("WARN"), Set.of(), null, null, null, null);
            var before = adapter.tail(null, null, noMatch, 10);
            logger.info("tail watermark test");
            var after = adapter.tail(before.bootId(), Long.parseLong(before.nextAfterSeq()), noMatch, 10);
            assertThat(Long.parseLong(after.nextAfterSeq())).isGreaterThan(Long.parseLong(before.nextAfterSeq()));
            assertThat(after.items()).isEmpty();
            assertThat(adapter.tail("old-boot", 0L, filter, 10).gapReason()).isEqualTo("RESTART");
        } finally {
            adapter.stop();
        }
    }

    @Test
    void buffersRemainBoundedAndKeepProblemEventsSeparately() {
        var buffer = new LogBufferAppender();
        Instant now = Instant.now();
        for (int i = 0; i < 10010; i++) buffer.add(
            new LogRecord(
                now,
                i == 0 ? "ERROR" : "INFO",
                "operations",
                "test",
                "message",
                "logger",
                "main",
                null,
                null,
                null,
                Map.of(),
                null
            )
        );
        assertThat(buffer.snapshot(false).items()).hasSize(10000);
        assertThat(buffer.evicted(false)).isEqualTo(10);
        assertThat(buffer.snapshot(true).items()).hasSize(1);
        assertThat(buffer.countsSince(Instant.EPOCH).get("INFO")).isEqualTo(10009);
    }

    @Test
    void histogramCountsBeyondAPageWithoutDoubleCountingAndFillsEmptyBuckets() throws Exception {
        Instant from = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MINUTES),
            to = from.plusSeconds(3600);
        Path file = directory.resolve("server.log");
        Files.writeString(file, line(from.plusSeconds(1), "counted").repeat(1205));
        var adapter = new LogQueryAdapter(new MockEnvironment().withProperty("logging.file.name", file.toString()));
        var histogram = adapter.histogram(from, to, filter);
        assertThat(histogram.partial()).isFalse();
        assertThat(histogram.buckets()).hasSize(60);
        assertThat(
            histogram
                .buckets()
                .stream()
                .mapToLong(b -> b.counts().getOrDefault("INFO", 0L))
                .sum()
        ).isEqualTo(1205);
    }

    @org.junit.jupiter.api.RepeatedTest(3)
    @org.junit.jupiter.api.Timeout(20)
    void concurrentTailNeverAdvancesPastUnobservedRecordsWithoutAGap() throws Exception {
        var buffer = new LogBufferAppender();
        var adapter = new LogQueryAdapter(new MockEnvironment(), buffer);
        var outstanding = new java.util.concurrent.Semaphore(4096);
        var record = new LogRecord(
            Instant.now(),
            "INFO",
            "operations",
            "test",
            "concurrent",
            "logger",
            "main",
            null,
            null,
            null,
            Map.of(),
            null
        );
        try (var writer = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var writing = writer.submit(() -> {
                for (int i = 0; i < 50000; i++) {
                    outstanding.acquireUninterruptibly();
                    buffer.add(record);
                }
            });
            long cursor = 0;
            int received = 0;
            while (cursor < 50000) {
                var page = adapter.tail(buffer.bootId, cursor, filter, 500);
                assertThat(page.gap()).isFalse();
                for (var item : page.items()) {
                    assertThat(Long.parseLong(item.seq())).isEqualTo(++received);
                }
                outstanding.release(page.items().size());
                cursor = Long.parseLong(page.nextAfterSeq());
            }
            writing.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(received).isEqualTo(50000);
        }
    }

    @Test
    void preservesRootCausesWhileRedactingPathsAddressesAndUrlSecrets() {
        assertThat(
            LogSanitizer.text(
                "I/O error on GET request for https://qyapi.weixin.qq.com/cgi-bin/gettoken: Connect timed out after 5000 ms",
                2048
            )
        ).isEqualTo(
            "I/O error on GET request for https://qyapi.weixin.qq.com/cgi-bin/gettoken: Connect timed out after 5000 ms"
        );
        assertThat(
            LogSanitizer.text("Failed to write C:\\ProgramData\\Yumpoo\\logs\\x.log because disk is full", 2048)
        ).isEqualTo("Failed to write [path] because disk is full");
        assertThat(
            LogSanitizer.text(
                "Failed http://user:secret@10.2.3.4/api?password=secret&value=private because timed out",
                2048
            )
        )
            .contains("http://***@[address]/api?[redacted] because timed out")
            .doesNotContain("secret", "private", "10.2.3.4");
    }

    @Test
    void nullFileKeysRequireStableCreationAndNonshrinkingSize() {
        var attrs = org.mockito.Mockito.mock(java.nio.file.attribute.BasicFileAttributes.class);
        var created = java.nio.file.attribute.FileTime.from(Instant.parse("2026-09-26T00:00:00Z"));
        org.mockito.Mockito.when(attrs.isRegularFile()).thenReturn(true);
        org.mockito.Mockito.when(attrs.creationTime()).thenReturn(created);
        org.mockito.Mockito.when(attrs.size()).thenReturn(200L);
        assertThat(LogQueryAdapter.sameFile(null, created, 100L, attrs)).isTrue();
        assertThat(LogQueryAdapter.sameFile(null, created, 201L, attrs)).isFalse();
        assertThat(
            LogQueryAdapter.sameFile(null, java.nio.file.attribute.FileTime.from(Instant.EPOCH), 100L, attrs)
        ).isFalse();
    }

    @Test
    void fileKeywordSearchMatchesOnlyRedactedTextWithinRange() throws Exception {
        Instant from = Instant.now().minusSeconds(600),
            to = Instant.now();
        Path file = directory.resolve("server.log");
        Files.writeString(
            file,
            line(from.minusSeconds(60), "plain secret-value") +
                line(from.plusSeconds(1), "token=secret-value") +
                line(from.plusSeconds(2), "plain secret-value")
        );
        var adapter = new LogQueryAdapter(new MockEnvironment().withProperty("logging.file.name", file.toString()));
        var keyword = new LogQueryPort.Filter(null, Set.of(), "SECRET-VALUE", null, null, null);
        assertThat(adapter.search(from, to, keyword, null, 10).items())
            .extracting(e -> e.record().msg())
            .containsExactly("plain secret-value");
        assertThat(
            adapter
                .histogram(from, to, keyword)
                .buckets()
                .stream()
                .mapToLong(b -> b.counts().getOrDefault("INFO", 0L))
                .sum()
        ).isEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void queuedFileQueryWaitsForTheInFlightScanInsteadOfBeingRateLimited() throws Exception {
        Instant from = Instant.now().minusSeconds(600),
            to = Instant.now();
        Path file = directory.resolve("server.log");
        Files.writeString(file, line(from.plusSeconds(1), "queued"));
        var adapter = new LogQueryAdapter(new MockEnvironment().withProperty("logging.file.name", file.toString()));
        adapter.scanner.acquire();
        var release = java.util.concurrent.CompletableFuture.runAsync(
            adapter.scanner::release,
            java.util.concurrent.CompletableFuture.delayedExecutor(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        );
        assertThat(adapter.search(from, to, filter, null, 10).items())
            .extracting(e -> e.record().msg())
            .containsExactly("queued");
        release.join();
        assertThat(adapter.scanner.availablePermits()).isEqualTo(1);
    }

    @Test
    void replacementBetweenPagesDoesNotReturnRowsFromTheNewFile() throws Exception {
        Path file = directory.resolve("server.log");
        Instant from = Instant.now().minusSeconds(600),
            to = Instant.now();
        Files.writeString(file, line(from.plusSeconds(1), "original").repeat(3));
        var created = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class).creationTime();
        var adapter = new LogQueryAdapter(new MockEnvironment().withProperty("logging.file.name", file.toString()));
        var first = adapter.search(from, to, filter, null, 1);
        Files.move(file, directory.resolve("rolled.log"));
        Files.writeString(file, line(from.plusSeconds(2), "replaced").repeat(3));
        Files.getFileAttributeView(file, java.nio.file.attribute.BasicFileAttributeView.class).setTimes(
            null,
            null,
            created
        );
        var second = adapter.search(from, to, filter, first.nextCursor(), 1);
        assertThat(second.partial()).isTrue();
        assertThat(second.partialReason()).isEqualTo("SOURCE_REMOVED");
        assertThat(second.items()).isEmpty();
    }

    private String line(Instant at, String message) {
        return (
            JsonMapper.builder()
                .build()
                .writeValueAsString(
                    Map.of(
                        "schema",
                        "yumpoo-log/1",
                        "time",
                        at.toString(),
                        "level",
                        "INFO",
                        "module",
                        "operations",
                        "msg",
                        message,
                        "logger",
                        "logger",
                        "thread",
                        "main"
                    )
                ) + "\n"
        );
    }
}
