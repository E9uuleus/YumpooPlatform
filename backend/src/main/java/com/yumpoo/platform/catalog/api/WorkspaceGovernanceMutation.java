package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record WorkspaceGovernanceMutation(
        UUID companyId,
        UUID workspaceId,
        long expectedRowVersion,
        UUID actorUserId
) {
}
