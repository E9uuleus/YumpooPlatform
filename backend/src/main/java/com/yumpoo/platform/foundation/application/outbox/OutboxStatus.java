package com.yumpoo.platform.foundation.application.outbox;

public enum OutboxStatus {
    PENDING,
    PROCESSING,
    RETRY,
    COMPLETED,
    DEAD
}
