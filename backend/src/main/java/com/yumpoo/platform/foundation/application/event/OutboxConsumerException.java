package com.yumpoo.platform.foundation.application.event;

import java.util.regex.Pattern;

/**
 * 消费者可显式给出的脱敏失败分类。
 */
public class OutboxConsumerException extends RuntimeException {

    private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,79}$");

    private final String errorCode;
    private final boolean retryable;

    public OutboxConsumerException(String errorCode, boolean retryable) {
        super(errorCode);
        if (errorCode == null || !ERROR_CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("errorCode must be a stable uppercase code");
        }
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
