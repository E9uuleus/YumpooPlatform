package com.yumpoo.platform.foundation.application.outbox;

public record OutboxWorkerIdentity(String value) {

    public OutboxWorkerIdentity {
        if (value == null || value.isBlank() || value.length() > 80) {
            throw new IllegalArgumentException("worker identity must be between 1 and 80 characters");
        }
    }
}
