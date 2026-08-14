package com.yumpoo.platform.identityaccess.application.directory;

import com.yumpoo.platform.identityaccess.domain.identity.ProfileHash;

import java.util.Objects;

public record WeComMemberProfile(
        String externalUserId,
        String displayName,
        DirectoryOptionalField email,
        DirectoryOptionalField mobile,
        String departmentSummary,
        ProfileHash rawProfileHash
) {

    public WeComMemberProfile {
        externalUserId = normalizeRequired(externalUserId, 256, "externalUserId");
        displayName = normalizeRequired(displayName, 200, "displayName");
        email = normalizedField(email, 320, "email");
        mobile = normalizedField(mobile, 64, "mobile");
        departmentSummary = normalizeOptional(
                departmentSummary,
                1000,
                "departmentSummary"
        );
        Objects.requireNonNull(rawProfileHash, "rawProfileHash must not be null");
    }

    @Override
    public String toString() {
        return "WeComMemberProfile[REDACTED]";
    }

    private static String normalizeRequired(String value, int maxLength, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static DirectoryOptionalField normalizedField(
            DirectoryOptionalField field,
            int maxLength,
            String fieldName
    ) {
        Objects.requireNonNull(field, fieldName + " must not be null");
        if (field.state() != DirectoryOptionalField.State.PRESENT) {
            return field;
        }
        String normalized = normalizeOptional(field.value(), maxLength, fieldName);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " PRESENT value must not be blank");
        }
        return DirectoryOptionalField.present(normalized);
    }
}
