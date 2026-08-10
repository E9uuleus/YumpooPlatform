package com.yumpoo.platform.identityaccess.application.directory;

/** 可持久化或进入脱敏证据的稳定失败分类；不包含供应商原文。 */
public enum DirectorySnapshotFailure {
    SYSTEM_BUSY(true),
    INVALID_CREDENTIALS(false),
    ACCESS_TOKEN_REJECTED(false),
    RATE_LIMITED(true),
    PERMISSION_DENIED(false),
    UNTRUSTED_IP(false),
    PROVIDER_ERROR(false),
    TRANSPORT_ERROR(false),
    MALFORMED_RESPONSE(false),
    MALFORMED_MEMBER_LIST(false),
    MISSING_CURSOR(false),
    INVALID_CURSOR_TYPE(false),
    INVALID_CURSOR_VALUE(false),
    MALFORMED_MEMBER(false),
    CURSOR_LOOP(false),
    PAGE_LIMIT_EXCEEDED(false);

    private final boolean retryable;

    DirectorySnapshotFailure(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
