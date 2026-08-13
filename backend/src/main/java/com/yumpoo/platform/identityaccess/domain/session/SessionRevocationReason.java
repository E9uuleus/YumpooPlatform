package com.yumpoo.platform.identityaccess.domain.session;

public enum SessionRevocationReason {
    ROTATED,
    USER_LOGOUT,
    EMPLOYMENT_LEFT,
    ACCOUNT_DISABLED,
    AUTHORIZATION_CHANGED,
    ADMIN_FORCED,
    IDLE_EXPIRED,
    ABSOLUTE_EXPIRED
}
