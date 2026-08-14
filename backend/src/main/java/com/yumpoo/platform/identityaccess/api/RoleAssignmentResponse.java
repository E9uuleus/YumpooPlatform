package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentView;

import java.time.Instant;
import java.util.UUID;

public record RoleAssignmentResponse(
        UUID assignmentId,
        UUID userId,
        String role,
        String scopeType,
        UUID scopeId,
        String status,
        Instant grantedAt,
        Instant revokedAt,
        long rowVersion,
        String etag
) {
    static RoleAssignmentResponse from(RoleAssignmentView view) {
        return new RoleAssignmentResponse(
                view.assignmentId(), view.userId(), view.role().name(), view.scopeType(),
                view.scopeId(), view.status().name(), view.grantedAt(), view.revokedAt(),
                view.rowVersion(), StrongEtag.format(view.rowVersion()));
    }
}
