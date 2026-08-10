package com.yumpoo.platform.foundation.application.outbox;

import java.util.regex.Pattern;

public record OutboxFailure(
        String consumerName,
        String errorCode,
        String exceptionType,
        boolean retryable
) {

    private static final Pattern CONSUMER = Pattern.compile("^[a-z][a-z0-9_.:-]{0,119}$");
    private static final Pattern ERROR = Pattern.compile("^[A-Z][A-Z0-9_]{0,79}$");

    public OutboxFailure {
        if (consumerName == null || !CONSUMER.matcher(consumerName).matches()) {
            throw new IllegalArgumentException("consumerName must be a stable lowercase code");
        }
        if (errorCode == null || !ERROR.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("errorCode must be a stable uppercase code");
        }
        if (exceptionType == null || exceptionType.isBlank() || exceptionType.length() > 160) {
            throw new IllegalArgumentException("exceptionType must be between 1 and 160 characters");
        }
    }
}
