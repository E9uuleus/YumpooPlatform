package com.yumpoo.platform.foundation.application.request;

import java.util.regex.Pattern;

/**
 * requestId 在传输层与应用层之间共享的最小契约。
 */
public final class RequestIdContext {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String ATTRIBUTE_NAME = RequestIdContext.class.getName() + ".requestId";
    public static final int MAX_LENGTH = 64;
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$");

    private RequestIdContext() {
    }

    public static boolean isValid(String value) {
        return value != null && value.length() <= MAX_LENGTH && SAFE_ID.matcher(value).matches();
    }

    public static String requireValid(String value, String fieldName) {
        if (!isValid(value)) {
            throw new IllegalArgumentException(fieldName + " must be a safe correlation identifier");
        }
        return value;
    }
}
