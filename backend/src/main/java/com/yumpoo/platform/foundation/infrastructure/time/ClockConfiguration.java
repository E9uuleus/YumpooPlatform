package com.yumpoo.platform.foundation.infrastructure.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    /** PostgreSQL timestamptz 只保存到微秒：应用时间按同一精度截断，写入后读回的时间不会晚于同一请求里的 now。 */
    @Bean
    Clock systemUtcClock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
