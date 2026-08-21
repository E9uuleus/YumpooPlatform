package com.yumpoo.platform.identityaccess.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yumpoo.auth.local")
public final class LocalAuthenticationProperties {

    private boolean enabled;
    private String memberId;
    private String displayName;
    private String backupMemberId;
    private String backupDisplayName;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMemberId() {
        return memberId;
    }

    public void setMemberId(String memberId) {
        this.memberId = trim(memberId);
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = trim(displayName);
    }

    public String getBackupMemberId() {
        return backupMemberId;
    }

    public void setBackupMemberId(String backupMemberId) {
        this.backupMemberId = trim(backupMemberId);
    }

    public String getBackupDisplayName() {
        return backupDisplayName;
    }

    public void setBackupDisplayName(String backupDisplayName) {
        this.backupDisplayName = trim(backupDisplayName);
    }

    void validateForEnabled() {
        if (!enabled) {
            return;
        }
        require(memberId, 256, "local member id");
        require(displayName, 128, "local display name");
        require(backupMemberId, 256, "local backup member id");
        require(backupDisplayName, 128, "local backup display name");
        if (memberId.equals(backupMemberId)) {
            throw new IllegalStateException("Local authentication members must be distinct");
        }
    }

    private static void require(String value, int maximumLength, String field) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalStateException(field + " is invalid");
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }
}
