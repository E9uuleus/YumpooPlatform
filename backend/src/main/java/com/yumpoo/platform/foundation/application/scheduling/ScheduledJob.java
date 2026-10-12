package com.yumpoo.platform.foundation.application.scheduling;

import java.util.UUID;

public interface ScheduledJob {
    String code();
    default boolean available() { return true; }
    ScheduledJobResult run(UUID companyId, String triggerKey, String requestId);
}
