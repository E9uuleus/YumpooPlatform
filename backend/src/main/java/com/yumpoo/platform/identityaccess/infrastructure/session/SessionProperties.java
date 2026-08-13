package com.yumpoo.platform.identityaccess.infrastructure.session;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "yumpoo.session")
public class SessionProperties {

    private Duration idleTimeout = Duration.ofHours(8);
    private Duration absoluteTimeout = Duration.ofDays(7);
    private Duration revokedRetention = Duration.ofHours(24);
    private Duration cleanupDelay = Duration.ofHours(1);
    private int purgeBatchSize = 500;
    private int purgeMaxBatches = 10;
    private String currentKeyVersion = "local-v1";
    private String currentKey = "";
    private String previousKeyVersion = "";
    private String previousKey = "";
    private String previousAcceptUntil = "";

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Duration getAbsoluteTimeout() {
        return absoluteTimeout;
    }

    public void setAbsoluteTimeout(Duration absoluteTimeout) {
        this.absoluteTimeout = absoluteTimeout;
    }

    public Duration getRevokedRetention() {
        return revokedRetention;
    }

    public void setRevokedRetention(Duration revokedRetention) {
        this.revokedRetention = revokedRetention;
    }

    public Duration getCleanupDelay() {
        return cleanupDelay;
    }

    public void setCleanupDelay(Duration cleanupDelay) {
        this.cleanupDelay = cleanupDelay;
    }

    public int getPurgeBatchSize() {
        return purgeBatchSize;
    }

    public void setPurgeBatchSize(int purgeBatchSize) {
        this.purgeBatchSize = purgeBatchSize;
    }

    public int getPurgeMaxBatches() {
        return purgeMaxBatches;
    }

    public void setPurgeMaxBatches(int purgeMaxBatches) {
        this.purgeMaxBatches = purgeMaxBatches;
    }

    public String getCurrentKeyVersion() {
        return currentKeyVersion;
    }

    public void setCurrentKeyVersion(String currentKeyVersion) {
        this.currentKeyVersion = currentKeyVersion;
    }

    public String getCurrentKey() {
        return currentKey;
    }

    public void setCurrentKey(String currentKey) {
        this.currentKey = currentKey;
    }

    public String getPreviousKeyVersion() {
        return previousKeyVersion;
    }

    public void setPreviousKeyVersion(String previousKeyVersion) {
        this.previousKeyVersion = previousKeyVersion;
    }

    public String getPreviousKey() {
        return previousKey;
    }

    public void setPreviousKey(String previousKey) {
        this.previousKey = previousKey;
    }

    public String getPreviousAcceptUntil() {
        return previousAcceptUntil;
    }

    public void setPreviousAcceptUntil(String previousAcceptUntil) {
        this.previousAcceptUntil = previousAcceptUntil;
    }
}
