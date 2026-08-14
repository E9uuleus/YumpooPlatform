package com.yumpoo.platform.identityaccess.application.administration;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.application.authorization.ManagedPlatformRole;
import com.yumpoo.platform.identityaccess.application.authorization.RoleGovernanceRepository;
import com.yumpoo.platform.identityaccess.application.authorization.RoleUserSnapshot;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class IdentityAdminAccessPolicy {

    private final RoleGovernanceRepository repository;

    public IdentityAdminAccessPolicy(RoleGovernanceRepository repository) {
        this.repository = repository;
    }

    public RoleUserSnapshot requireReader(UUID companyId, UUID actorUserId) {
        RoleUserSnapshot actor = requireAvailable(companyId, actorUserId);
        if (actor.activeRoles().stream().noneMatch(role ->
                role == ManagedPlatformRole.APP_MANAGER
                        || role == ManagedPlatformRole.COMPANY_ADMIN)) {
            throw denied();
        }
        return actor;
    }

    public RoleUserSnapshot requireCompanyAdmin(UUID companyId, UUID actorUserId) {
        RoleUserSnapshot actor = requireAvailable(companyId, actorUserId);
        if (!actor.activeRoles().contains(ManagedPlatformRole.COMPANY_ADMIN)) {
            throw denied();
        }
        return actor;
    }

    private RoleUserSnapshot requireAvailable(UUID companyId, UUID actorUserId) {
        RoleUserSnapshot actor = repository.findUser(companyId, actorUserId)
                .orElseThrow(IdentityAdminAccessPolicy::denied);
        if (!actor.available()) {
            throw denied();
        }
        return actor;
    }

    private static ApplicationException denied() {
        return new ApplicationException(StandardErrorCode.ACCESS_DENIED);
    }
}
