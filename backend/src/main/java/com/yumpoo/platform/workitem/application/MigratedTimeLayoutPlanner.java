package com.yumpoo.platform.workitem.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Lays one member-day of migrated minutes into the free gaps of that day, counted from the member's workday start. */
public final class MigratedTimeLayoutPlanner {
    private MigratedTimeLayoutPlanner() {}

    static final Duration MIN_GAP = Duration.ofMinutes(15);

    public enum Outcome { IMPORT, CLEAR, ERROR }

    /** A session of the member that may occupy the day; {@code stoppedAt} is null while the timer is running. */
    public record Existing(Instant startedAt, Instant stoppedAt, boolean migrated) {}
    public record Segment(Instant startedAt, Instant stoppedAt) {}
    public record Issue(String code, String detail) {}
    /** {@code previous} holds the day's own migrated sessions, which the plan replaces. */
    public record DayPlan(Outcome outcome, List<Segment> segments, List<Existing> previous, long previousMinutes, List<Issue> issues) {}

    public static DayPlan plan(LocalDate workDate, ZoneId zone, LocalDate today, int minutes, int workdayStartMinute,
            int workdayEndMinute, List<Existing> sessions) {
        Instant dayStart = workDate.atStartOfDay(zone).toInstant();
        Instant dayEnd = workDate.plusDays(1).atStartOfDay(zone).toInstant();
        var previous = sessions.stream().filter(s -> s.migrated() && !s.startedAt().isBefore(dayStart) && s.startedAt().isBefore(dayEnd)).toList();
        long previousMinutes = Math.round(previous.stream().mapToLong(s -> Duration.between(s.startedAt(), endOf(s, dayEnd)).toMillis()).sum() / 60000.0);
        var issues = new ArrayList<Issue>();
        // Recorded time may not end in the future, so today and later are rejected outright, clearing included.
        if (!workDate.isBefore(today)) {
            issues.add(new Issue("FUTURE_DATE", null));
            return new DayPlan(Outcome.ERROR, List.of(), previous, previousMinutes, issues);
        }
        if (minutes == 0) return new DayPlan(Outcome.CLEAR, List.of(), previous, previousMinutes, issues);

        Instant start = workDate.atStartOfDay().plusMinutes(workdayStartMinute).atZone(zone).toInstant();
        var busy = sessions.stream().filter(s -> !previous.contains(s))
                .map(s -> new Segment(s.startedAt(), endOf(s, dayEnd)))
                .filter(s -> s.stoppedAt().isAfter(start) && s.startedAt().isBefore(dayEnd))
                .sorted(Comparator.comparing(Segment::startedAt)).toList();
        Duration remaining = Duration.ofMinutes(minutes);
        var segments = new ArrayList<Segment>();
        Instant cursor = start;
        for (int i = 0; i <= busy.size() && remaining.isPositive(); i++) {
            Instant gapEnd = i < busy.size() ? busy.get(i).startedAt() : dayEnd;
            if (gapEnd.isAfter(dayEnd)) gapEnd = dayEnd;
            Duration gap = gapEnd.isAfter(cursor) ? Duration.between(cursor, gapEnd) : Duration.ZERO;
            Duration least = remaining.compareTo(MIN_GAP) < 0 ? remaining : MIN_GAP;
            if (gap.compareTo(least) >= 0) {
                Duration take = gap.compareTo(remaining) < 0 ? gap : remaining;
                segments.add(new Segment(cursor, cursor.plus(take)));
                remaining = remaining.minus(take);
            }
            if (i < busy.size() && busy.get(i).stoppedAt().isAfter(cursor)) cursor = busy.get(i).stoppedAt();
        }
        if (remaining.isPositive()) {
            issues.add(new Issue("NOT_ENOUGH_FREE_TIME", null));
            return new DayPlan(Outcome.ERROR, List.of(), previous, previousMinutes, issues);
        }
        Instant workdayEnd = workDate.atStartOfDay().plusMinutes(workdayEndMinute).atZone(zone).toInstant();
        if (segments.getLast().stoppedAt().isAfter(workdayEnd)) issues.add(new Issue("PAST_WORKDAY_END", null));
        if (previousMinutes > 0) issues.add(new Issue("REPLACES_PREVIOUS", Long.toString(previousMinutes)));
        return new DayPlan(Outcome.IMPORT, List.copyOf(segments), previous, previousMinutes, issues);
    }

    /** A running session holds the rest of the day. */
    private static Instant endOf(Existing session, Instant dayEnd) {
        return session.stoppedAt() == null ? dayEnd : session.stoppedAt();
    }
}
