package com.yumpoo.platform.foundation.application.request;

/**
 * requestId 在传输层与应用层之间共享的最小契约。
 */
public final class RequestIdContext {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String ATTRIBUTE_NAME = RequestIdContext.class.getName() + ".requestId";

    private RequestIdContext() {
    }
}
