package com.yumpoo.platform.workitem.application;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.yumpoo.platform.workitem.application.TimeTrackingModels.Session;

class TimeShareCalculatorTest {
    private static final Instant BASE=Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void nonOverlappingSessionsHaveOneShareEach() {
        var rows=TimeShareCalculator.segments(List.of(session(0,10),session(20,30)),at(0),at(30));
        assertThat(rows).hasSize(2).allMatch(row -> row.concurrency()==1);
    }
    @Test
    void partialOverlapSplitsIntoOneTwoOne() {
        var rows=TimeShareCalculator.segments(List.of(session(0,20),session(10,30)),at(0),at(30));
        assertThat(rows).extracting(TimeShareCalculator.Share::concurrency).containsExactly(1,2,2,1);
        assertThat(rows).extracting(TimeShareCalculator.Share::startedAt).containsExactly(at(0),at(10),at(10),at(20));
    }
    @Test
    void runningSessionKeepsAnOpenSegment() {
        var rows=TimeShareCalculator.segments(List.of(session(0,10),session(5,null)),at(0),null);
        assertThat(rows).extracting(TimeShareCalculator.Share::concurrency).containsExactly(1,2,2,1);
        assertThat(rows.getLast().startedAt()).isEqualTo(at(10));
        assertThat(rows.getLast().stoppedAt()).isNull();
    }
    @Test
    void clipsAtBothWindowBoundaries() {
        var rows=TimeShareCalculator.segments(List.of(session(0,30),session(5,20)),at(10),at(15));
        assertThat(rows).hasSize(2).allMatch(row -> row.startedAt().equals(at(10)) && row.stoppedAt().equals(at(15)) && row.concurrency()==2);
    }
    @Test
    void categoryRecordsShareConcurrencyWithWorkItemsAndKeepTheirCategory() {
        var meeting=new Session(UUID.randomUUID(),UUID.randomUUID(),null,null,UUID.randomUUID(),at(0),at(20),"MANUAL",0,null,null,TimeCategory.MEETING);
        var rows=TimeShareCalculator.segments(List.of(meeting,session(10,30)),at(0),at(30));
        assertThat(rows).extracting(TimeShareCalculator.Share::concurrency).containsExactly(1,2,2,1);
        assertThat(rows).filteredOn(row -> row.sessionId().equals(meeting.id()))
                .allMatch(row -> row.timeCategory()==TimeCategory.MEETING && row.projectId()==null && row.workItemId()==null);
    }
    private static Instant at(int seconds) { return BASE.plusSeconds(seconds); }
    private static Session session(int start,Integer stop) {
        return new Session(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),at(start),stop==null ? null : at(stop),"TIMER",0,null,null);
    }
}
