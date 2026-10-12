package com.yumpoo.platform.foundation.application.scheduling;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduledJobServiceTest {
    @Test
    void nextRunUsesCompanyTimezoneAndSkipsMissedSlots() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        assertThat(ScheduledJobService.nextRunAt("0 0 2 * * *", zone, Instant.parse("2026-10-12T19:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-13T18:00:00Z"));
        assertThat(ScheduledJobService.nextRunAt("0 0 2 * * *", zone, Instant.parse("2026-10-16T05:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-16T18:00:00Z"));
        assertThatThrownBy(() -> ScheduledJobService.nextRunAt("invalid cron", zone, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
