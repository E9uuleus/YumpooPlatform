package com.yumpoo.platform.identityaccess.application.oauth;

import java.util.Objects;

public record WeComMemberIdentity(String corpId, String memberId) {

    public WeComMemberIdentity {
        requireIdentifier(corpId, "corpId");
        requireIdentifier(memberId, "memberId");
    }

    @Override
    public String toString() {
        return "WeComMemberIdentity[REDACTED]";
    }

    private static void requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(name + " must be between 1 and 256 characters");
        }
    }
}
