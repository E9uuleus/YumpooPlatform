package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record ProjectOwnerReassignmentMutation(
        UUID companyId, UUID projectId, long expectedProjectVersion,
        UUID newOwnerUserId, UUID actorUserId
) {}
