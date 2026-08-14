package com.yumpoo.platform.identityaccess.application.authorization;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class GovernanceStateQueryService {

    private final RoleGovernanceRepository governanceRepository;
    private final GovernanceStateQueryRepository queryRepository;

    public GovernanceStateQueryService(
            RoleGovernanceRepository governanceRepository,
            GovernanceStateQueryRepository queryRepository
    ) {
        this.governanceRepository = governanceRepository;
        this.queryRepository = queryRepository;
    }

    public GovernanceMemberState findMember(UUID companyId, UUID actorUserId, UUID targetUserId) {
        requireReader(companyId, actorUserId);
        return queryRepository.findMember(companyId, targetUserId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    public RoleAssignmentSnapshot findAssignment(
            UUID companyId,
            UUID actorUserId,
            UUID assignmentId,
            ManagedPlatformRole expectedRole
    ) {
        requireReader(companyId, actorUserId);
        return queryRepository.findAssignment(companyId, assignmentId, expectedRole)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireReader(UUID companyId, UUID actorUserId) {
        RoleUserSnapshot actor = governanceRepository.findUser(companyId, actorUserId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.ACCESS_DENIED));
        if (!actor.available() || actor.activeRoles().stream().noneMatch(role ->
                role == ManagedPlatformRole.APP_MANAGER
                        || role == ManagedPlatformRole.COMPANY_ADMIN)) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
    }
}
