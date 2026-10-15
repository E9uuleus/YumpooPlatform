package com.yumpoo.platform.workitem.application;

import java.time.Instant;
import java.util.*;
import static com.yumpoo.platform.workitem.application.TimeTrackingModels.Session;

public final class TimeShareCalculator {
    private TimeShareCalculator() {}

    public record Share(UUID sessionId, UUID companyId, UUID userId, UUID projectId, UUID workItemId,
            Instant startedAt, Instant stoppedAt, int concurrency) {}

    /** Splits [from, to) into segments; to == null means unbounded. */
    public static List<Share> segments(List<Session> sessions, Instant from, Instant to) {
        var effective=sessions.stream().filter(s -> s.deletedAt()==null
                && (s.stoppedAt()==null || s.stoppedAt().isAfter(s.startedAt()))
                && (s.stoppedAt()==null || s.stoppedAt().isAfter(from))
                && (to==null || s.startedAt().isBefore(to))).toList();
        var cuts=new TreeSet<Instant>();
        cuts.add(from);
        if(to!=null) cuts.add(to);
        for(Session s:effective) {
            if(s.startedAt().isAfter(from) && (to==null || s.startedAt().isBefore(to))) cuts.add(s.startedAt());
            if(s.stoppedAt()!=null && s.stoppedAt().isAfter(from) && (to==null || s.stoppedAt().isBefore(to))) cuts.add(s.stoppedAt());
        }
        var points=new ArrayList<>(cuts);
        var shares=new ArrayList<Share>();
        for(int i=0;i<points.size()-1;i++) {
            Instant a=points.get(i), b=points.get(i+1);
            var covering=effective.stream().filter(s -> !s.startedAt().isAfter(a)
                    && (s.stoppedAt()==null || !s.stoppedAt().isBefore(b))).toList();
            append(shares,covering,a,b);
        }
        if(to==null) {
            Instant last=points.getLast();
            append(shares,effective.stream().filter(s -> !s.startedAt().isAfter(last) && s.stoppedAt()==null).toList(),last,null);
        }
        return shares;
    }

    private static void append(List<Share> shares,List<Session> sessions,Instant start,Instant stop) {
        for(Session s:sessions) shares.add(new Share(s.id(),s.companyId(),s.userId(),s.projectId(),s.workItemId(),start,stop,sessions.size()));
    }
}
