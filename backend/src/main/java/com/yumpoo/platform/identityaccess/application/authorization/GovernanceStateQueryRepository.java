package com.yumpoo.platform.identityaccess.application.authorization;

import java.util.Optional;
import java.util.UUID;

public interface GovernanceStateQueryRepository {

    Optional<GovernanceMemberState> findMember(UUID companyId, UUID userId);

    Optional<RoleAssignmentSnapshot> findAssignment(
            UUID companyId, UUID assignmentId, ManagedPlatformRole expectedRole
    );
}
