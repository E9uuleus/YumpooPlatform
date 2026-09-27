package com.yumpoo.platform.identityaccess.application.authorization;

import java.time.Instant;
import java.util.UUID;

public record PlatformRoleChangeResult(UUID userId, MemberRoleTier role, MemberRoleTier previousRole,
        long userRowVersion, long authorizationVersion, Instant changedAt) {}
