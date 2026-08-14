package com.yumpoo.platform.identityaccess.application.directory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DirectoryProfileMapper {

    private static final int MAX_SUMMARY_LENGTH = 1000;

    private DirectoryProfileMapper() {
    }

    public static WeComMemberProfile map(
            WeComRawMemberProfile raw,
            Map<Long, String> departmentNames
    ) {
        Objects.requireNonNull(raw, "raw must not be null");
        Objects.requireNonNull(departmentNames, "departmentNames must not be null");
        List<String> names = new ArrayList<>();
        for (Long departmentId : raw.departmentIds().stream().sorted().toList()) {
            String name = departmentNames.get(departmentId);
            if (name == null || name.isBlank()) {
                throw new DirectorySyncException(
                        "DIRECTORY_DEPARTMENT_UNAVAILABLE",
                        "A member department was outside the readable directory scope"
                );
            }
            names.add(name.trim());
        }
        String summary = names.isEmpty() ? null : String.join("、", names);
        if (summary != null && summary.length() > MAX_SUMMARY_LENGTH) {
            throw new DirectorySyncException(
                    "DIRECTORY_DEPARTMENT_SUMMARY_TOO_LONG",
                    "The normalized department summary exceeded the storage limit"
            );
        }
        return new WeComMemberProfile(
                raw.externalUserId(),
                raw.displayName(),
                raw.email(),
                raw.mobile(),
                summary,
                DirectoryCanonicalHash.profile(raw, summary)
        );
    }
}
