package com.yumpoo.platform.identityaccess.application.administration;

import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncCounts;

import java.time.Instant;
import java.util.UUID;

public record DirectorySyncRunView(
        UUID runId,
        String triggerType,
        UUID triggeredByUserId,
        String triggeredByDisplayName,
        String phase,
        String status,
        String cursorTerminationMode,
        int pageCount,
        boolean scanComplete,
        DirectorySyncCounts counts,
        String errorCode,
        String errorSummary,
        String requestId,
        long rowVersion,
        Instant startedAt,
        Instant finishedAt
) {
}
