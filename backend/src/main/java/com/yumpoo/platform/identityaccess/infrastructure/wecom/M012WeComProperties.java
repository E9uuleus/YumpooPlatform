package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties(prefix = "yumpoo.m012.wecom")
public final class M012WeComProperties {

    private boolean enabled;
    private String corpId;
    private String agentId;
    private String appSecret;
    private URI callbackUri;
    private Set<String> allowedMemberIds = new LinkedHashSet<>();

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

    public Set<String> getAllowedMemberIds() {
        return Set.copyOf(allowedMemberIds);
    }

    public void setAllowedMemberIds(Set<String> allowedMemberIds) {
        this.allowedMemberIds = allowedMemberIds == null
                ? new LinkedHashSet<>()
                : allowedMemberIds.stream()
                .map(M012WeComProperties::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 仅在真实验证入口被显式启用时校验凭据，避免默认关闭状态要求本地持有密钥。
     */
    public void validateForEnabled() {
        if (!enabled) {
            return;
        }
        if (isBlank(corpId)
                || isBlank(agentId)
                || !agentId.chars().allMatch(Character::isDigit)
                || isBlank(appSecret)
                || !isSecureCallback(callbackUri)
                || allowedMemberIds.isEmpty()
                || allowedMemberIds.stream().anyMatch(M012WeComProperties::isBlank)) {
            throw new IllegalStateException("M0-12 WeCom live verification configuration is invalid");
        }
    }

    private static boolean isSecureCallback(URI callbackUri) {
        return callbackUri != null
                && callbackUri.isAbsolute()
                && "https".equalsIgnoreCase(callbackUri.getScheme())
                && callbackUri.getHost() != null
                && !callbackUri.getHost().isBlank()
                && "/_m0/m0-12/wecom/callback".equals(callbackUri.getPath())
                && (callbackUri.getRawQuery() == null || callbackUri.getRawQuery().isEmpty())
                && callbackUri.getUserInfo() == null
                && callbackUri.getFragment() == null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
