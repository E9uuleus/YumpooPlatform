package com.yumpoo.platform.foundation.infrastructure.time;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClockConfigurationTest {

    @Test
    void applicationClockUsesPostgresMicrosecondPrecision() {
        assertThat(new ClockConfiguration().systemUtcClock().instant().getNano() % 1_000).isZero();
    }
}
