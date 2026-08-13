package com.yumpoo.platform.identityaccess.domain.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record User(
        UUID id,
        UUID companyId,
        EmploymentStatus employmentStatus,
        AccountStatus accountStatus,
        String displayName,
        String email,
        String mobile,
        String departmentSummary,
        Instant directorySyncedAt,
        Instant leftAt,
        String leftReason,
        Instant accountDisabledAt,
        UUID accountDisabledByUserId,
        String accountDisabledReason,
        long rowVersion,
        Instant createdAt,
        Instant updatedAt
) {

    public User {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(employmentStatus, "employmentStatus must not be null");
        Objects.requireNonNull(accountStatus, "accountStatus must not be null");
        requireText(displayName, 200, "displayName");
        requireOptionalText(email, 320, "email");
        requireOptionalText(mobile, 64, "mobile");
        requireOptionalText(departmentSummary, 1000, "departmentSummary");
        Objects.requireNonNull(directorySyncedAt, "directorySyncedAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (rowVersion < 0) {
            throw new IllegalArgumentException("rowVersion must not be negative");
        }
        if (updatedAt.isBefore(createdAt)
                || directorySyncedAt.isBefore(createdAt)
                || directorySyncedAt.isAfter(updatedAt)) {
            throw new IllegalArgumentException("user timestamps are inconsistent");
        }
        if ((leftAt == null) != (leftReason == null)) {
            throw new IllegalArgumentException("left facts must be complete");
        }
        if (employmentStatus == EmploymentStatus.LEFT && leftAt == null) {
            throw new IllegalArgumentException("LEFT user requires left facts");
        }
        boolean disabledFactsAbsent = accountDisabledAt == null
                && accountDisabledByUserId == null
                && accountDisabledReason == null;
        boolean disabledFactsComplete = accountDisabledAt != null
                && accountDisabledByUserId != null
                && accountDisabledReason != null;
        if (!disabledFactsAbsent && !disabledFactsComplete) {
            throw new IllegalArgumentException("account-disabled facts must be complete");
        }
        if (accountStatus == AccountStatus.DISABLED && !disabledFactsComplete) {
            throw new IllegalArgumentException("DISABLED user requires account-disabled facts");
        }
    }

    @Override
    public String toString() {
        return "User[id=" + id
                + ", companyId=" + companyId
                + ", employmentStatus=" + employmentStatus
                + ", accountStatus=" + accountStatus
                + ", rowVersion=" + rowVersion
                + ", personalData=REDACTED]";
    }

    private static void requireText(String value, int maxLength, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank() || !value.equals(value.trim()) || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireOptionalText(String value, int maxLength, String field) {
        if (value != null) {
            requireText(value, maxLength, field);
        }
    }
}
