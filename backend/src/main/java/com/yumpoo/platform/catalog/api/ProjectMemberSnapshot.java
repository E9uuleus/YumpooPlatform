package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;

import java.time.Instant;
import java.util.UUID;

public record ProjectMemberSnapshot(
        UUID membershipId, UUID projectId, UUID userId, String displayName,
        String employmentStatus, String accountStatus, String membershipStatus, boolean owner,
        Instant joinedAt, UUID joinedByUserId, Instant removedAt, UUID removedByUserId,
        long rowVersion, String etag
) {
    public static ProjectMemberSnapshot of(
            UUID membershipId, UUID projectId, UUID userId, String displayName,
            String employmentStatus, String accountStatus, String membershipStatus, boolean owner,
            Instant joinedAt, UUID joinedByUserId, Instant removedAt, UUID removedByUserId,
            long rowVersion
    ) {
        return new ProjectMemberSnapshot(membershipId, projectId, userId, displayName,
                employmentStatus, accountStatus, membershipStatus, owner, joinedAt,
                joinedByUserId, removedAt, removedByUserId, rowVersion,
                StrongEtag.format(rowVersion));
    }
}
