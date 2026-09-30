package com.yumpoo.platform.identityaccess.application.session;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ActiveSessionQueryRepository {
    List<ActiveSessionRecord> find(UUID companyId, Instant now);
    ActiveSessionCounts count(Instant now);

    record ActiveSessionCounts(long online, long idle) {}

    record ActiveSessionRecord(
        UUID id,
        UUID companyId,
        UUID userId,
        String displayName,
        String clientType,
        String clientVersion,
        Instant issuedAt,
        Instant lastSeenAt,
        Instant expiresAt
    ) {}
}
