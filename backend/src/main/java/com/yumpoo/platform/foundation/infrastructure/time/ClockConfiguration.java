package com.yumpoo.platform.foundation.infrastructure.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    Clock systemUtcClock() {
        return Clock.systemUTC();
    }
}
