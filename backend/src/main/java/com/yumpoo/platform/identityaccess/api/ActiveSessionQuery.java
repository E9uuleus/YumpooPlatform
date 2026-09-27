package com.yumpoo.platform.identityaccess.api;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public interface ActiveSessionQuery {
    List<Session> findActive(UUID companyId, Instant now);
    Counts countActive(Instant now);
    record Counts(long online, long idle) { }
    record Session(UUID id, UUID companyId, UUID userId, String displayName, String clientType,
            String clientVersion, Instant issuedAt, Instant lastSeenAt, Instant expiresAt) { }
}
