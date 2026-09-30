package com.yumpoo.platform.operations.infrastructure;

import com.sun.management.OperatingSystemMXBean;
import com.yumpoo.platform.foundation.application.diagnostics.DeploymentDiagnosticsPort;
import com.yumpoo.platform.foundation.application.logging.LogQueryPort;
import com.yumpoo.platform.foundation.application.outbox.OutboxBacklogPort;
import com.yumpoo.platform.identityaccess.api.ActiveSessionQuery;
import com.yumpoo.platform.operations.application.MetricAggregation;
import com.yumpoo.platform.operations.application.OperationsAlertService;
import com.yumpoo.platform.operations.application.OperationsModels.ComponentHealth;
import com.yumpoo.platform.operations.application.OperationsModels.HostInfo;
import com.yumpoo.platform.operations.application.OperationsModels.MetricPoint;
import com.yumpoo.platform.operations.application.OperationsModels.PostureCheck;
import com.yumpoo.platform.operations.application.OperationsModels.RuntimeSnapshot;
import com.yumpoo.platform.operations.application.OperationsRepository;
import com.yumpoo.platform.operations.application.OperationsRuntime;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OpsSampler implements OperationsRuntime, SmartLifecycle {

    private final MeterRegistry registry;
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final OutboxBacklogPort outbox;
    private final ActiveSessionQuery sessions;
    private final DeploymentDiagnosticsPort deployment;
    private final LogQueryPort logs;
    private final OperationsRepository repository;
    private final OperationsAlertService alerts;
    private final Environment environment;
    private final ObjectProvider<BuildProperties> build;
    private final ObjectProvider<Flyway> flyway;
    private final Clock clock;
    private final UUID bootId = UUID.randomUUID();
    private final Instant startedAt = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
    private final Deque<MetricPoint> history = new ArrayDeque<>();
    private final NavigableMap<Instant, List<MetricPoint>> pendingMinutes = new TreeMap<>();
    private final NavigableMap<Instant, AlertSample> pendingAlerts = new TreeMap<>();
    private final Map<String, Double> previousCounters = new HashMap<>();
    private final AtomicReference<IoSample> io = new AtomicReference<>();
    private final AtomicReference<RuntimeSnapshot> current = new AtomicReference<>();
    private ScheduledExecutorService sampler;
    private ExecutorService worker;
    private Future<?> inFlight;
    private volatile boolean running;
    private long lostSamples;
    private int dbFailures;
    private volatile String schema = "unknown";
    private volatile String databaseVersion = "unknown";
    private Instant lastCleanup = Instant.EPOCH;
    private Instant diskCheckedAt = Instant.EPOCH;
    private Instant databaseOutageAt;
    private int databaseOutageFailures;
    private volatile Instant probeStartedAt;
    private long droppedAlertSamples;

    private record AlertSample(Map<String, Double> values, List<PostureCheck> posture) {}

    private record IoSample(
        Instant at,
        Map<String, Double> values,
        List<ComponentHealth> health,
        DeploymentDiagnosticsPort.Snapshot deployment
    ) {}

    public OpsSampler(
        MeterRegistry registry,
        DataSource source,
        OutboxBacklogPort outbox,
        ActiveSessionQuery sessions,
        DeploymentDiagnosticsPort deployment,
        LogQueryPort logs,
        OperationsRepository repository,
        OperationsAlertService alerts,
        Environment environment,
        ObjectProvider<BuildProperties> build,
        ObjectProvider<Flyway> flyway,
        Clock clock
    ) {
        this.registry = registry;
        this.source = source;
        this.outbox = outbox;
        this.sessions = sessions;
        this.deployment = deployment;
        this.logs = logs;
        this.repository = repository;
        this.alerts = alerts;
        this.environment = environment;
        this.build = build;
        this.flyway = flyway;
        this.clock = clock;
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(3);
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        sampler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "yumpoo-ops-sampler"));
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>(), r ->
            daemon(r, "yumpoo-ops-io")
        );
        sampler.scheduleWithFixedDelay(this::sampleSafely, 0, 15, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (sampler != null) sampler.shutdownNow();
        if (worker != null) worker.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return environment.getProperty("yumpoo.operations.sampling-enabled", Boolean.class, true);
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private void sampleSafely() {
        try {
            sample();
        } catch (RuntimeException exception) {
            LoggerFactory.getLogger(getClass())
                .atWarn()
                .setMessage("operations sampling failed")
                .addKeyValue("event", "operations.sample.failed")
                .addKeyValue("exceptionType", exception.getClass().getName())
                .log();
        }
    }

    void sample() {
        Instant now = clock.instant();
        Map<String, Double> values = localMetrics(now);
        IoSample remote = io.get();
        boolean fresh = remote != null && Duration.between(remote.at, now).getSeconds() <= 45;
        if (remote != null) remote.values.forEach((key, value) -> values.put(key, fresh ? value : null));
        Instant probing = probeStartedAt;
        if (probing != null) values.put("db.ping", Math.max(0, Duration.between(probing, now).toMillis()) * 1d);
        List<ComponentHealth> health = new ArrayList<>();
        health.add(new ComponentHealth("application", "UP", now, ""));
        if (remote != null) remote.health.forEach(h ->
            health.add(fresh ? h : new ComponentHealth(h.code(), "UNKNOWN", h.checkedAt(), "STALE"))
        );
        else health.add(new ComponentHealth("database", "UNKNOWN", null, "SAMPLING"));
        if (poolBusy()) {
            health.removeIf(component -> component.code().equals("database"));
            health.add(new ComponentHealth("database", "DEGRADED", now, "POOL_BUSY"));
        }
        List<PostureCheck> posture = posture();
        HostInfo host = host(remote == null ? List.of() : remote.deployment.volumes());
        String status = health.stream().anyMatch(h -> h.status().equals("DOWN"))
            ? "DOWN"
            : health.stream().anyMatch(h -> h.status().equals("DEGRADED")) ||
                posture.stream().anyMatch(p -> p.status().equals("FAIL") && !p.severity().equals("INFO")) ||
                !alerts.cached().isEmpty()
              ? "DEGRADED"
              : health.stream().anyMatch(h -> h.status().equals("UNKNOWN"))
                ? "UNKNOWN"
                : "UP";
        queueAlerts(now, values, posture);
        synchronized (pendingAlerts) {
            values.put("alerts.bufferedSamples", (double) pendingAlerts.size());
            values.put("alerts.droppedSamples", (double) droppedAlertSamples);
        }
        MetricPoint point = new MetricPoint(now, Collections.unmodifiableMap(new LinkedHashMap<>(values)));
        synchronized (history) {
            history.addLast(point);
            while (history.size() > 240) history.removeFirst();
        }
        synchronized (pendingMinutes) {
            pendingMinutes
                .computeIfAbsent(now.truncatedTo(ChronoUnit.MINUTES), ignored -> new ArrayList<>())
                .add(point);
            while (pendingMinutes.size() > 60) {
                pendingMinutes.pollFirstEntry();
                lostSamples++;
            }
        }
        current.set(
            new RuntimeSnapshot(
                now,
                remote == null ? null : remote.at,
                status,
                point.values(),
                List.copyOf(health),
                posture,
                host,
                lostSamples
            )
        );
        if (inFlight == null || inFlight.isDone()) {
            Map<String, Double> local = new LinkedHashMap<>(values);
            inFlight = worker.submit(() -> collectIo(now, local, posture));
        }
    }

    private Map<String, Double> localMetrics(Instant now) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (String key : List.of(
            "cpu.system",
            "cpu.process",
            "memory.total",
            "memory.used",
            "swap.total",
            "swap.used",
            "heap.used",
            "heap.max",
            "heap.ratio",
            "nonheap.used",
            "threads.live",
            "gc.pause",
            "http.requests",
            "http.errors",
            "http.errorRate",
            "http.p95",
            "db.pool.active",
            "db.pool.pending",
            "db.pool.max",
            "logs.error5m",
            "logs.error",
            "logs.warn"
        ))
            values.put(key, null);
        try {
            var os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof OperatingSystemMXBean bean) {
                put(values, "cpu.system", bean.getCpuLoad());
                put(values, "cpu.process", bean.getProcessCpuLoad());
                put(values, "memory.total", bean.getTotalMemorySize());
                put(values, "memory.used", bean.getTotalMemorySize() - bean.getFreeMemorySize());
                put(values, "swap.total", bean.getTotalSwapSpaceSize());
                put(values, "swap.used", bean.getTotalSwapSpaceSize() - bean.getFreeSwapSpaceSize());
            }
        } catch (RuntimeException ignored) {}
        try {
            var memory = ManagementFactory.getMemoryMXBean();
            var heap = memory.getHeapMemoryUsage();
            put(values, "heap.used", heap.getUsed());
            put(values, "heap.max", heap.getMax());
            put(values, "heap.ratio", heap.getMax() > 0 ? (double) heap.getUsed() / heap.getMax() : -1);
            put(values, "nonheap.used", memory.getNonHeapMemoryUsage().getUsed());
            put(values, "threads.live", ManagementFactory.getThreadMXBean().getThreadCount());
        } catch (RuntimeException ignored) {}
        try {
            double gc = registry
                .find("jvm.gc.pause")
                .timers()
                .stream()
                .mapToDouble(t -> t.totalTime(TimeUnit.MILLISECONDS))
                .sum();
            values.put("gc.pause", delta("gc.pause", gc));
        } catch (RuntimeException ignored) {}
        try {
            double count = 0,
                errors = 0,
                p95 = 0;
            for (var timer : registry.find("http.server.requests").timers()) {
                String uri = timer.getId().getTag("uri");
                if (uri == null || uri.startsWith("/actuator") || uri.startsWith("/api/v1/admin/operations")) continue;
                count += timer.count();
                String status = timer.getId().getTag("status");
                if (status != null && status.startsWith("5")) errors += timer.count();
            }
            var businessLatency = registry.find("yumpoo.http.business").timer();
            if (businessLatency != null) for (var percentile : businessLatency.takeSnapshot().percentileValues())
                if (Math.abs(percentile.percentile() - .95) < .001) p95 = percentile.value(TimeUnit.MILLISECONDS);
            values.put("http.requests", delta("http.requests", count));
            values.put("http.errors", delta("http.errors", errors));
            double requests5m = values.get("http.requests") == null ? 0 : values.get("http.requests");
            double errors5m = values.get("http.errors") == null ? 0 : values.get("http.errors");
            boolean fullWindow;
            synchronized (history) {
                fullWindow = !history.isEmpty() && !history.getFirst().at().isAfter(now.minusSeconds(300));
                for (var point : history)
                    if (point.at().isAfter(now.minusSeconds(300))) {
                        if (point.values().get("http.requests") != null) requests5m += point
                            .values()
                            .get("http.requests");
                        if (point.values().get("http.errors") != null) errors5m += point.values().get("http.errors");
                    }
            }
            values.put("http.errorRate", fullWindow && requests5m >= 20 ? errors5m / requests5m : null);
            values.put("http.p95", requests5m >= 20 && Double.isFinite(p95) && p95 > 0 ? p95 : null);
        } catch (RuntimeException ignored) {}
        try {
            if (source instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
                put(values, "db.pool.active", hikari.getHikariPoolMXBean().getActiveConnections());
                put(values, "db.pool.pending", hikari.getHikariPoolMXBean().getThreadsAwaitingConnection());
                put(values, "db.pool.max", hikari.getMaximumPoolSize());
            }
        } catch (RuntimeException ignored) {}
        try {
            var counts = logs.countsSince(now.minusSeconds(300));
            put(values, "logs.error5m", counts.getOrDefault("ERROR", 0L));
            var totals = logs.countsSince(startedAt);
            values.put("logs.error", delta("logs.error", totals.getOrDefault("ERROR", 0L)));
            values.put("logs.warn", delta("logs.warn", totals.getOrDefault("WARN", 0L)));
        } catch (RuntimeException ignored) {}
        return values;
    }

    void collectIo(Instant at, Map<String, Double> local, List<PostureCheck> posture) {
        Map<String, Double> values = new LinkedHashMap<>();
        List<ComponentHealth> health = new ArrayList<>();
        DeploymentDiagnosticsPort.Snapshot disks;
        try {
            IoSample previous = io.get();
            if (previous != null && Duration.between(diskCheckedAt, at).getSeconds() < 60) disks = previous.deployment;
            else {
                disks = deployment.read();
                diskCheckedAt = at;
            }
        } catch (RuntimeException exception) {
            disks = new DeploymentDiagnosticsPort.Snapshot("UNKNOWN", List.of());
        }
        health.add(new ComponentHealth("deploymentDirectories", disks.directoryHealth(), at, ""));
        double lowestFree = disks
            .volumes()
            .stream()
            .filter(v -> v.totalBytes() > 0)
            .mapToDouble(v -> (double) v.freeBytes() / v.totalBytes())
            .min()
            .orElse(-1);
        health.add(
            new ComponentHealth(
                "disk",
                lowestFree < 0 ? "UNKNOWN" : lowestFree <= .20 ? "DEGRADED" : "UP",
                diskCheckedAt.equals(Instant.EPOCH) ? null : diskCheckedAt,
                ""
            )
        );
        for (var volume : disks.volumes()) {
            put(values, "disk." + volume.id() + ".free", volume.freeBytes());
            put(values, "disk." + volume.id() + ".total", volume.totalBytes());
        }
        boolean busy = poolBusy();
        if (busy) {
            health.add(new ComponentHealth("database", "DEGRADED", at, "POOL_BUSY"));
            values.put("db.ping", null);
            values.put("db.failures", null);
        } else {
            try {
                probeStartedAt = clock.instant();
                long start = System.nanoTime();
                jdbc.queryForObject("SELECT 1", Integer.class);
                put(values, "db.ping", (System.nanoTime() - start) / 1_000_000d);
                dbFailures = 0;
                health.add(new ComponentHealth("database", "UP", at, ""));
            } catch (RuntimeException exception) {
                dbFailures++;
                values.put("db.ping", null);
                health.add(new ComponentHealth("database", "DOWN", at, "DEPENDENCY_UNAVAILABLE"));
            } finally {
                probeStartedAt = null;
            }
            put(values, "db.failures", dbFailures);
            if (dbFailures >= 2) {
                if (databaseOutageAt == null) databaseOutageAt = at;
                databaseOutageFailures = Math.max(databaseOutageFailures, dbFailures);
            }
        }
        try {
            if (busy || dbFailures > 0) throw new IllegalStateException();
            var backlog = outbox.read();
            put(values, "outbox.backlog", backlog.total());
            put(values, "outbox.dead", backlog.dead());
            put(
                values,
                "outbox.oldestAge",
                backlog.oldest() == null ? 0 : Math.max(0, Duration.between(backlog.oldest(), at).getSeconds())
            );
            health.add(new ComponentHealth("outbox", backlog.dead() > 0 ? "DEGRADED" : "UP", at, ""));
        } catch (RuntimeException exception) {
            values.put("outbox.backlog", null);
            values.put("outbox.dead", null);
            values.put("outbox.oldestAge", null);
            health.add(new ComponentHealth("outbox", "UNKNOWN", at, "DEPENDENCY_UNAVAILABLE"));
        }
        try {
            if (busy || dbFailures > 0) throw new IllegalStateException();
            var active = sessions.countActive(at);
            put(values, "sessions.online", active.online());
            put(values, "sessions.idle", active.idle());
        } catch (RuntimeException exception) {
            values.put("sessions.online", null);
            values.put("sessions.idle", null);
        }
        io.set(new IoSample(at, Collections.unmodifiableMap(values), List.copyOf(health), disks));
        RuntimeSnapshot latest = current.get();
        Map<String, Double> observation = new LinkedHashMap<>(latest == null ? local : latest.metrics());
        observation.putAll(values);
        queueAlerts(clock.instant(), observation, posture);
        if (busy || dbFailures > 0) return;
        try {
            flushAlerts();
            if (databaseOutageAt != null) {
                alerts.recordDatabaseOutage(databaseOutageAt, databaseOutageFailures);
                databaseOutageAt = null;
                databaseOutageFailures = 0;
            }
            if (schema.equals("unknown")) {
                var migration = flyway.getIfAvailable();
                if (migration != null && migration.info().current() != null) schema = migration
                    .info()
                    .current()
                    .getVersion()
                    .toString();
            }
            if (databaseVersion.equals("unknown")) databaseVersion = jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<String>) connection -> {
                    var metadata = connection.getMetaData();
                    return metadata == null
                        ? "unknown"
                        : metadata.getDatabaseProductName() +
                              " " +
                              metadata.getDatabaseMajorVersion() +
                              "." +
                              metadata.getDatabaseMinorVersion();
                }
            );
            Map<Instant, List<MetricPoint>> pending;
            synchronized (pendingMinutes) {
                pending = new TreeMap<>(pendingMinutes.headMap(at.truncatedTo(ChronoUnit.MINUTES), false));
            }
            int saved = 0;
            for (var entry : pending.entrySet()) {
                repository.saveMinute(
                    bootId,
                    startedAt,
                    entry.getKey(),
                    MetricAggregation.aggregate(entry.getValue()),
                    entry.getValue().size()
                );
                synchronized (pendingMinutes) {
                    pendingMinutes.remove(entry.getKey());
                }
                if (++saved == 5) break;
            }
            if (Duration.between(lastCleanup, at).toHours() >= 24) {
                repository.cleanup(at);
                lastCleanup = at;
            }
        } catch (RuntimeException exception) {
            LoggerFactory.getLogger(getClass())
                .atWarn()
                .setMessage("operations persistence unavailable")
                .addKeyValue("event", "operations.persistence.failed")
                .addKeyValue("exceptionType", exception.getClass().getName())
                .log();
        }
    }

    private boolean poolBusy() {
        return (
            source instanceof HikariDataSource hikari &&
            hikari.getHikariPoolMXBean() != null &&
            hikari.getHikariPoolMXBean().getIdleConnections() == 0 &&
            hikari.getHikariPoolMXBean().getActiveConnections() >= hikari.getMaximumPoolSize()
        );
    }

    private void queueAlerts(Instant at, Map<String, Double> values, List<PostureCheck> posture) {
        synchronized (pendingAlerts) {
            pendingAlerts.put(
                at,
                new AlertSample(Collections.unmodifiableMap(new LinkedHashMap<>(values)), List.copyOf(posture))
            );
            while (pendingAlerts.size() > 240) {
                pendingAlerts.pollFirstEntry();
                droppedAlertSamples++;
            }
        }
    }

    private void flushAlerts() {
        for (int count = 0; count < 40; count++) {
            Map.Entry<Instant, AlertSample> next;
            synchronized (pendingAlerts) {
                next = pendingAlerts.firstEntry();
            }
            if (next == null) return;
            alerts.evaluate(next.getValue().values(), next.getValue().posture(), next.getKey());
            synchronized (pendingAlerts) {
                pendingAlerts.remove(next.getKey(), next.getValue());
            }
        }
    }

    private HostInfo host(List<DeploymentDiagnosticsPort.Volume> volumes) {
        var runtime = ManagementFactory.getRuntimeMXBean();
        var os = ManagementFactory.getOperatingSystemMXBean();
        var properties = build.getIfAvailable();
        return new HostInfo(
            environment.getProperty("COMPUTERNAME", "本机"),
            os.getName() + " " + os.getVersion(),
            os.getArch(),
            environment.getProperty("PROCESSOR_IDENTIFIER"),
            os.getAvailableProcessors(),
            System.getProperty("java.version"),
            ProcessHandle.current().pid(),
            startedAt,
            runtime.getUptime(),
            ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax(),
            properties == null ? "unknown" : properties.getVersion(),
            properties == null ? "unknown" : Objects.toString(properties.get("commit"), "unknown"),
            properties == null ? null : properties.getTime(),
            schema,
            databaseVersion,
            ManagementFactory.getGarbageCollectorMXBeans()
                .stream()
                .map(java.lang.management.GarbageCollectorMXBean::getName)
                .toList(),
            java.nio.charset.Charset.defaultCharset().name(),
            ZoneId.systemDefault().getId(),
            List.of(environment.getActiveProfiles()),
            volumes
        );
    }

    private List<PostureCheck> posture() {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        List<PostureCheck> checks = new ArrayList<>();
        check(checks, "LOCAL_AUTH_ENABLED", prod ? "CRITICAL" : "INFO", flag("yumpoo.auth.local.enabled"));
        check(checks, "CONTROLLED_AUTH_ENABLED", prod ? "CRITICAL" : "INFO", flag("yumpoo.auth.controlled.enabled"));
        check(
            checks,
            "M113_FIXTURE_ENABLED",
            prod ? "CRITICAL" : "INFO",
            flag("yumpoo.verification.m1-13.fixture-enabled")
        );
        check(
            checks,
            "WECOM_PROBE_ENABLED",
            "WARNING",
            flag("yumpoo.m012.wecom.enabled") || flag("yumpoo.m015.wecom.enabled")
        );
        check(
            checks,
            "SERVER_NOT_LOOPBACK",
            "WARNING",
            !Set.of("127.0.0.1", "::1", "localhost").contains(environment.getProperty("server.address", ""))
        );
        check(
            checks,
            "DEFAULT_CHARSET_NOT_UTF8",
            "WARNING",
            !java.nio.charset.Charset.defaultCharset().equals(java.nio.charset.StandardCharsets.UTF_8)
        );
        check(checks, "LOG_FILE_NOT_CONFIGURED", "WARNING", environment.getProperty("logging.file.name", "").isBlank());
        check(
            checks,
            "DEFENDER_NOT_CONFIGURED",
            "WARNING",
            environment.getProperty("yumpoo.attachments.defender-executable", "").isBlank()
        );
        check(checks, "ATTACHMENT_CLEANUP_DELETE_ENABLED", "INFO", flag("yumpoo.attachments.cleanup-delete-enabled"));
        try {
            String until = environment.getProperty("yumpoo.session.previous-accept-until", "");
            check(
                checks,
                "SESSION_PREVIOUS_KEY_EXPIRED",
                "INFO",
                !until.isBlank() && Instant.parse(until).isBefore(clock.instant())
            );
        } catch (RuntimeException exception) {
            checks.add(new PostureCheck("SESSION_PREVIOUS_KEY_EXPIRED", "INFO", "UNKNOWN"));
        }
        return List.copyOf(checks);
    }

    private boolean flag(String property) {
        return environment.getProperty(property, Boolean.class, false);
    }

    private static void check(List<PostureCheck> list, String code, String severity, boolean failed) {
        list.add(new PostureCheck(code, severity, failed ? "FAIL" : "PASS"));
    }

    private static void put(Map<String, Double> values, String key, double value) {
        values.put(key, Double.isFinite(value) && value >= 0 ? value : null);
    }

    private Double delta(String key, double value) {
        Double previous = previousCounters.put(key, value);
        return previous == null || value < previous ? null : value - previous;
    }

    @Override
    public RuntimeSnapshot snapshot() {
        var value = current.get();
        return value != null
            ? value
            : new RuntimeSnapshot(clock.instant(), null, "UNKNOWN", Map.of(), List.of(), posture(), host(List.of()), 0);
    }

    @Override
    public List<MetricPoint> recent() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }
}
