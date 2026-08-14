package com.yumpoo.platform.identityaccess.application.directory;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;

public record DirectoryScanResult(
        List<String> externalUserIds,
        CursorTerminationMode terminationMode,
        int pageCount,
        String memberSetHash,
        String pageTrajectoryHash
) {

    public DirectoryScanResult {
        Objects.requireNonNull(externalUserIds, "externalUserIds must not be null");
        externalUserIds = List.copyOf(externalUserIds);
        for (String externalUserId : externalUserIds) {
            if (externalUserId == null || externalUserId.isBlank()
                    || externalUserId.length() > 256) {
                throw new IllegalArgumentException("externalUserIds contains an invalid ID");
            }
        }
        if (new HashSet<>(externalUserIds).size() != externalUserIds.size()) {
            throw new IllegalArgumentException("externalUserIds contains duplicates");
        }
        Objects.requireNonNull(terminationMode, "terminationMode must not be null");
        if (pageCount < 1) {
            throw new IllegalArgumentException("pageCount must be positive");
        }
        if (!hash(memberSetHash) || !hash(pageTrajectoryHash)) {
            throw new IllegalArgumentException("directory scan hashes are invalid");
        }
    }

    @Override
    public String toString() {
        return "DirectoryScanResult[memberCount=" + externalUserIds.size()
                + ", terminationMode=" + terminationMode
                + ", pageCount=" + pageCount + "]";
    }

    public enum CursorTerminationMode {
        EXPLICIT_EMPTY,
        OMITTED_CONFIRMED
    }

    private static boolean hash(String value) {
        return value != null && value.matches("^[0-9a-f]{64}$");
    }
}
