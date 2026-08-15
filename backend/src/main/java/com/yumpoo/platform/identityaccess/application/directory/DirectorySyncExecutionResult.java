package com.yumpoo.platform.identityaccess.application.directory;

import java.util.Objects;

public record DirectorySyncExecutionResult(
        DirectorySyncRunSnapshot snapshot,
        DirectorySyncClaimDisposition disposition
) {
    public DirectorySyncExecutionResult {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(disposition, "disposition must not be null");
    }
}
