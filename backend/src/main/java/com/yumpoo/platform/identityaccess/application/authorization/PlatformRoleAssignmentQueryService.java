package com.yumpoo.platform.identityaccess.application.authorization;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.stereotype.Service;

@Service
public class PlatformRoleAssignmentQueryService implements PlatformRoleAssignmentQueryUseCase {

    private final RoleGovernanceRepository governanceRepository;
    private final RoleAssignmentQueryRepository queryRepository;

    public PlatformRoleAssignmentQueryService(
            RoleGovernanceRepository governanceRepository,
            RoleAssignmentQueryRepository queryRepository
    ) {
        this.governanceRepository = governanceRepository;
        this.queryRepository = queryRepository;
    }

    @Override
    public RoleAssignmentPage find(RoleAssignmentQuery query) {
        RoleUserSnapshot actor = governanceRepository.findUser(
                        query.companyId(), query.actorUserId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.ACCESS_DENIED));
        if (!actor.available()
                || actor.activeRoles().stream().noneMatch(role ->
                        role == ManagedPlatformRole.APP_MANAGER
                                || role == ManagedPlatformRole.COMPANY_ADMIN)) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        return queryRepository.find(query);
    }
}
