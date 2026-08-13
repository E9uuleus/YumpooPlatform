package com.yumpoo.platform.organization.api;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;

public record CompanyWeek(LocalDate startInclusive, LocalDate endInclusive) {

    public CompanyWeek {
        Objects.requireNonNull(startInclusive, "startInclusive must not be null");
        Objects.requireNonNull(endInclusive, "endInclusive must not be null");
        if (startInclusive.getDayOfWeek() != DayOfWeek.MONDAY
                || !endInclusive.equals(startInclusive.plusDays(6))) {
            throw new IllegalArgumentException("Company week must run from Monday through Sunday");
        }
    }
}
