package com.yumpoo.platform.identityaccess.api;

public record DesktopAuthAttemptRequest(
        String state,
        String codeChallenge,
        String codeChallengeMethod
) {
    @Override
    public String toString() {
        return "DesktopAuthAttemptRequest[credentials=REDACTED]";
    }
}
