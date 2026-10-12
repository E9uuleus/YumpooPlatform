package com.yumpoo.platform.foundation.application.scheduling;

public record ScheduledJobResult(ScheduledJobOutcome outcome, String errorCode) {
    public static ScheduledJobResult succeeded() {
        return new ScheduledJobResult(ScheduledJobOutcome.SUCCEEDED, null);
    }
}
