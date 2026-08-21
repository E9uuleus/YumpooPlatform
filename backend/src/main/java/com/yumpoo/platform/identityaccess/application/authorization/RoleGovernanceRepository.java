package com.yumpoo.platform.identityaccess.application.authorization;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RoleGovernanceRepository {
    GovernanceStateSnapshot lockState(UUID companyId);

    Optional<RoleUserSnapshot> lockUser(UUID companyId, UUID userId);

    Optional<RoleUserSnapshot> findUser(UUID companyId, UUID userId);

    Optional<RoleUserSnapshot> findAvailableAppManager(UUID companyId);

    Optional<RoleAssignmentSnapshot> lockAssignment(
            UUID companyId, UUID assignmentId, ManagedPlatformRole expectedRole
    );

    Optional<RoleAssignmentSnapshot> findActiveAssignment(
            UUID companyId, UUID userId, ManagedPlatformRole role
    );

    boolean hasAppManagerHistory(UUID companyId);

    boolean hasAnyRoleHistory(UUID companyId);

    int countAvailableAppManagers(UUID companyId);

    RoleAssignmentSnapshot grant(
            UUID assignmentId, UUID companyId, UUID userId, ManagedPlatformRole role,
            String actorType, UUID actorUserId, String systemCode,
            String reasonReference, Instant now
    );

    RoleAssignmentSnapshot revoke(
            RoleAssignmentSnapshot assignment, UUID actorUserId,
            String reasonReference, Instant now
    );

    RoleUserSnapshot incrementAuthorizationVersion(UUID companyId, UUID userId, long expectedRowVersion);

    GovernanceStateSnapshot markAvailable(UUID companyId, boolean initialize, Instant now);

    GovernanceStateSnapshot markMissing(UUID companyId, Instant now);
}
