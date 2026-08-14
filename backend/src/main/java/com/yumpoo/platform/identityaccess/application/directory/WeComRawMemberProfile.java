package com.yumpoo.platform.identityaccess.application.directory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** 企微成员详情白名单；不得序列化或进入日志。 */
public record WeComRawMemberProfile(
        String externalUserId,
        String displayName,
        DirectoryOptionalField email,
        DirectoryOptionalField mobile,
        List<Long> departmentIds
) {

    public WeComRawMemberProfile {
        externalUserId = required(externalUserId, 256, "externalUserId");
        displayName = required(displayName, 200, "displayName");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(mobile, "mobile must not be null");
        Objects.requireNonNull(departmentIds, "departmentIds must not be null");
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long departmentId : departmentIds) {
            if (departmentId == null || departmentId <= 0) {
                throw new IllegalArgumentException("departmentIds contains an invalid ID");
            }
            unique.add(departmentId);
        }
        departmentIds = List.copyOf(unique);
    }

    @Override
    public String toString() {
        return "WeComRawMemberProfile[REDACTED]";
    }

    private static String required(String value, int maxLength, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
