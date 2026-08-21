package com.yumpoo.platform.identityaccess.application.authorization;

import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.identityaccess.application.audit.IdentitySecurityAuditRecorder;
import com.yumpoo.platform.identityaccess.application.session.SessionRevocationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformRoleManagementServiceTest {

    private static final UUID COMPANY_ID = UUID.randomUUID();
    private static final UUID EXISTING_MANAGER_ID = UUID.randomUUID();
    private static final UUID PREFERRED_MANAGER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-21T08:00:00Z");

    @Test
    void reusesAnExistingAvailableManager() {
        Fixture fixture = fixture();
        when(fixture.coordinator().lock(COMPANY_ID)).thenReturn(availability(
                GovernanceLifecycleStatus.AVAILABLE,
                1
        ));
        when(fixture.repository().findAvailableAppManager(COMPANY_ID)).thenReturn(
                Optional.of(new RoleUserSnapshot(
                        EXISTING_MANAGER_ID,
                        COMPANY_ID,
                        "ACTIVE",
                        "ENABLED",
                        7,
                        3,
                        Set.of(ManagedPlatformRole.APP_MANAGER)
                ))
        );

        MaintenanceRoleActor actor = fixture.service().ensureAvailableAppManager(
                COMPANY_ID,
                PREFERRED_MANAGER_ID,
                "local fixture"
        );

        assertThat(actor).isEqualTo(new MaintenanceRoleActor(EXISTING_MANAGER_ID, 7));
    }

    @Test
    void selectsBootstrapOnlyForUninitializedGovernanceAndBreakGlassOtherwise() {
        assertRecoveryMode(GovernanceLifecycleStatus.UNINITIALIZED, MaintenanceRoleMode.BOOTSTRAP);
        assertRecoveryMode(GovernanceLifecycleStatus.MISSING, MaintenanceRoleMode.BREAK_GLASS);
        assertRecoveryMode(GovernanceLifecycleStatus.AVAILABLE, MaintenanceRoleMode.BREAK_GLASS);
    }

    private static void assertRecoveryMode(
            GovernanceLifecycleStatus lifecycleStatus,
            MaintenanceRoleMode expectedMode
    ) {
        Fixture fixture = fixture();
        PlatformRoleManagementService service = spy(fixture.service());
        when(fixture.coordinator().lock(COMPANY_ID)).thenReturn(availability(
                lifecycleStatus,
                0
        ));
        doReturn(mutation()).when(service).execute(any(MaintenanceRoleCommand.class));

        MaintenanceRoleActor actor = service.ensureAvailableAppManager(
                COMPANY_ID,
                PREFERRED_MANAGER_ID,
                "local fixture"
        );

        ArgumentCaptor<MaintenanceRoleCommand> command = ArgumentCaptor.forClass(
                MaintenanceRoleCommand.class
        );
        verify(service).execute(command.capture());
        assertThat(command.getValue().mode()).isEqualTo(expectedMode);
        assertThat(actor).isEqualTo(new MaintenanceRoleActor(PREFERRED_MANAGER_ID, 1));
    }

    private static AvailabilitySnapshot availability(
            GovernanceLifecycleStatus lifecycleStatus,
            int count
    ) {
        return new AvailabilitySnapshot(
                new GovernanceStateSnapshot(COMPANY_ID, lifecycleStatus, 0, 0),
                count
        );
    }

    private static PlatformRoleMutationResult mutation() {
        return new PlatformRoleMutationResult(
                UUID.randomUUID(),
                COMPANY_ID,
                PREFERRED_MANAGER_ID,
                ManagedPlatformRole.APP_MANAGER,
                RoleAssignmentStatus.ACTIVE,
                0,
                1,
                1,
                NOW
        );
    }

    private static Fixture fixture() {
        RoleGovernanceRepository repository = mock(RoleGovernanceRepository.class);
        AppManagerAvailabilityCoordinator coordinator = mock(
                AppManagerAvailabilityCoordinator.class
        );
        return new Fixture(
                new PlatformRoleManagementService(
                        repository,
                        coordinator,
                        mock(SessionRevocationService.class),
                        mock(TransactionalEventPort.class),
                        mock(IdempotentCommandExecutor.class),
                        mock(ObjectMapper.class),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        mock(IdentitySecurityAuditRecorder.class)
                ),
                repository,
                coordinator
        );
    }

    private record Fixture(
            PlatformRoleManagementService service,
            RoleGovernanceRepository repository,
            AppManagerAvailabilityCoordinator coordinator
    ) {
    }
}
