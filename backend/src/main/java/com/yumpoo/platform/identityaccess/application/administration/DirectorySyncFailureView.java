package com.yumpoo.platform.identityaccess.application.administration;

public record DirectorySyncFailureView(
        String maskedMemberReference,
        String action,
        String result,
        String errorCode
) {
}
