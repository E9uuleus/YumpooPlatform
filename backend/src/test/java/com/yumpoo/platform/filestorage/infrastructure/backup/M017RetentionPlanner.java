package com.yumpoo.platform.filestorage.infrastructure.backup;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** 仅计算保留标签与删除资格，不执行文件删除。 */
public final class M017RetentionPlanner {

    private M017RetentionPlanner() {
    }

    public static RetentionPlan plan(List<Candidate> candidates, ZoneId companyZone) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(companyZone, "companyZone must not be null");
        Map<UUID, EnumSet<Label>> labels = new LinkedHashMap<>();
        candidates.forEach(candidate -> labels.put(candidate.backupSetId(), EnumSet.noneOf(Label.class)));

        List<Candidate> verified = candidates.stream()
                .filter(Candidate::verified)
                .sorted(Comparator.comparing(Candidate::verifiedAt).reversed())
                .toList();
        selectLatest(verified, candidate -> localDate(candidate, companyZone), 14, Label.DAILY, labels);
        selectLatest(verified, candidate -> isoWeek(candidate, companyZone), 8, Label.WEEKLY, labels);
        selectLatest(verified, candidate -> localDate(candidate, companyZone).withDayOfMonth(1),
                6, Label.MONTHLY, labels);

        List<RetentionDecision> decisions = candidates.stream()
                .sorted(Comparator.comparing(Candidate::verifiedAt).reversed())
                .map(candidate -> {
                    List<String> assigned = labels.get(candidate.backupSetId()).stream()
                            .sorted()
                            .map(Label::wireName)
                            .toList();
                    boolean protectedSet = !assigned.isEmpty() || candidate.legalHold();
                    return new RetentionDecision(
                            candidate.backupSetId(),
                            candidate.verifiedAt(),
                            candidate.verified(),
                            assigned,
                            candidate.legalHold(),
                            !protectedSet
                    );
                })
                .toList();
        return new RetentionPlan(1, "M0-17", companyZone.getId(), decisions);
    }

    private static <K> void selectLatest(
            List<Candidate> verified,
            Function<Candidate, K> bucket,
            int limit,
            Label label,
            Map<UUID, EnumSet<Label>> labels
    ) {
        Map<K, Candidate> latest = new LinkedHashMap<>();
        for (Candidate candidate : verified) {
            latest.putIfAbsent(bucket.apply(candidate), candidate);
            if (latest.size() == limit) {
                break;
            }
        }
        latest.values().forEach(candidate -> labels.get(candidate.backupSetId()).add(label));
    }

    private static LocalDate localDate(Candidate candidate, ZoneId zone) {
        return candidate.verifiedAt().atZone(zone).toLocalDate();
    }

    private static IsoWeek isoWeek(Candidate candidate, ZoneId zone) {
        LocalDate date = localDate(candidate, zone);
        WeekFields iso = WeekFields.ISO;
        return new IsoWeek(date.get(iso.weekBasedYear()), date.get(iso.weekOfWeekBasedYear()));
    }

    public record Candidate(UUID backupSetId, Instant verifiedAt, boolean verified, boolean legalHold) {
        public Candidate {
            Objects.requireNonNull(backupSetId, "backupSetId must not be null");
            Objects.requireNonNull(verifiedAt, "verifiedAt must not be null");
        }
    }

    public record RetentionPlan(
            int schemaVersion,
            String milestone,
            String companyTimeZone,
            List<RetentionDecision> decisions
    ) {
    }

    public record RetentionDecision(
            UUID backupSetId,
            Instant verifiedAt,
            boolean verified,
            List<String> labels,
            boolean legalHold,
            boolean deletionEligible
    ) {
    }

    private record IsoWeek(int year, int week) {
    }

    private enum Label {
        DAILY("daily"), WEEKLY("weekly"), MONTHLY("monthly");

        private final String wireName;

        Label(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }
}
