package com.yumpoo.platform.workitem.application;

import java.time.LocalDate;
import java.time.LocalTime;

public record DueTimeChange(boolean supplied, LocalTime value) {
    public static DueTimeChange unchanged() {
        return new DueTimeChange(false, null);
    }

    public LocalTime resolve(LocalDate date, LocalTime previous) {
        if (date == null && supplied && value != null)
            throw new IllegalArgumentException("截止时间必须同时设置截止日期");
        return date == null ? null : supplied ? value : previous;
    }
}
