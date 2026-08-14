package com.yumpoo.platform.identityaccess.application.directory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DirectorySyncRunSnapshot(
        UUID runId,
        UUID companyId,
        DirectorySyncTriggerType triggerType,
        DirectorySyncRunPhase phase,
        DirectorySyncRunStatus status,
        DirectoryScanResult.CursorTerminationMode cursorTerminationMode,
        int pageCount,
        boolean scanComplete,
        DirectorySyncCounts counts,
        String errorCode,
        String requestId,
        long rowVersion,
        Instant startedAt,
        Instant finishedAt
) {
    public DirectorySyncRunSnapshot {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(triggerType, "triggerType must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(counts, "counts must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        if (pageCount < 0 || rowVersion < 0) {
            throw new IllegalArgumentException("pageCount and rowVersion must not be negative");
        }
        if (requestId == null
                || !requestId.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")) {
            throw new IllegalArgumentException("requestId is invalid");
        }
        if (scanComplete != (cursorTerminationMode != null)) {
            throw new IllegalArgumentException("confirmed scans require a termination mode");
        }
        boolean running = status == DirectorySyncRunStatus.RUNNING;
        if (running != (finishedAt == null)
                || running == (phase == DirectorySyncRunPhase.COMPLETED)) {
            throw new IllegalArgumentException("directory sync lifecycle is inconsistent");
        }
        if (status == DirectorySyncRunStatus.SUCCEEDED
                && (!scanComplete || errorCode != null)) {
            throw new IllegalArgumentException("successful sync snapshot is inconsistent");
        }
        if (status == DirectorySyncRunStatus.FAILED
                && (errorCode == null || !errorCode.matches("^[A-Z][A-Z0-9_]{0,79}$"))) {
            throw new IllegalArgumentException("failed sync snapshot requires a stable error code");
        }
        if (finishedAt != null && finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt must not precede startedAt");
        }
    }

    @Override
    public String toString() {
        return "DirectorySyncRunSnapshot[runId=" + runId
                + ", status=" + status
                + ", phase=" + phase
                + ", counts=" + counts + "]";
    }
}
