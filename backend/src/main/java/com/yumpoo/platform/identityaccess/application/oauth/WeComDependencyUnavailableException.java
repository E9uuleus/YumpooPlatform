package com.yumpoo.platform.identityaccess.application.oauth;

public final class WeComDependencyUnavailableException extends RuntimeException {

    private static final String SAFE_MESSAGE = "WeCom identity service is unavailable";

    public WeComDependencyUnavailableException() {
        super(SAFE_MESSAGE);
    }

    public WeComDependencyUnavailableException(Throwable cause) {
        super(SAFE_MESSAGE, cause);
    }
}
