package com.yumpoo.platform.foundation.infrastructure.outbox;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "yumpoo.outbox")
public class OutboxProperties {

    private boolean enabled = true;
    @NotNull
    private Duration pollDelay = Duration.ofSeconds(1);
    @NotNull
    private Duration initialDelay = Duration.ofSeconds(1);
    @Min(1)
    @Max(1000)
    private int batchSize = 50;
    @Min(1)
    @Max(32)
    private int concurrency = 2;
    @NotNull
    private Duration leaseDuration = Duration.ofMinutes(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPollDelay() {
        return pollDelay;
    }

    public void setPollDelay(Duration pollDelay) {
        this.pollDelay = pollDelay;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }
}
