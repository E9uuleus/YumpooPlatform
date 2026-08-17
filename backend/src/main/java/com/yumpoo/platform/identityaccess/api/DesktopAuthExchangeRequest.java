package com.yumpoo.platform.identityaccess.api;

public record DesktopAuthExchangeRequest(
        String state,
        String handoffCode,
        String codeVerifier
) {
    @Override
    public String toString() {
        return "DesktopAuthExchangeRequest[credentials=REDACTED]";
    }
}
