package com.yumpoo.platform.operations.application;

import com.yumpoo.platform.foundation.application.diagnostics.DeploymentDiagnosticsPort;
import com.yumpoo.platform.foundation.application.logging.LogQueryPort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OperationsModels {

    private OperationsModels() {}

    public record MetricPoint(Instant at, Map<String, Double> values) {}

    public record ComponentHealth(String code, String status, Instant checkedAt, String detail) {}

    public record PostureCheck(String code, String severity, String status) {}

    public record HostInfo(
        String hostName,
        String os,
        String architecture,
        String cpuModel,
        int processors,
        String javaVersion,
        long pid,
        Instant startedAt,
        long uptimeMs,
        long heapMax,
        String version,
        String commit,
        Instant buildTime,
        String schemaVersion,
        String databaseVersion,
        List<String> garbageCollectors,
        String defaultCharset,
        String timeZone,
        List<String> profiles,
        List<DeploymentDiagnosticsPort.Volume> volumes
    ) {}

    public record RuntimeSnapshot(
        Instant sampledAt,
        Instant ioUpdatedAt,
        String health,
        Map<String, Double> metrics,
        List<ComponentHealth> components,
        List<PostureCheck> posture,
        HostInfo host,
        long lostSamples
    ) {}

    public record Overview(
        RuntimeSnapshot runtime,
        List<MetricPoint> sparkline,
        List<Alert> alerts,
        List<LogQueryPort.Entry> recentErrors
    ) {}

    public record Series(String key, String unit, List<Double> values) {}

    public record MetricSeries(
        String range,
        int resolutionSeconds,
        List<Instant> timestamps,
        List<Series> series,
        List<Instant> restarts
    ) {}

    public record SessionInfo(
        UUID id,
        String clientType,
        String clientVersion,
        Instant issuedAt,
        Instant lastSeenAt,
        Instant expiresAt
    ) {}

    public record SessionUser(
        UUID userId,
        String displayName,
        String presence,
        Instant lastSeenAt,
        List<SessionInfo> sessions
    ) {}

    public record VersionCount(String clientType, String clientVersion, long count) {}

    public record SessionSummary(
        long online,
        long idle,
        long away,
        long activeSessions,
        List<VersionCount> byClientVersion
    ) {}

    public record Page<T>(List<T> items, long totalElements, int page, int size) {}

    public record Rule(
        String code,
        boolean enabled,
        Double warningThreshold,
        Double criticalThreshold,
        int forSeconds,
        long rowVersion,
        String etag,
        String comparison,
        String unit
    ) {}

    public record RuleUpdate(boolean enabled, Double warningThreshold, Double criticalThreshold, int forSeconds) {}

    public record Alert(
        UUID id,
        String ruleCode,
        String subjectKey,
        String severity,
        String status,
        Instant startedAt,
        Instant evaluatedAt,
        Double peakValue,
        Double lastValue,
        Map<String, Object> params,
        Instant acknowledgedAt,
        UUID acknowledgedByUserId,
        String acknowledgeNote,
        Instant resolvedAt,
        String resolution
    ) {}

    public record AlertEvent(
        UUID id,
        String eventType,
        String severity,
        Double value,
        UUID actorUserId,
        Instant occurredAt
    ) {}

    public record AlertDetail(Alert alert, List<AlertEvent> events) {}

    public record AlertSummary(long warning, long critical) {}
}
