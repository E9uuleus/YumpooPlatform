package com.yumpoo.platform.organization.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface CompanyCalendarCommand {
    long yearVersion(int year);
    /** Replaces every explicit day of the year when {@code expectedVersion} is current; false on a stale version. */
    boolean replaceYear(int year, long expectedVersion, List<Day> days, UUID actorUserId, Instant now);
    record Day(LocalDate date, CalendarDayType dayType, Integer standardMinutes, String note, CalendarDaySource source) {}
}
