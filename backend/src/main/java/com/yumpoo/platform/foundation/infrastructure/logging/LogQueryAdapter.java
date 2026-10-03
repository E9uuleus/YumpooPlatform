package com.yumpoo.platform.foundation.infrastructure.logging;

import ch.qos.logback.classic.LoggerContext;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.logging.LogQueryPort;
import com.yumpoo.platform.foundation.application.logging.LogRecord;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class LogQueryAdapter implements LogQueryPort, SmartLifecycle {

    private static final long BYTE_LIMIT = 256L * 1024 * 1024;
    private static final long TIME_LIMIT = 3_000_000_000L;
    // A queued query waits for one in-flight scan to finish its budget instead of failing immediately.
    private static final long ACQUIRE_LIMIT = TIME_LIMIT + 1_000_000_000L;
    private static final Set<String> LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
    private static final Set<String> RESERVED = Set.of(
        "schema",
        "time",
        "level",
        "module",
        "event",
        "msg",
        "logger",
        "thread",
        "requestId",
        "correlationId",
        "userId",
        "error"
    );
    private final LogBufferAppender buffer;
    private final Environment environment;
    final Semaphore scanner = new Semaphore(1, true);
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Cursor> cursors = new LinkedHashMap<>();
    private volatile boolean running;

    private record FileRef(
        Path path,
        Object key,
        java.nio.file.attribute.FileTime created,
        long size,
        byte[] prefix,
        String id
    ) {}

    private record Line(JsonNode root, Instant time, String level) {}

    private record Match(long number, Line line) {}

    private record Cursor(
        Instant from,
        Instant to,
        Filter filter,
        List<FileRef> files,
        int index,
        long beforeLine,
        long beforeSeq,
        boolean memory,
        Instant expires
    ) {}

    private static final class Budget {

        final long deadline = System.nanoTime() + TIME_LIMIT;
        long bytes, skipped;

        boolean exhausted() {
            return bytes >= BYTE_LIMIT || System.nanoTime() >= deadline;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LogQueryAdapter(Environment environment) {
        this(environment, new LogBufferAppender());
    }

    LogQueryAdapter(Environment environment, LogBufferAppender buffer) {
        this.environment = environment;
        this.buffer = buffer;
    }

    @Override
    public void start() {
        if (running) return;
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        buffer.setName("YUMPOO_OPERATIONS_BUFFER");
        buffer.setContext(context);
        buffer.start();
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(buffer);
        running = true;
    }

    @Override
    public void stop() {
        ((LoggerContext) LoggerFactory.getILoggerFactory())
            .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
            .detachAppender(buffer);
        buffer.stop();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 100;
    }

    @Override
    public Tail tail(String bootId, Long afterSeq, Filter filter, int limit) {
        LogQueryPort.validateLimit(limit);
        if (afterSeq != null && afterSeq < 0) throw LogQueryPort.invalid();
        var snapshot = buffer.snapshot(filter.problemsOnly());
        long latest = snapshot.latest();
        boolean restart = bootId != null && !bootId.equals(buffer.bootId);
        boolean gap = restart || (afterSeq != null && afterSeq < snapshot.evicted());
        long after = afterSeq == null || restart ? Math.max(0, latest - 10000) : afterSeq;
        if (!restart && after > latest) throw LogQueryPort.invalid();
        List<Entry> items = new ArrayList<>();
        long next = after;
        if (afterSeq == null || restart) {
            var matches = snapshot
                .items()
                .stream()
                .filter(item -> filter.matches(item.record()))
                .toList();
            for (var item : matches.subList(Math.max(0, matches.size() - limit), matches.size()))
                items.add(entry(item));
            next = latest;
        } else {
            for (var item : snapshot.items()) {
                if (item.seq() <= after) continue;
                next = item.seq();
                if (filter.matches(item.record())) items.add(entry(item));
                if (items.size() == limit) break;
            }
            if (items.size() < limit) next = latest;
        }
        return new Tail(
            buffer.bootId,
            List.copyOf(items),
            Long.toString(next),
            Long.toString(latest),
            next < latest,
            gap,
            restart ? "RESTART" : gap ? "BUFFER_EVICTED" : null,
            snapshot.dropped()
        );
    }

    @Override
    public Page search(Instant from, Instant to, Filter filter, String cursor, int limit) {
        LogQueryPort.validateRange(from, to);
        LogQueryPort.validateLimit(limit);
        Cursor state = cursor == null ? initial(from, to, filter) : lookup(cursor, from, to, filter);
        if (state.memory) return memory(state, limit);
        acquire();
        try {
            return files(state, limit);
        } finally {
            scanner.release();
        }
    }

    private Cursor initial(Instant from, Instant to, Filter filter) {
        var snapshot = buffer.snapshot(filter.problemsOnly());
        var items = snapshot.items();
        boolean covered =
            !from.isBefore(buffer.started) &&
            (snapshot.evicted() == 0 || (!items.isEmpty() && !from.isBefore(items.getFirst().record().time())));
        return new Cursor(
            from,
            to,
            filter,
            covered ? List.of() : fileRefs(from, to),
            0,
            Long.MAX_VALUE,
            snapshot.latest() + 1,
            covered,
            Instant.now().plusSeconds(300)
        );
    }

    private Page memory(Cursor state, int limit) {
        List<Entry> found = new ArrayList<>();
        var snapshot = buffer.snapshot(state.filter.problemsOnly());
        var source = new ArrayList<>(snapshot.items());
        Collections.reverse(source);
        for (var item : source)
            if (item.seq() < state.beforeSeq && within(item.record(), state) && state.filter.matches(item.record())) {
                found.add(entry(item));
                if (found.size() > limit) break;
            }
        boolean more = found.size() > limit;
        if (more) found.removeLast();
        String next = more
            ? save(
                  new Cursor(
                      state.from,
                      state.to,
                      state.filter,
                      List.of(),
                      0,
                      Long.MAX_VALUE,
                      Long.parseLong(found.getLast().seq()),
                      true,
                      state.expires
                  )
              )
            : null;
        boolean gap =
            snapshot.evicted() > 0 && (source.isEmpty() || !state.from.isAfter(source.getLast().record().time()));
        return new Page(found, "BUFFER", next, gap, gap ? "BUFFER_EVICTED" : null, 0, 0, snapshot.dropped());
    }

    private Page files(Cursor state, int limit) {
        List<Entry> found = new ArrayList<>();
        Budget budget = new Budget();
        for (int index = state.index; index < state.files.size(); index++) {
            FileRef ref = state.files.get(index);
            Deque<Match> matches = new ArrayDeque<>();
            String failure = scan(
                ref,
                index == state.index ? state.beforeLine : Long.MAX_VALUE,
                budget,
                (number, line) -> {
                    if (matches(line, state)) {
                        matches.addLast(new Match(number, line));
                        if (matches.size() > limit - found.size() + 1) matches.removeFirst();
                    }
                }
            );
            if (failure != null) {
                if (failure.equals("SOURCE_REMOVED")) matches.clear();
                while (!matches.isEmpty() && found.size() < limit) found.add(entry(ref, matches.removeLast()));
                return new Page(found, "FILE", null, true, failure, budget.bytes, budget.skipped, buffer.dropped());
            }
            while (!matches.isEmpty()) {
                Match item = matches.removeLast();
                if (found.size() == limit) {
                    String last = found.getLast().id();
                    long boundary = Long.parseLong(last.substring(last.lastIndexOf(':') + 1));
                    return new Page(
                        found,
                        "FILE",
                        save(
                            new Cursor(
                                state.from,
                                state.to,
                                state.filter,
                                state.files,
                                index,
                                boundary,
                                0,
                                false,
                                state.expires
                            )
                        ),
                        budget.skipped > 0,
                        budget.skipped > 0 ? "SKIPPED_LINES" : null,
                        budget.bytes,
                        budget.skipped,
                        buffer.dropped()
                    );
                }
                found.add(entry(ref, item));
            }
            if (found.size() == limit && index + 1 < state.files.size()) return new Page(
                found,
                "FILE",
                save(
                    new Cursor(
                        state.from,
                        state.to,
                        state.filter,
                        state.files,
                        index + 1,
                        Long.MAX_VALUE,
                        0,
                        false,
                        state.expires
                    )
                ),
                budget.skipped > 0,
                budget.skipped > 0 ? "SKIPPED_LINES" : null,
                budget.bytes,
                budget.skipped,
                buffer.dropped()
            );
        }
        return new Page(
            found,
            "FILE",
            null,
            budget.skipped > 0,
            budget.skipped > 0 ? "SKIPPED_LINES" : state.files.isEmpty() ? "NO_LOG_FILES" : null,
            budget.bytes,
            budget.skipped,
            buffer.dropped()
        );
    }

    private String scan(
        FileRef ref,
        long beforeLine,
        Budget budget,
        java.util.function.BiConsumer<Long, Line> visitor
    ) {
        if (budget.exhausted()) return "SCAN_BUDGET";
        try {
            Path path = locate(ref);
            if (path == null) return "SOURCE_REMOVED";
            try (
                InputStream raw = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
                InputStream snapshot = new BoundedInputStream(raw, ref.size);
                InputStream input = path.toString().endsWith(".gz") ? new GZIPInputStream(snapshot) : snapshot
            ) {
                byte[] chunk = new byte[8192];
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                long number = 0;
                boolean overflow = false;
                int count;
                while ((count = input.read(chunk)) != -1) {
                    budget.bytes += count;
                    if (budget.exhausted()) return "SCAN_BUDGET";
                    for (int start = 0, i = 0; i <= count; i++) {
                        if (i < count && chunk[i] != '\n') continue;
                        if (line.size() + i - start <= 65536) line.write(chunk, start, i - start);
                        else overflow = true;
                        start = i + 1;
                        if (i == count) break;
                        number++;
                        if (number >= beforeLine) return locate(ref) == null ? "SOURCE_REMOVED" : null;
                        if (overflow) budget.skipped++;
                        else try {
                            Line parsed = read(line.toString(StandardCharsets.UTF_8));
                            if (parsed == null) budget.skipped++;
                            else visitor.accept(number, parsed);
                        } catch (RuntimeException exception) {
                            budget.skipped++;
                        }
                        line.reset();
                        overflow = false;
                    }
                }
            }
            return locate(ref) == null ? "SOURCE_REMOVED" : null;
        } catch (IOException exception) {
            return "FILE_UNAVAILABLE";
        }
    }

    private List<FileRef> fileRefs(Instant from, Instant to) {
        String configured = environment.getProperty("logging.file.name");
        if (configured == null || configured.isBlank()) return List.of();
        Path active = Path.of(configured).toAbsolutePath().normalize();
        Path parent = active.getParent();
        if (!Files.isDirectory(parent)) return List.of();
        List<FileRef> files = new ArrayList<>();
        String name = active.getFileName().toString();
        var pattern = java.util.regex.Pattern.compile(
            java.util.regex.Pattern.quote(name) + "\\.(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)\\.gz"
        );
        try (var paths = Files.list(parent)) {
            for (Path path : paths.toList()) {
                var match = pattern.matcher(path.getFileName().toString());
                if (!path.equals(active) && !match.matches()) continue;
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!path.toRealPath().getParent().equals(parent.toRealPath())) continue;
                if (match.matches()) {
                    var date = LocalDate.parse(match.group(1));
                    if (
                        date.isBefore(from.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(1)) ||
                        date.isAfter(to.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1))
                    ) continue;
                }
                var attributes = Files.readAttributes(path, BasicFileAttributes.class);
                byte[] prefix =
                    attributes.fileKey() == null ? prefix(path, (int) Math.min(512, attributes.size())) : null;
                files.add(
                    new FileRef(
                        path,
                        attributes.fileKey(),
                        attributes.creationTime(),
                        attributes.size(),
                        prefix,
                        UUID.randomUUID().toString()
                    )
                );
            }
        } catch (IOException exception) {
            throw new ApplicationException(StandardErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        files.sort(
            Comparator.comparing((FileRef f) ->
                f.path.equals(active)
                    ? "9999"
                    : f.path
                          .getFileName()
                          .toString()
                          .substring(name.length() + 1, name.length() + 11)
            )
                .thenComparingLong(f -> {
                    var m = pattern.matcher(f.path.getFileName().toString());
                    return m.matches() ? Long.parseLong(m.group(2)) : Long.MAX_VALUE;
                })
                .reversed()
        );
        return List.copyOf(files);
    }

    private Path locate(FileRef file) throws IOException {
        if (Files.isSymbolicLink(file.path)) return null;
        if (Files.exists(file.path)) {
            var attributes = Files.readAttributes(file.path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (
                sameFile(file.key, file.created, file.size, attributes) &&
                (file.prefix == null || Arrays.equals(file.prefix, prefix(file.path, file.prefix.length)))
            ) return file.path;
        }
        return null;
    }

    static boolean sameFile(
        Object key,
        java.nio.file.attribute.FileTime created,
        long size,
        BasicFileAttributes current
    ) {
        return (
            current.isRegularFile() &&
            current.size() >= size &&
            (key != null
                ? key.equals(current.fileKey())
                : current.fileKey() == null && created.equals(current.creationTime()))
        );
    }

    private static byte[] prefix(Path path, int length) throws IOException {
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return input.readNBytes(length);
        }
    }

    private Line read(String value) {
        JsonNode root = json.readTree(value);
        if (!"yumpoo-log/1".equals(root.path("schema").asText())) return null;
        String level = root.path("level").asText();
        if (!LEVELS.contains(level)) return null;
        return new Line(root, Instant.parse(root.path("time").asText()), level);
    }

    // Redaction dominates scan cost, so filtering redacts only the text a keyword search reads.
    private static boolean matches(Line line, Cursor state) {
        if (
            line.time.isBefore(state.from) ||
            !line.time.isBefore(state.to) ||
            !state.filter.levels().contains(line.level)
        ) return false;
        JsonNode root = line.root,
            e = root.path("error");
        boolean text = state.filter.q() != null && !state.filter.q().isBlank();
        return state.filter.matches(
            new LogRecord(
                line.time,
                line.level,
                state.filter.modules().isEmpty() ? "" : LogSanitizer.text(root.path("module").asText(), 64),
                nullable(root, "event"),
                text ? LogSanitizer.text(root.path("msg").asText(), 2048) : "",
                "",
                "",
                nullable(root, "requestId"),
                nullable(root, "correlationId"),
                nullable(root, "userId"),
                Map.of(),
                text && e.isObject()
                    ? new LogRecord.LogError(LogSanitizer.text(e.path("type").asText(), 256), "", "", "")
                    : null
            )
        );
    }

    private Entry entry(FileRef ref, Match match) {
        return new Entry(ref.id + ":" + match.number, null, record(match.line));
    }

    private LogRecord record(Line line) {
        JsonNode root = line.root;
        Map<String, Object> fields = new LinkedHashMap<>();
        root.properties().forEach(pair -> {
            if (!RESERVED.contains(pair.getKey()) && fields.size() < 32) {
                var node = pair.getValue();
                LogRecordMapper.put(
                    fields,
                    pair.getKey(),
                    node.isNumber() ? node.numberValue() : node.isBoolean() ? node.asBoolean() : node.asText()
                );
            }
        });
        var e = root.path("error");
        LogRecord.LogError error = e.isObject()
            ? new LogRecord.LogError(
                  LogSanitizer.text(e.path("type").asText(), 256),
                  LogSanitizer.text(e.path("msg").asText(), 2048),
                  LogSanitizer.text(e.path("stack").asText(), 8192),
                  LogSanitizer.text(e.path("hash").asText(), 64)
              )
            : null;
        return new LogRecord(
            line.time,
            line.level,
            LogSanitizer.text(root.path("module").asText(), 64),
            nullable(root, "event"),
            LogSanitizer.text(root.path("msg").asText(), 2048),
            LogSanitizer.text(root.path("logger").asText(), 256),
            LogSanitizer.text(root.path("thread").asText(), 128),
            nullable(root, "requestId"),
            nullable(root, "correlationId"),
            nullable(root, "userId"),
            fields,
            error
        );
    }

    private static String nullable(JsonNode root, String key) {
        String value = root.hasNonNull(key) ? root.path(key).asText() : null;
        return value != null && LogRecordMapper.IDENTIFIER.matcher(value).matches() ? value : null;
    }

    private static boolean within(LogRecord record, Cursor state) {
        return !record.time().isBefore(state.from) && record.time().isBefore(state.to);
    }

    private Entry entry(LogBufferAppender.Item item) {
        return new Entry(buffer.bootId + ":" + item.seq(), Long.toString(item.seq()), item.record());
    }

    private synchronized String save(Cursor cursor) {
        cursors.entrySet().removeIf(e -> e.getValue().expires.isBefore(Instant.now()));
        while (cursors.size() >= 64) cursors.remove(cursors.keySet().iterator().next());
        String token = UUID.randomUUID().toString();
        cursors.put(token, cursor);
        return token;
    }

    private synchronized Cursor lookup(String token, Instant from, Instant to, Filter filter) {
        Cursor cursor = cursors.get(token);
        if (
            cursor == null ||
            cursor.expires.isBefore(Instant.now()) ||
            !cursor.from.equals(from) ||
            !cursor.to.equals(to) ||
            !cursor.filter.equals(filter)
        ) throw LogQueryPort.invalid();
        return cursor;
    }

    @Override
    public Map<String, Long> countsSince(Instant since) {
        return buffer.countsSince(since);
    }

    @Override
    public List<Entry> recentErrors(int limit) {
        var items = new ArrayList<>(buffer.snapshot(true).items());
        Collections.reverse(items);
        return items
            .stream()
            .filter(item -> item.record().level().equals("ERROR"))
            .limit(limit)
            .map(this::entry)
            .toList();
    }

    @Override
    public Histogram histogram(Instant from, Instant to, Filter filter) {
        LogQueryPort.validateRange(from, to);
        int seconds =
            Duration.between(from, to).toHours() <= 1 ? 60 : Duration.between(from, to).toHours() <= 6 ? 300 : 900;
        Map<Instant, Map<String, Long>> counts = new TreeMap<>();
        Cursor state = initial(from, to, filter);
        String reason = null;
        java.util.function.BiConsumer<Instant, String> count = (time, level) -> {
            Instant at = Instant.ofEpochSecond(Math.floorDiv(time.getEpochSecond(), seconds) * seconds);
            counts.computeIfAbsent(at, ignored -> new TreeMap<>()).merge(level, 1L, Long::sum);
        };
        if (state.memory) {
            var snapshot = buffer.snapshot(filter.problemsOnly());
            for (var item : snapshot.items()) if (
                within(item.record(), state) && filter.matches(item.record())
            ) count.accept(item.record().time(), item.record().level());
            if (
                snapshot.evicted() > 0 &&
                (snapshot.items().isEmpty() || !from.isAfter(snapshot.items().getFirst().record().time()))
            ) reason = "BUFFER_EVICTED";
        } else {
            acquire();
            try {
                Budget budget = new Budget();
                for (FileRef ref : state.files) {
                    reason = scan(ref, Long.MAX_VALUE, budget, (number, line) -> {
                        if (matches(line, state)) count.accept(line.time, line.level);
                    });
                    if (reason != null) break;
                }
                if (reason == null && budget.skipped > 0) reason = "SKIPPED_LINES";
            } finally {
                scanner.release();
            }
        }
        for (
            long at = Math.floorDiv(from.getEpochSecond(), seconds) * seconds;
            at < to.getEpochSecond();
            at += seconds
        ) counts.computeIfAbsent(Instant.ofEpochSecond(at), ignored -> new TreeMap<>());
        return new Histogram(
            counts
                .entrySet()
                .stream()
                .map(e -> new Bucket(e.getKey(), e.getValue()))
                .toList(),
            seconds,
            reason != null,
            reason
        );
    }

    private void acquire() {
        try {
            if (scanner.tryAcquire(ACQUIRE_LIMIT, TimeUnit.NANOSECONDS)) return;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        throw new ApplicationException(StandardErrorCode.RATE_LIMITED);
    }

    private static final class BoundedInputStream extends FilterInputStream {

        private long remaining;

        BoundedInputStream(InputStream input, long maximum) {
            super(input);
            remaining = maximum;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = super.read();
            if (value != -1) remaining--;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int count = in.read(bytes, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
    }
}
