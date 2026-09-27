package com.yumpoo.platform.identityaccess.api;

import java.time.Instant;
import java.util.UUID;

public record PlatformRoleTierChangeResult(UUID userId, PlatformRoleTier role, PlatformRoleTier previousRole,
        long userRowVersion, long authorizationVersion, Instant changedAt) {}
