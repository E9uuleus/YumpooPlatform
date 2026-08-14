package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "yumpoo.wecom.directory")
public final class DirectorySyncWeComProperties {

    private boolean enabled;
    private String corpId;
    private String directorySecret;
    private String profileSecret;
    private int pageSize = 1000;
    private Duration leaseDuration = Duration.ofMinutes(5);
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(20);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCorpId() {
        return corpId;
    }

    public void setCorpId(String corpId) {
        this.corpId = trim(corpId);
    }

    public String getDirectorySecret() {
        return directorySecret;
    }

    public void setDirectorySecret(String directorySecret) {
        this.directorySecret = trim(directorySecret);
    }

    public String getProfileSecret() {
        return profileSecret;
    }

    public void setProfileSecret(String profileSecret) {
        this.profileSecret = trim(profileSecret);
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public void validateForEnabled() {
        if (!enabled) {
            return;
        }
        if (blank(corpId)
                || blank(directorySecret)
                || blank(profileSecret)
                || directorySecret.equals(profileSecret)
                || pageSize < 1
                || pageSize > 10_000
                || invalid(leaseDuration)
                || invalid(connectTimeout)
                || invalid(readTimeout)
                || connectTimeout.compareTo(leaseDuration) >= 0
                || readTimeout.compareTo(leaseDuration) >= 0) {
            throw new IllegalStateException("M1-04 WeCom directory configuration is invalid");
        }
    }

    private static boolean invalid(Duration duration) {
        return duration == null || duration.isZero() || duration.isNegative();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
