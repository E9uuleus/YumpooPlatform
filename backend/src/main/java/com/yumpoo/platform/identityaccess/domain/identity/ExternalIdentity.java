package com.yumpoo.platform.identityaccess.domain.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ExternalIdentity(
        UUID id,
        UUID companyId,
        UUID userId,
        ExternalIdentityProvider provider,
        String externalUserId,
        EmploymentStatus providerEmploymentStatus,
        ProfileHash rawProfileHash,
        Instant lastSeenAt,
        Instant createdAt,
        Instant updatedAt
) {

    public ExternalIdentity {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(externalUserId, "externalUserId must not be null");
        Objects.requireNonNull(providerEmploymentStatus, "providerEmploymentStatus must not be null");
        Objects.requireNonNull(rawProfileHash, "rawProfileHash must not be null");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (externalUserId.isBlank()
                || !externalUserId.equals(externalUserId.trim())
                || externalUserId.length() > 256) {
            throw new IllegalArgumentException("externalUserId is invalid");
        }
        if (updatedAt.isBefore(createdAt)
                || lastSeenAt.isBefore(createdAt)
                || lastSeenAt.isAfter(updatedAt)) {
            throw new IllegalArgumentException("external identity timestamps are inconsistent");
        }
    }

    @Override
    public String toString() {
        return "ExternalIdentity[id=" + id
                + ", companyId=" + companyId
                + ", userId=" + userId
                + ", provider=" + provider
                + ", providerEmploymentStatus=" + providerEmploymentStatus
                + ", identityData=REDACTED]";
    }
}
