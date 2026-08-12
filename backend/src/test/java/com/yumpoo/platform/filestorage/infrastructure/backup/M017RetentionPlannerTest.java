package com.yumpoo.platform.filestorage.infrastructure.backup;

import com.yumpoo.platform.filestorage.infrastructure.backup.M017RetentionPlanner.Candidate;
import com.yumpoo.platform.filestorage.infrastructure.backup.M017RetentionPlanner.RetentionDecision;
import com.yumpoo.platform.filestorage.infrastructure.backup.M017RetentionPlanner.RetentionPlan;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class M017RetentionPlannerTest {

    @Test
    void selectsDailyWeeklyMonthlyGenerationsWithOverlappingLabels() {
        Instant latest = Instant.parse("2026-08-12T15:30:00Z");
        List<Candidate> candidates = new ArrayList<>();
        for (int day = 0; day < 220; day++) {
            candidates.add(candidate(latest.minus(day, ChronoUnit.DAYS), true, false));
        }

        RetentionPlan plan = M017RetentionPlanner.plan(candidates, ZoneId.of("Asia/Shanghai"));

        assertThat(plan.decisions().stream().filter(decision -> decision.labels().contains("daily"))).hasSize(14);
        assertThat(plan.decisions().stream().filter(decision -> decision.labels().contains("weekly"))).hasSize(8);
        assertThat(plan.decisions().stream().filter(decision -> decision.labels().contains("monthly"))).hasSize(6);
        assertThat(plan.decisions()).anySatisfy(decision ->
                assertThat(decision.labels()).contains("daily", "weekly", "monthly"));
        assertThat(plan.companyTimeZone()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void usesLatestVerifiedSetPerCompanyDayAndNeverPromotesFailedSets() {
        Candidate earlier = candidate(Instant.parse("2026-08-11T16:30:00Z"), true, false);
        Candidate later = candidate(Instant.parse("2026-08-12T14:00:00Z"), true, false);
        Candidate failedLatest = candidate(Instant.parse("2026-08-12T15:00:00Z"), false, false);

        RetentionPlan plan = M017RetentionPlanner.plan(
                List.of(earlier, later, failedLatest),
                ZoneId.of("Asia/Shanghai")
        );

        assertThat(decision(plan, later).labels()).contains("daily");
        assertThat(decision(plan, earlier).labels()).isEmpty();
        assertThat(decision(plan, failedLatest).labels()).isEmpty();
        assertThat(decision(plan, failedLatest).deletionEligible()).isTrue();
    }

    @Test
    void legalHoldPreventsDeletionWithoutTurningBadSetIntoAProtectedGeneration() {
        Candidate failedHeld = candidate(Instant.parse("2026-08-12T12:00:00Z"), false, true);

        RetentionDecision decision = decision(
                M017RetentionPlanner.plan(List.of(failedHeld), ZoneId.of("Asia/Shanghai")),
                failedHeld
        );

        assertThat(decision.labels()).isEmpty();
        assertThat(decision.legalHold()).isTrue();
        assertThat(decision.deletionEligible()).isFalse();
    }

    @Test
    void companyTimezoneControlsNaturalDayBuckets() {
        Candidate beforeShanghaiMidnight = candidate(Instant.parse("2026-08-12T15:59:00Z"), true, false);
        Candidate afterShanghaiMidnight = candidate(Instant.parse("2026-08-12T16:01:00Z"), true, false);

        RetentionPlan shanghai = M017RetentionPlanner.plan(
                List.of(beforeShanghaiMidnight, afterShanghaiMidnight),
                ZoneId.of("Asia/Shanghai")
        );
        RetentionPlan utc = M017RetentionPlanner.plan(
                List.of(beforeShanghaiMidnight, afterShanghaiMidnight),
                ZoneId.of("UTC")
        );

        assertThat(shanghai.decisions().stream().filter(item -> item.labels().contains("daily"))).hasSize(2);
        assertThat(utc.decisions().stream().filter(item -> item.labels().contains("daily"))).hasSize(1);
    }

    private static Candidate candidate(Instant instant, boolean verified, boolean legalHold) {
        return new Candidate(UUID.nameUUIDFromBytes(instant.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                instant, verified, legalHold);
    }

    private static RetentionDecision decision(RetentionPlan plan, Candidate candidate) {
        return plan.decisions().stream()
                .filter(item -> item.backupSetId().equals(candidate.backupSetId()))
                .findFirst()
                .orElseThrow();
    }
}
