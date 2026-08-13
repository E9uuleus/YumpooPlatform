package com.yumpoo.platform.organization.domain;

import java.time.LocalDate;
import java.util.Objects;

public record CalendarDayOverride(
        LocalDate date,
        DayType dayType,
        Integer standardMinutes,
        Source source,
        String note,
        long rowVersion
) {

    public CalendarDayOverride {
        Objects.requireNonNull(date, "date must not be null");
        Objects.requireNonNull(dayType, "dayType must not be null");
        Objects.requireNonNull(source, "source must not be null");
        if (dayType == DayType.WORKDAY
                && standardMinutes != null
                && (standardMinutes < 1 || standardMinutes > 720)) {
            throw new IllegalArgumentException("WORKDAY standardMinutes must be null or between 1 and 720");
        }
        if (dayType == DayType.NON_WORKDAY && !Integer.valueOf(0).equals(standardMinutes)) {
            throw new IllegalArgumentException("NON_WORKDAY standardMinutes must be zero");
        }
        if (note != null && note.isBlank()) {
            throw new IllegalArgumentException("note must be null or non-blank");
        }
        if (rowVersion < 0) {
            throw new IllegalArgumentException("rowVersion must not be negative");
        }
    }

    public enum DayType {
        WORKDAY,
        NON_WORKDAY
    }

    public enum Source {
        MANUAL,
        IMPORT
    }
}
