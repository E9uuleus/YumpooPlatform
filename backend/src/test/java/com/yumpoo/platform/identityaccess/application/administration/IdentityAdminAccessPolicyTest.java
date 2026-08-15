package com.yumpoo.platform.identityaccess.application.administration;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.application.authorization.ManagedPlatformRole;
import com.yumpoo.platform.identityaccess.application.authorization.RoleGovernanceRepository;
import com.yumpoo.platform.identityaccess.application.authorization.RoleUserSnapshot;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncAdministrationUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityAdminAccessPolicyTest {

    private static final UUID COMPANY_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000111");

    @Test
    void bothAdministratorRolesCanReadButOnlyCompanyAdminCanWrite() {
        RoleGovernanceRepository repository = mock(RoleGovernanceRepository.class);
        IdentityAdminAccessPolicy policy = new IdentityAdminAccessPolicy(repository);

        when(repository.findUser(COMPANY_ID, USER_ID))
                .thenReturn(Optional.of(user(Set.of(ManagedPlatformRole.APP_MANAGER))));
        assertThat(policy.requireReader(COMPANY_ID, USER_ID).userId()).isEqualTo(USER_ID);
        assertDenied(() -> policy.requireCompanyAdmin(COMPANY_ID, USER_ID));

        when(repository.findUser(COMPANY_ID, USER_ID))
                .thenReturn(Optional.of(user(Set.of(ManagedPlatformRole.COMPANY_ADMIN))));
        assertThat(policy.requireReader(COMPANY_ID, USER_ID).userId()).isEqualTo(USER_ID);
        assertThat(policy.requireCompanyAdmin(COMPANY_ID, USER_ID).userId()).isEqualTo(USER_ID);
    }

    @Test
    void ordinaryOrUnavailableMemberIsDenied() {
        RoleGovernanceRepository repository = mock(RoleGovernanceRepository.class);
        IdentityAdminAccessPolicy policy = new IdentityAdminAccessPolicy(repository);

        when(repository.findUser(COMPANY_ID, USER_ID))
                .thenReturn(Optional.of(user(Set.of())));
        assertDenied(() -> policy.requireReader(COMPANY_ID, USER_ID));

        when(repository.findUser(COMPANY_ID, USER_ID))
                .thenReturn(Optional.of(new RoleUserSnapshot(
                        USER_ID, COMPANY_ID, "ACTIVE", "DISABLED", 0, 0,
                        Set.of(ManagedPlatformRole.COMPANY_ADMIN))));
        assertDenied(() -> policy.requireReader(COMPANY_ID, USER_ID));
    }

    @Test
    void disabledIntegrationReturnsDependencyUnavailableAfterAuthorization() {
        IdentityAdminAccessPolicy policy = mock(IdentityAdminAccessPolicy.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DirectorySyncAdministrationUseCase> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ManualDirectorySyncService service = new ManualDirectorySyncService(policy, provider);

        assertThatThrownBy(() -> service.execute(
                COMPANY_ID, USER_ID, UUID.randomUUID().toString(), "m111-disabled"))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(StandardErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    private static RoleUserSnapshot user(Set<ManagedPlatformRole> roles) {
        return new RoleUserSnapshot(
                USER_ID, COMPANY_ID, "ACTIVE", "ENABLED", 0, 0, roles);
    }

    private static void assertDenied(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(StandardErrorCode.ACCESS_DENIED));
    }
}
