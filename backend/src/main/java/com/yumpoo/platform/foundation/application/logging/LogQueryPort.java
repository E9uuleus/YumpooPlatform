package com.yumpoo.platform.foundation.application.logging;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import java.time.*;
import java.util.*;

public interface LogQueryPort {
    Page search(Instant from, Instant to, Filter filter, String cursor, int limit);
    Tail tail(String bootId, Long afterSeq, Filter filter, int limit);
    Histogram histogram(Instant from, Instant to, Filter filter);
    Map<String, Long> countsSince(Instant since);
    List<Entry> recentErrors(int limit);

    record Filter(Set<String> levels, Set<String> modules, String q, String requestId, String event, String userId) {
        public Filter {
            levels = levels == null ? Set.of("ERROR", "WARN", "INFO") : Set.copyOf(levels);
            modules = modules == null ? Set.of() : Set.copyOf(modules);
            if (levels.isEmpty() || !Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR").containsAll(levels)
                    || modules.size() > 16 || tooLong(q, 200) || tooLong(requestId, 128) || tooLong(event, 128)
                    || tooLong(userId, 36)) throw invalid();
        }
        public boolean matches(LogRecord r) {
            return levels.contains(r.level()) && (modules.isEmpty() || modules.contains(r.module()))
                    && (blank(requestId) || requestId.equals(r.requestId()) || requestId.equals(r.correlationId()))
                    && (blank(event) || event.equals(r.event())) && (blank(userId) || userId.equals(r.userId()))
                    && (blank(q) || (r.msg() + " " + r.event() + " " + (r.error() == null ? "" : r.error().type()))
                            .toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)));
        }
        public boolean problemsOnly() { return Set.of("WARN", "ERROR").containsAll(levels); }
        private static boolean tooLong(String value, int size) { return value != null && value.length() > size; }
        private static boolean blank(String value) { return value == null || value.isBlank(); }
    }
    record Entry(String id, String seq, LogRecord record) { }
    record Page(List<Entry> items, String source, String nextCursor, boolean partial, String partialReason,
            long scannedBytes, long skippedLines, long droppedCount) { }
    record Tail(String bootId, List<Entry> items, String nextAfterSeq, String latestSeq, boolean hasMore,
            boolean gap, String gapReason, long droppedCount) { }
    record Bucket(Instant time, Map<String, Long> counts) { }
    record Histogram(List<Bucket> buckets, int bucketSeconds, boolean partial, String partialReason) { }
    static void validateRange(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(1)) > 0) throw invalid();
    }
    static void validateLimit(int limit) { if (limit < 1 || limit > 500) throw invalid(); }
    static ApplicationException invalid() { return new ApplicationException(StandardErrorCode.VALIDATION_FAILED); }
}
