package com.yumpoo.platform.workitem.application;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.yumpoo.platform.workitem.application.TimeTrackingModels.*;

public final class TimeSessionFillPlanner {
    private TimeSessionFillPlanner() {}

    public record Change(Session before, Instant startedAt, Instant stoppedAt) {}
    public record Plan(List<TimeSessionFillEntry> created, List<Change> changed,
            List<Session> deleted, long skippedDurationMs) {}
    private record Range(Instant start, Instant stop) {}

    static List<TimeSessionFillEntry> mergeEntries(List<TimeSessionFillEntry> entries) {
        Map<TimeSubject,List<Range>> grouped=new LinkedHashMap<>();
        for(var entry:entries) grouped.computeIfAbsent(entry.subject(),subject -> new ArrayList<>())
                .add(new Range(entry.startedAt(),entry.stoppedAt()));
        var merged=new ArrayList<TimeSessionFillEntry>();
        grouped.forEach((subject,ranges) -> union(ranges).forEach(range -> merged.add(TimeSessionFillEntry.of(subject,range.start(),range.stop()))));
        return merged;
    }

    public static Plan plan(TimeSessionFillMode mode,List<TimeSessionFillEntry> entries,List<Session> sessions,Set<UUID> editableIds) {
        var merged=mergeEntries(entries);
        var effective=sessions.stream().filter(s -> s.deletedAt()==null && (s.stoppedAt()==null || s.stoppedAt().isAfter(s.startedAt()))).toList();
        var created=new ArrayList<TimeSessionFillEntry>();
        var changed=new ArrayList<Change>();
        var deleted=new ArrayList<Session>();
        if(mode==TimeSessionFillMode.REPLACE) {
            var targetSubjects=merged.stream().map(TimeSessionFillEntry::subject).collect(java.util.stream.Collectors.toSet());
            var replaced=union(merged.stream().map(entry -> new Range(entry.startedAt(),entry.stoppedAt())).toList());
            for(Session session:effective) {
                if(targetSubjects.contains(session.subject()) || !editableIds.contains(session.id())) continue;
                var remaining=subtract(new Range(session.startedAt(),session.stoppedAt()),replaced);
                if(remaining.isEmpty()) deleted.add(session);
                else {
                    var first=remaining.getFirst();
                    if(!first.start().equals(session.startedAt()) || !first.stop().equals(session.stoppedAt()))
                        changed.add(new Change(session,first.start(),first.stop()));
                    for(int i=1;i<remaining.size();i++) {
                        var part=remaining.get(i);
                        created.add(TimeSessionFillEntry.of(session.subject(),part.start(),part.stop()));
                    }
                }
            }
        }
        long skipped=0;
        for(var entry:merged) {
            var blocked=effective.stream().filter(s -> mode==TimeSessionFillMode.GAPS || s.subject().equals(entry.subject()))
                    .map(s -> new Range(s.startedAt(),s.stoppedAt())).toList();
            var gaps=subtract(new Range(entry.startedAt(),entry.stoppedAt()),blocked);
            long added=0;
            for(var gap:gaps) {
                created.add(TimeSessionFillEntry.of(entry.subject(),gap.start(),gap.stop()));
                added+=Duration.between(gap.start(),gap.stop()).toMillis();
            }
            skipped+=Duration.between(entry.startedAt(),entry.stoppedAt()).toMillis()-added;
        }
        return new Plan(created,changed,deleted,skipped);
    }

    private static List<Range> union(List<Range> ranges) {
        var result=new ArrayList<Range>();
        for(var range:ranges.stream().sorted(Comparator.comparing(Range::start)).toList()) {
            if(result.isEmpty() || range.start().isAfter(result.getLast().stop())) result.add(range);
            else {
                var previous=result.removeLast();
                result.add(new Range(previous.start(),previous.stop().isAfter(range.stop()) ? previous.stop() : range.stop()));
            }
        }
        return result;
    }

    private static List<Range> subtract(Range range,List<Range> blocked) {
        var result=new ArrayList<Range>();
        Instant cursor=range.start();
        for(var other:blocked.stream().sorted(Comparator.comparing(Range::start)).toList()) {
            if(other.stop()!=null && !other.stop().isAfter(cursor)) continue;
            if(!other.start().isBefore(range.stop())) break;
            if(other.start().isAfter(cursor)) result.add(new Range(cursor,other.start()));
            if(other.stop()==null || !other.stop().isBefore(range.stop())) { cursor=range.stop(); break; }
            if(other.stop().isAfter(cursor)) cursor=other.stop();
        }
        if(cursor.isBefore(range.stop())) result.add(new Range(cursor,range.stop()));
        return result;
    }
}
