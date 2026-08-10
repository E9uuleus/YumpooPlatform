package com.yumpoo.platform.identityaccess.application.oauth;

public final class WeComAuthenticationFailedException extends RuntimeException {

    private static final String SAFE_MESSAGE = "WeCom authorization could not be verified";

    public WeComAuthenticationFailedException() {
        super(SAFE_MESSAGE);
    }

    public WeComAuthenticationFailedException(Throwable cause) {
        super(SAFE_MESSAGE, cause);
    }
}
