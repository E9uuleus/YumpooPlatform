package com.yumpoo.platform.foundation.infrastructure.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.yumpoo.platform.foundation.application.logging.LogRecord;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

final class LogBufferAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    record Item(long seq, LogRecord record, int bytes) {}

    record Snapshot(List<Item> items, long latest, long evicted, long dropped) {}

    private final LogRecordMapper mapper = new LogRecordMapper();
    private final Deque<Item> recent = new ArrayDeque<>();
    private final Deque<Item> problems = new ArrayDeque<>();
    private final NavigableMap<Instant, Map<String, Long>> counts = new TreeMap<>();
    private final Map<String, Long> totals = new HashMap<>();
    final String bootId = UUID.randomUUID().toString();
    final Instant started = Instant.now();
    private long sequence;
    private long dropped;
    private long recentBytes;
    private long problemBytes;
    private long recentEvicted;
    private long problemEvicted;
    private long lastDropped;

    @Override
    protected void append(ILoggingEvent event) {
        try {
            add(mapper.map(event));
        } catch (Throwable ignored) {
            synchronized (this) {
                dropped++;
            }
        }
    }

    synchronized void add(LogRecord original) {
        var error = original.error();
        if (error != null) error = new LogRecord.LogError(
            error.type(),
            error.msg(),
            LogSanitizer.text(error.stack(), 4096),
            error.hash()
        );
        LogRecord record = new LogRecord(
            original.time(),
            original.level(),
            original.module(),
            original.event(),
            original.msg(),
            original.logger(),
            original.thread(),
            original.requestId(),
            original.correlationId(),
            original.userId(),
            original.fields(),
            error
        );
        // Conservative UTF-16 accounting, including map/object overhead.
        int bytes =
            1024 +
            2 *
                (record.msg().length() +
                    record.fields().toString().length() +
                    (error == null ? 0 : error.stack().length()));
        counts
            .computeIfAbsent(record.time().truncatedTo(ChronoUnit.MINUTES), ignored -> new HashMap<>())
            .merge(record.level(), 1L, Long::sum);
        totals.merge(record.level(), 1L, Long::sum);
        counts.headMap(Instant.now().minus(Duration.ofDays(1)), false).clear();
        Item item = new Item(++sequence, record, bytes);
        if (bytes > 65536) {
            dropped++;
            lastDropped = sequence;
            return;
        }
        recent.addLast(item);
        recentBytes += bytes;
        while (recent.size() > 10000 || recentBytes > 16 * 1024 * 1024) {
            var evicted = recent.removeFirst();
            recentBytes -= evicted.bytes();
            recentEvicted = evicted.seq();
        }
        if (Set.of("WARN", "ERROR").contains(record.level())) {
            problems.addLast(item);
            problemBytes += bytes;
            while (problems.size() > 2000 || problemBytes > 8 * 1024 * 1024) {
                var evicted = problems.removeFirst();
                problemBytes -= evicted.bytes();
                problemEvicted = evicted.seq();
            }
        }
    }

    synchronized Snapshot snapshot(boolean problemOnly) {
        return new Snapshot(
            List.copyOf(problemOnly ? problems : recent),
            sequence,
            Math.max(problemOnly ? problemEvicted : recentEvicted, lastDropped),
            dropped
        );
    }

    synchronized long evicted(boolean problemOnly) {
        return problemOnly ? problemEvicted : recentEvicted;
    }

    synchronized long latest() {
        return sequence;
    }

    synchronized long dropped() {
        return dropped;
    }

    synchronized Map<String, Long> countsSince(Instant since) {
        if (!since.isAfter(started)) return Map.copyOf(totals);
        Map<String, Long> result = new HashMap<>();
        counts
            .tailMap(since.truncatedTo(ChronoUnit.MINUTES), true)
            .values()
            .forEach(bucket -> bucket.forEach((k, v) -> result.merge(k, v, Long::sum)));
        return Map.copyOf(result);
    }
}
