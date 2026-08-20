package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record ProjectMemberMutation(
        UUID companyId, UUID projectId, UUID userId, Long expectedMembershipVersion,
        UUID actorUserId, String reason
) {}
