package com.yumpoo.platform.workitem.application;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.yumpoo.platform.workitem.application.TimeTrackingModels.*;

class TimeSessionFillPlannerTest {
    private static final Instant BASE=Instant.parse("2026-10-14T00:00:00Z");
    private static final UUID COMPANY=UUID.randomUUID(), USER=UUID.randomUUID(), PROJECT=UUID.randomUUID();

    @Test
    void parallelOnlyFillsTheTargetsOwnGapsAndUnionsItsEntries() {
        UUID item=UUID.randomUUID();
        var plan=TimeSessionFillPlanner.plan(TimeSessionFillMode.PARALLEL,
                List.of(entry(item,0,30),entry(item,20,40)),
                List.of(session(item,10,20),session(UUID.randomUUID(),0,40)),Set.of());
        assertThat(plan.created()).containsExactly(entry(item,0,10),entry(item,20,40));
        assertThat(plan.skippedDurationMs()).isEqualTo(10000);
        assertThat(plan.changed()).isEmpty();
        assertThat(plan.deleted()).isEmpty();
    }

    @Test
    void replaceDeletesShortensAndSplitsEditableOtherItemsWhileKeepingTheRest() {
        UUID item=UUID.randomUUID();
        Session deleted=session(UUID.randomUUID(),12,18), shortened=session(UUID.randomUUID(),5,15);
        Session split=session(UUID.randomUUID(),0,30), protectedSession=session(UUID.randomUUID(),0,30);
        Session running=session(UUID.randomUUID(),0,null);
        var plan=TimeSessionFillPlanner.plan(TimeSessionFillMode.REPLACE,List.of(entry(item,10,20)),
                List.of(deleted,shortened,split,protectedSession,running),Set.of(deleted.id(),shortened.id(),split.id()));
        assertThat(plan.deleted()).containsExactly(deleted);
        assertThat(plan.changed()).containsExactly(new TimeSessionFillPlanner.Change(shortened,at(5),at(10)),
                new TimeSessionFillPlanner.Change(split,at(0),at(10)));
        assertThat(plan.created()).containsExactly(entry(split.workItemId(),20,30),entry(item,10,20));
        assertThat(plan.skippedDurationMs()).isZero();
    }

    @Test
    void gapsOnlyFillsTimeUncoveredByAnySessionIncludingRunningSessions() {
        UUID item=UUID.randomUUID();
        var plan=TimeSessionFillPlanner.plan(TimeSessionFillMode.GAPS,List.of(entry(item,-5,30)),
                List.of(session(UUID.randomUUID(),0,10),session(UUID.randomUUID(),5,15),session(UUID.randomUUID(),20,null)),Set.of());
        assertThat(plan.created()).containsExactly(entry(item,-5,0),entry(item,15,20));
        assertThat(plan.skippedDurationMs()).isEqualTo(25000);
        assertThat(plan.changed()).isEmpty();
        assertThat(plan.deleted()).isEmpty();
    }

    @Test
    void categoryRecordsOccupyGapsAndBlockOnlyTheSameCategoryInParallel() {
        UUID item=UUID.randomUUID();
        Session meeting=category(TimeCategory.MEETING,10,20);
        var gaps=TimeSessionFillPlanner.plan(TimeSessionFillMode.GAPS,List.of(entry(item,0,30)),List.of(meeting),Set.of());
        assertThat(gaps.created()).containsExactly(entry(item,0,10),entry(item,20,30));
        var parallel=TimeSessionFillPlanner.plan(TimeSessionFillMode.PARALLEL,
                List.of(categoryEntry(TimeCategory.MEETING,0,30),categoryEntry(TimeCategory.LEARNING,0,30)),
                List.of(meeting,session(item,0,30)),Set.of());
        assertThat(parallel.created()).containsExactly(categoryEntry(TimeCategory.MEETING,0,10),
                categoryEntry(TimeCategory.MEETING,20,30),categoryEntry(TimeCategory.LEARNING,0,30));
    }

    @Test
    void mergesPerSubjectSoAWorkItemAndACategoryNeverUnion() {
        UUID item=UUID.randomUUID();
        var merged=TimeSessionFillPlanner.mergeEntries(List.of(entry(item,0,10),categoryEntry(TimeCategory.BUSINESS,5,15),
                categoryEntry(TimeCategory.BUSINESS,10,20)));
        assertThat(merged).containsExactly(entry(item,0,10),categoryEntry(TimeCategory.BUSINESS,5,20));
    }

    @Test
    void replaceWithACategoryTrimsEditableWorkItemRecordsAndSplitsAroundIt() {
        Session work=session(UUID.randomUUID(),0,30);
        var plan=TimeSessionFillPlanner.plan(TimeSessionFillMode.REPLACE,List.of(categoryEntry(TimeCategory.BUSINESS_TRIP,10,20)),
                List.of(work),Set.of(work.id()));
        assertThat(plan.changed()).containsExactly(new TimeSessionFillPlanner.Change(work,at(0),at(10)));
        assertThat(plan.created()).containsExactly(entry(work.workItemId(),20,30),categoryEntry(TimeCategory.BUSINESS_TRIP,10,20));
    }

    private static Instant at(int seconds) { return BASE.plusSeconds(seconds); }
    private static TimeSessionFillEntry categoryEntry(TimeCategory category,int start,int stop) {
        return new TimeSessionFillEntry(null,at(start),at(stop),category, null);
    }
    private static Session category(TimeCategory category,int start,int stop) {
        return new Session(UUID.randomUUID(),COMPANY,null,null,USER,at(start),at(stop),"MANUAL",0,null,null,category, null);
    }
    private static TimeSessionFillEntry entry(UUID item,int start,int stop) { return new TimeSessionFillEntry(item,at(start),at(stop),null, null); }
    private static Session session(UUID item,int start,Integer stop) {
        return new Session(UUID.randomUUID(),COMPANY,PROJECT,item,USER,at(start),stop==null ? null : at(stop),"MANUAL",0,null,null);
    }
}
