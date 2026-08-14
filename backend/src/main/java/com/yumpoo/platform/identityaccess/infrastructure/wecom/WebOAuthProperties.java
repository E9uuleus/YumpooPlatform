package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "yumpoo.wecom.oauth")
public final class WebOAuthProperties {

    private boolean enabled;
    private String corpId;
    private String agentId;
    private String appSecret;
    private URI callbackUri;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(20);
    private Duration cleanupDelay = Duration.ofHours(1);
    private int purgeBatchSize = 500;
    private int purgeMaxBatches = 10;

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

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = trim(agentId);
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = trim(appSecret);
    }

    public URI getCallbackUri() {
        return callbackUri;
    }

    public void setCallbackUri(URI callbackUri) {
        this.callbackUri = callbackUri;
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

    public void validateForEnabled() {
        if (!enabled) {
            return;
        }
        if (invalidIdentifier(corpId)
                || isBlank(agentId)
                || !agentId.chars().allMatch(Character::isDigit)
                || isBlank(appSecret)
                || !secureCallback(callbackUri)
                || invalid(connectTimeout)
                || invalid(readTimeout)) {
            throw new IllegalStateException("WeCom Web OAuth configuration is invalid");
        }
    }

    public void validateCleanup() {
        if (invalid(cleanupDelay) || purgeBatchSize < 1 || purgeMaxBatches < 1) {
            throw new IllegalStateException("OAuth cleanup configuration is invalid");
        }
    }

    private static boolean secureCallback(URI uri) {
        return uri != null
                && uri.isAbsolute()
                && "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && !uri.getHost().isBlank()
                && "/api/v1/auth/wecom/callback".equals(uri.getPath())
                && uri.getUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getFragment() == null;
    }

    private static boolean invalidIdentifier(String value) {
        return isBlank(value) || value.length() > 256;
    }

    private static boolean invalid(Duration duration) {
        return duration == null || duration.isZero() || duration.isNegative();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
