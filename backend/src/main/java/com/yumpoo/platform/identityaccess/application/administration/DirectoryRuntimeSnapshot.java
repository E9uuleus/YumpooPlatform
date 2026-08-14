package com.yumpoo.platform.identityaccess.application.administration;

import java.time.Instant;
import java.util.UUID;

public record DirectoryRuntimeSnapshot(
        UUID activeRunId,
        Instant lastSuccessfulRunAt,
        Instant lastProblemAt,
        String lastProblemCode
) {
}
