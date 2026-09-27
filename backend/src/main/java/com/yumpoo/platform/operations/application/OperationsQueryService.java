package com.yumpoo.platform.operations.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.logging.LogQueryPort;
import com.yumpoo.platform.identityaccess.api.ActiveSessionQuery;
import com.yumpoo.platform.operations.application.OperationsModels.HostInfo;
import com.yumpoo.platform.operations.application.OperationsModels.MetricPoint;
import com.yumpoo.platform.operations.application.OperationsModels.MetricSeries;
import com.yumpoo.platform.operations.application.OperationsModels.Overview;
import com.yumpoo.platform.operations.application.OperationsModels.Page;
import com.yumpoo.platform.operations.application.OperationsModels.Series;
import com.yumpoo.platform.operations.application.OperationsModels.SessionInfo;
import com.yumpoo.platform.operations.application.OperationsModels.SessionSummary;
import com.yumpoo.platform.operations.application.OperationsModels.SessionUser;
import com.yumpoo.platform.operations.application.OperationsModels.VersionCount;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OperationsQueryService {

    private final OperationsRuntime runtime;
    private final OperationsRepository repository;
    private final OperationsAlertService alerts;
    private final ActiveSessionQuery sessions;
    private final LogQueryPort logs;
    private final Clock clock;

    public OperationsQueryService(
        OperationsRuntime runtime,
        OperationsRepository repository,
        OperationsAlertService alerts,
        ActiveSessionQuery sessions,
        LogQueryPort logs,
        Clock clock
    ) {
        this.runtime = runtime;
        this.repository = repository;
        this.alerts = alerts;
        this.sessions = sessions;
        this.logs = logs;
        this.clock = clock;
    }

    public Overview overview() {
        return new Overview(
            runtime.snapshot(),
            runtime.recent(),
            alerts.cached().stream().limit(5).toList(),
            logs.recentErrors(5)
        );
    }

    public HostInfo host() {
        return runtime.snapshot().host();
    }

    public MetricSeries metrics(String range, Set<String> keys) {
        var ranges = Map.of("1h", 3600L, "6h", 21600L, "24h", 86400L, "7d", 604800L, "14d", 1209600L);
        if (
            !ranges.containsKey(range) ||
            keys.size() > 32 ||
            keys.stream().anyMatch(k -> !k.matches("[A-Za-z0-9.:-]{1,128}"))
        ) throw invalid();
        Instant to = clock.instant();
        Instant from = to.minusSeconds(ranges.get(range));
        List<MetricPoint> recent = runtime.recent();
        boolean memory =
            range.equals("1h") && !recent.isEmpty() && !recent.getFirst().at().isAfter(from.plusSeconds(15));
        int resolution = memory ? 15 : range.equals("7d") ? 900 : range.equals("14d") ? 1800 : 60;
        List<MetricPoint> points = new ArrayList<>(
            memory ? recent : repository.metrics(from.truncatedTo(java.time.temporal.ChronoUnit.MINUTES), to)
        );
        if (!memory) {
            Instant newest = points.isEmpty() ? Instant.EPOCH : points.getLast().at().plusSeconds(60);
            recent
                .stream()
                .filter(p -> !p.at().isBefore(newest))
                .forEach(points::add);
        }
        Map<Instant, List<MetricPoint>> buckets = new TreeMap<>();
        for (MetricPoint point : points)
            if (!point.at().isBefore(from) && !point.at().isAfter(to)) {
                Instant bucket = Instant.ofEpochSecond(
                    Math.floorDiv(point.at().getEpochSecond(), resolution) * resolution
                );
                buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(point);
            }
        Map<Instant, Map<String, Double>> values = new TreeMap<>();
        buckets.forEach((at, group) -> values.put(at, MetricAggregation.aggregate(group)));
        Set<String> actualKeys = new TreeSet<>(keys);
        if (actualKeys.isEmpty()) values.values().forEach(v -> actualKeys.addAll(v.keySet()));
        List<Instant> timestamps = new ArrayList<>();
        long first = Math.floorDiv(from.getEpochSecond(), resolution) * resolution;
        for (long t = first; t <= to.getEpochSecond(); t += resolution) timestamps.add(Instant.ofEpochSecond(t));
        List<Series> series = actualKeys
            .stream()
            .map(key ->
                new Series(
                    key,
                    MetricAggregation.unit(key),
                    timestamps
                        .stream()
                        .map(at -> values.getOrDefault(at, Map.of()).get(key))
                        .toList()
                )
            )
            .toList();
        return new MetricSeries(range, resolution, timestamps, series, repository.restarts(from, to));
    }

    private List<SessionUser> users(UUID companyId, Instant now) {
        Map<UUID, List<ActiveSessionQuery.Session>> grouped = new LinkedHashMap<>();
        for (var session : sessions.findActive(companyId, now))
            grouped.computeIfAbsent(session.userId(), ignored -> new ArrayList<>()).add(session);
        return grouped
            .entrySet()
            .stream()
            .map(entry -> {
                var values = entry.getValue();
                Instant lastSeen = values
                    .stream()
                    .map(ActiveSessionQuery.Session::lastSeenAt)
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
                String presence = !lastSeen.isBefore(now.minusSeconds(120))
                    ? "ONLINE"
                    : !lastSeen.isBefore(now.minusSeconds(1800))
                      ? "IDLE"
                      : "AWAY";
                return new SessionUser(
                    entry.getKey(),
                    values.getFirst().displayName(),
                    presence,
                    lastSeen,
                    values
                        .stream()
                        .map(s ->
                            new SessionInfo(
                                s.id(),
                                s.clientType(),
                                s.clientVersion(),
                                s.issuedAt(),
                                s.lastSeenAt(),
                                s.expiresAt()
                            )
                        )
                        .toList()
                );
            })
            .sorted(
                Comparator.comparingInt((SessionUser s) -> List.of("ONLINE", "IDLE", "AWAY").indexOf(s.presence()))
                    .thenComparing(SessionUser::lastSeenAt, Comparator.reverseOrder())
                    .thenComparing(SessionUser::userId)
            )
            .toList();
    }

    public Page<SessionUser> sessions(
        UUID companyId,
        String presence,
        String clientType,
        String q,
        int page,
        int size
    ) {
        OperationsAlertService.validatePage(page, size);
        if (
            (presence != null && !Set.of("ONLINE", "IDLE", "AWAY").contains(presence)) ||
            (clientType != null && !Set.of("WEB", "ELECTRON").contains(clientType)) ||
            (q != null && q.length() > 200)
        ) throw invalid();
        var filtered = users(companyId, clock.instant())
            .stream()
            .filter(u -> presence == null || u.presence().equals(presence))
            .filter(
                u ->
                    clientType == null ||
                    u
                        .sessions()
                        .stream()
                        .anyMatch(s -> s.clientType().equals(clientType))
            )
            .filter(u -> q == null || u.displayName().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))
            .toList();
        return new Page<>(
            filtered
                .stream()
                .skip((long) page * size)
                .limit(size)
                .toList(),
            filtered.size(),
            page,
            size
        );
    }

    public SessionSummary sessionSummary(UUID companyId) {
        var users = users(companyId, clock.instant());
        Map<String, Long> versions = new TreeMap<>();
        users.forEach(u ->
            u
                .sessions()
                .forEach(s ->
                    versions.merge(s.clientType() + "|" + Objects.toString(s.clientVersion(), "UNKNOWN"), 1L, Long::sum)
                )
        );
        return new SessionSummary(
            users
                .stream()
                .filter(u -> u.presence().equals("ONLINE"))
                .count(),
            users
                .stream()
                .filter(u -> u.presence().equals("IDLE"))
                .count(),
            users
                .stream()
                .filter(u -> u.presence().equals("AWAY"))
                .count(),
            users
                .stream()
                .mapToLong(u -> u.sessions().size())
                .sum(),
            versions
                .entrySet()
                .stream()
                .map(e -> new VersionCount(e.getKey().split("\\|", 2)[0], e.getKey().split("\\|", 2)[1], e.getValue()))
                .toList()
        );
    }

    private static ApplicationException invalid() {
        return new ApplicationException(StandardErrorCode.VALIDATION_FAILED);
    }
}
