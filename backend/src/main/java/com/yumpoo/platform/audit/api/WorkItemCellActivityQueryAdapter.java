package com.yumpoo.platform.audit.api;

import com.yumpoo.platform.audit.application.WorkItemCellActivityCursorCodec;
import com.yumpoo.platform.audit.application.WorkItemCellActivityRepository;
import com.yumpoo.platform.audit.application.WorkItemCellActivityStoredEvent;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class WorkItemCellActivityQueryAdapter implements WorkItemCellActivityQueryPort {
    private static final int DEFAULT_SIZE = 25;
    private static final int MAX_SIZE = 100;

    private final WorkItemCellActivityRepository repository;
    private final WorkItemCellActivityCursorCodec cursors;
    private final Clock clock;

    public WorkItemCellActivityQueryAdapter(WorkItemCellActivityRepository repository,
            WorkItemCellActivityCursorCodec cursors, Clock clock) {
        this.repository = repository;
        this.cursors = cursors;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public WorkItemCellActivityPage find(UUID companyId, UUID projectId, UUID workItemId,
            ZoneId timezone, DayOfWeek weekStartDay, WorkItemCellActivityQuery raw) {
        WorkItemCellActivityQuery query = raw == null
                ? new WorkItemCellActivityQuery(null, null, null, null, null) : raw;
        int size = query.size() == null ? DEFAULT_SIZE : query.size();
        if (size < 1 || size > MAX_SIZE) throw validation("size", "INVALID_PAGE_SIZE",
                "size 必须在 1 到 100 之间");

        Set<UUID> actors = normalized(query.actorUserIds());
        Set<String> columns = normalizedColumns(query.columns());
        WorkItemCellActivityCursorCodec.Cursor cursor = cursors.decode(query.cursor());
        Instant snapshotAt = cursor == null ? clock.instant() : cursor.snapshotAt();
        String fingerprint = fingerprint(companyId, projectId, workItemId, query.timeRange(),
                actors, columns, snapshotAt);
        if (cursor != null && !fingerprint.equals(cursor.fingerprint()))
            throw WorkItemCellActivityCursorCodec.invalidCursor();

        Window selectedWindow = window(query.timeRange(), snapshotAt, timezone, weekStartDay);
        WorkItemCellActivityRepository.Filters filters = filters(selectedWindow, snapshotAt,
                actors, columns);
        List<WorkItemCellActivityStoredEvent> rows = new ArrayList<>(repository.find(companyId,
                workItemId, filters, cursor == null ? null : cursor.anchor(), size + 1));
        boolean hasMore = rows.size() > size;
        if (hasMore) rows.removeLast();
        String nextCursor = hasMore && !rows.isEmpty()
                ? cursors.encode(new WorkItemCellActivityCursorCodec.Cursor(fingerprint, snapshotAt,
                        new WorkItemCellActivityRepository.CursorAnchor(rows.getLast().occurredAt(),
                                rows.getLast().id()))) : null;
        return new WorkItemCellActivityPage(rows.stream().map(this::entry).toList(), nextCursor,
                repository.acceptedFrom(), facets(companyId, workItemId, query.timeRange(),
                        snapshotAt, timezone, weekStartDay, actors, columns));
    }

    private WorkItemCellActivityFacets facets(UUID companyId, UUID workItemId,
            WorkItemCellActivityTimeRange selectedTime, Instant snapshotAt, ZoneId timezone,
            DayOfWeek weekStartDay, Set<UUID> selectedActors, Set<String> selectedColumns) {
        List<WorkItemCellActivityStoredEvent> history = repository.findForFacets(companyId,
                workItemId, filters(null, snapshotAt, Set.of(), Set.of()));

        List<WorkItemCellActivityFacets.TimeRangeFacet> times = new ArrayList<>();
        for (WorkItemCellActivityTimeRange range : WorkItemCellActivityTimeRange.values()) {
            long count = repository.findForFacets(companyId, workItemId,
                    filters(window(range, snapshotAt, timezone, weekStartDay), snapshotAt,
                            selectedActors, selectedColumns)).size();
            times.add(new WorkItemCellActivityFacets.TimeRangeFacet(range, count,
                    range == selectedTime));
        }

        Window selectedWindow = window(selectedTime, snapshotAt, timezone, weekStartDay);
        List<WorkItemCellActivityStoredEvent> actorRows = repository.findForFacets(companyId,
                workItemId, filters(selectedWindow, snapshotAt, Set.of(), selectedColumns));
        Map<UUID, Long> actorCounts = actorRows.stream()
                .filter(row -> row.actorUserId() != null)
                .collect(Collectors.groupingBy(WorkItemCellActivityStoredEvent::actorUserId,
                        Collectors.counting()));
        Map<UUID, String> actorNames = latest(history,
                WorkItemCellActivityStoredEvent::actorUserId,
                WorkItemCellActivityStoredEvent::actorDisplayName);
        selectedActors.forEach(id -> actorNames.putIfAbsent(id, "未知成员"));
        List<WorkItemCellActivityFacets.ActorFacet> actorFacets = actorNames.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .map(entry -> new WorkItemCellActivityFacets.ActorFacet(entry.getKey(),
                        entry.getValue(), actorCounts.getOrDefault(entry.getKey(), 0L),
                        selectedActors.contains(entry.getKey())))
                .sorted(Comparator.comparing(WorkItemCellActivityFacets.ActorFacet::displayName))
                .toList();

        List<WorkItemCellActivityStoredEvent> columnRows = repository.findForFacets(companyId,
                workItemId, filters(selectedWindow, snapshotAt, selectedActors, Set.of()));
        Map<String, Long> columnCounts = columnRows.stream().collect(Collectors.groupingBy(
                WorkItemCellActivityStoredEvent::columnCode, Collectors.counting()));
        LinkedHashSet<String> historicalColumns = history.stream()
                .map(WorkItemCellActivityStoredEvent::columnCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        historicalColumns.addAll(selectedColumns);
        List<WorkItemCellActivityFacets.ColumnFacet> columnFacets = historicalColumns.stream()
                .map(WorkItemCellActivityColumn::valueOf)
                .map(column -> new WorkItemCellActivityFacets.ColumnFacet(column,
                        columnCounts.getOrDefault(column.name(), 0L),
                        selectedColumns.contains(column.name())))
                .sorted(Comparator.comparingInt(facet -> facet.value().ordinal())).toList();
        return new WorkItemCellActivityFacets(times, actorFacets, columnFacets);
    }

    private WorkItemCellActivityEntry entry(WorkItemCellActivityStoredEvent row) {
        return new WorkItemCellActivityEntry(row.id(), row.eventType(),
                new ActivityActorView(row.actorType(), row.actorUserId(), row.actorDisplayName()),
                row.occurredAt(), WorkItemCellActivityColumn.valueOf(row.columnCode()),
                WorkItemCellActivityChangeType.valueOf(row.changeType()), value(row.beforeValue()),
                value(row.afterValue()), row.contentId(), row.contentDisplayName());
    }

    private static WorkItemCellActivityValue value(JsonNode value) {
        if (value == null || value.isNull()) return null;
        return new WorkItemCellActivityValue(WorkItemCellActivityValueType.valueOf(
                value.path("type").asText()), nullable(value, "referenceId"),
                value.path("displayName").asText(), nullable(value, "colorToken"));
    }

    private static String nullable(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static WorkItemCellActivityRepository.Filters filters(Window window,
            Instant snapshotAt, Set<UUID> actors, Set<String> columns) {
        return new WorkItemCellActivityRepository.Filters(window == null ? null : window.from(),
                window == null ? null : window.to(), snapshotAt, actors, columns);
    }

    private static Window window(WorkItemCellActivityTimeRange range, Instant anchor,
            ZoneId timezone, DayOfWeek weekStartDay) {
        if (range == null) return null;
        LocalDate date = anchor.atZone(timezone).toLocalDate();
        LocalDate from;
        LocalDate to;
        switch (range) {
            case TODAY -> { from = date; to = date.plusDays(1); }
            case YESTERDAY -> { from = date.minusDays(1); to = date; }
            case THIS_WEEK -> {
                int back = Math.floorMod(date.getDayOfWeek().getValue() - weekStartDay.getValue(), 7);
                from = date.minusDays(back); to = from.plusWeeks(1);
            }
            case THIS_MONTH -> { from = date.withDayOfMonth(1); to = from.plusMonths(1); }
            case THIS_YEAR -> { from = date.withDayOfYear(1); to = from.plusYears(1); }
            default -> throw new IllegalStateException();
        }
        return new Window(from.atStartOfDay(timezone).toInstant(),
                to.atStartOfDay(timezone).toInstant());
    }

    private static <K> Map<K, String> latest(List<WorkItemCellActivityStoredEvent> rows,
            Function<WorkItemCellActivityStoredEvent, K> key,
            Function<WorkItemCellActivityStoredEvent, String> display) {
        return rows.stream().sorted(Comparator.comparing(
                        WorkItemCellActivityStoredEvent::occurredAt).reversed())
                .filter(row -> key.apply(row) != null)
                .collect(Collectors.toMap(key, display, (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    private static <T> Set<T> normalized(List<T> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static Set<String> normalizedColumns(List<WorkItemCellActivityColumn> values) {
        return values == null ? Set.of() : values.stream().map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String fingerprint(UUID companyId, UUID projectId, UUID workItemId,
            WorkItemCellActivityTimeRange timeRange, Set<UUID> actors, Set<String> columns,
            Instant snapshotAt) {
        String raw = "v2|" + companyId + '|' + projectId + '|' + workItemId + '|' + timeRange
                + '|' + actors.stream().sorted().toList() + '|'
                + columns.stream().sorted().toList()
                + '|' + snapshotAt;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ApplicationException validation(String field, String code, String message) {
        return new ApplicationException(StandardErrorCode.VALIDATION_FAILED,
                StandardErrorCode.VALIDATION_FAILED.defaultMessage(),
                List.of(new FieldViolation(field, code, message)));
    }

    private record Window(Instant from, Instant to) {}
}
