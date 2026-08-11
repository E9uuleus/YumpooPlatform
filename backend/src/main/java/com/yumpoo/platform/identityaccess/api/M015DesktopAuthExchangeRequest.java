package com.yumpoo.platform.identityaccess.api;

public record M015DesktopAuthExchangeRequest(
        String code,
        String state,
        String codeVerifier
) {

    @Override
    public String toString() {
        return "M015DesktopAuthExchangeRequest[credentials=REDACTED]";
    }
}
