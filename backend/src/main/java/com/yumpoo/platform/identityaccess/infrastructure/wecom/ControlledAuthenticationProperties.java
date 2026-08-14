package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yumpoo.auth.controlled")
public final class ControlledAuthenticationProperties {

    private boolean enabled;
    private String corpId;
    private String memberId;

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

    public String getMemberId() {
        return memberId;
    }

    public void setMemberId(String memberId) {
        this.memberId = trim(memberId);
    }

    public void validateForEnabled() {
        if (enabled && (invalid(corpId) || invalid(memberId))) {
            throw new IllegalStateException("Controlled identity provider configuration is invalid");
        }
    }

    private static boolean invalid(String value) {
        return value == null || value.isBlank() || value.length() > 256;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
