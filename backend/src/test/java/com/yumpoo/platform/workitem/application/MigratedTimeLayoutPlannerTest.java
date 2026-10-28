package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.application.MigratedTimeLayoutPlanner.DayPlan;
import com.yumpoo.platform.workitem.application.MigratedTimeLayoutPlanner.Existing;
import com.yumpoo.platform.workitem.application.MigratedTimeLayoutPlanner.Outcome;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MigratedTimeLayoutPlannerTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private static Instant at(String time) { return DAY.atTime(LocalTime.parse(time)).atZone(ZONE).toInstant(); }
    private static Existing busy(String from, String to) { return new Existing(at(from), at(to), false); }
    private static Existing migrated(String from, String to) { return new Existing(at(from), at(to), true); }
    private static DayPlan plan(int minutes, Existing... sessions) {
        return MigratedTimeLayoutPlanner.plan(DAY, ZONE, TODAY, minutes, 540, 1080, List.of(sessions));
    }
    private static List<String> spans(DayPlan plan) {
        return plan.segments().stream().map(s -> LocalTime.ofInstant(s.startedAt(), ZONE) + "-" + LocalTime.ofInstant(s.stoppedAt(), ZONE)).toList();
    }
    private static List<String> codes(DayPlan plan) { return plan.issues().stream().map(MigratedTimeLayoutPlanner.Issue::code).toList(); }

    @Test
    void startsAtWorkdayStartOnAnEmptyDay() {
        var result = plan(480);
        assertThat(result.outcome()).isEqualTo(Outcome.IMPORT);
        assertThat(spans(result)).containsExactly("09:00-17:00");
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void splitsAcrossGapsAroundExistingSessions() {
        var result = plan(240, busy("10:00", "11:00"), busy("13:00", "14:00"));
        assertThat(spans(result)).containsExactly("09:00-10:00", "11:00-13:00", "14:00-15:00");
    }

    @Test
    void sessionsBeforeTheWorkdayStartOnlyPushTheCursorWhenTheyOverlapIt() {
        var result = plan(60, busy("07:00", "09:30"));
        assertThat(spans(result)).containsExactly("09:30-10:30");
    }

    @Test
    void skipsGapsShorterThanFifteenMinutes() {
        var result = plan(120, busy("09:10", "10:00"), busy("10:10", "12:00"));
        assertThat(spans(result)).containsExactly("12:00-14:00");
    }

    @Test
    void shortRemainderMayUseAGapShorterThanFifteenMinutes() {
        var result = plan(160, busy("10:00", "10:20"), busy("12:00", "20:00"));
        assertThat(spans(result)).containsExactly("09:00-10:00", "10:20-12:00");
        var tail = plan(70, busy("10:00", "10:20"), busy("10:30", "12:00"));
        assertThat(spans(tail)).containsExactly("09:00-10:00", "10:20-10:30");
    }

    @Test
    void rejectsDaysThatCannotFit() {
        var result = plan(600, busy("12:00", "23:59"));
        assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(result.segments()).isEmpty();
        assertThat(codes(result)).containsExactly("NOT_ENOUGH_FREE_TIME");
    }

    @Test
    void runningSessionHoldsTheRestOfTheDay() {
        var running = new Existing(at("12:00"), null, false);
        assertThat(spans(plan(180, running))).containsExactly("09:00-12:00");
        assertThat(plan(240, running).outcome()).isEqualTo(Outcome.ERROR);
    }

    @Test
    void warnsWhenLastSegmentEndsAfterWorkdayEnd() {
        var result = plan(600);
        assertThat(spans(result)).containsExactly("09:00-19:00");
        assertThat(codes(result)).containsExactly("PAST_WORKDAY_END");
    }

    @Test
    void layoutMayRunUntilMidnight() {
        var result = plan(900);
        assertThat(spans(result)).containsExactly("09:00-00:00");
        assertThat(result.outcome()).isEqualTo(Outcome.IMPORT);
        assertThat(plan(901).outcome()).isEqualTo(Outcome.ERROR);
    }

    @Test
    void replacesThePreviousMigratedTimeOfTheDay() {
        var result = plan(120, migrated("09:00", "12:00"), busy("14:00", "15:00"));
        assertThat(spans(result)).containsExactly("09:00-11:00");
        assertThat(result.previous()).hasSize(1);
        assertThat(result.previousMinutes()).isEqualTo(180);
        assertThat(codes(result)).containsExactly("REPLACES_PREVIOUS");
        assertThat(result.issues().getFirst().detail()).isEqualTo("180");
    }

    @Test
    void migratedSessionsStartingOnAnotherDayStillOccupyTime() {
        var yesterday = new Existing(DAY.minusDays(1).atTime(23, 0).atZone(ZONE).toInstant(), at("10:00"), true);
        var result = plan(60, yesterday);
        assertThat(spans(result)).containsExactly("10:00-11:00");
        assertThat(result.previous()).isEmpty();
    }

    @Test
    void zeroMinutesClearsThePreviousTime() {
        var result = plan(0, migrated("09:00", "10:30"));
        assertThat(result.outcome()).isEqualTo(Outcome.CLEAR);
        assertThat(result.segments()).isEmpty();
        assertThat(result.previous()).hasSize(1);
        assertThat(result.previousMinutes()).isEqualTo(90);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void flagsTodayAndFutureDatesButStillLaysThemOut() {
        var future = MigratedTimeLayoutPlanner.plan(TODAY, ZONE, TODAY, 60, 540, 1080, List.of());
        assertThat(future.outcome()).isEqualTo(Outcome.IMPORT);
        assertThat(codes(future)).containsExactly("FUTURE_DATE");
        assertThat(codes(MigratedTimeLayoutPlanner.plan(TODAY.minusDays(1), ZONE, TODAY, 60, 540, 1080, List.of()))).isEmpty();
    }

    @Test
    void honoursACustomWorkdayStart() {
        var result = MigratedTimeLayoutPlanner.plan(DAY, ZONE, TODAY, 60, 480, 1020, List.of(busy("08:00", "08:30")));
        assertThat(spans(result)).containsExactly("08:30-09:30");
    }
}
