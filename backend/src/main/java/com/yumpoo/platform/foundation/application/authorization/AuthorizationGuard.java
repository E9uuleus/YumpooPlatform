package com.yumpoo.platform.foundation.application.authorization;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.domain.authorization.AuthorizationDecision;

import java.util.Objects;

public final class AuthorizationGuard {

    private AuthorizationGuard() {
    }

    public static void requireAllowed(AuthorizationDecision decision) {
        switch (Objects.requireNonNull(decision, "decision must not be null")) {
            case ALLOW -> {
            }
            case DENY_VISIBLE -> throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
            case DENY_HIDDEN -> throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        }
    }
}
