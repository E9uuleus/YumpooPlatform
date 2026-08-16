package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMaintenanceUseCase;
import com.yumpoo.platform.identityaccess.application.authorization.ManagedPlatformRole;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMutationResult;
import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentStatus;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningOutcome;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningResult;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureProvisioner;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureState;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureStateQuery;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.identity.EmploymentStatus;
import com.yumpoo.platform.organization.api.CompanyConfigurationSnapshot;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mock.env.MockEnvironment;

import java.time.DayOfWeek;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class M113FixtureRunnerTest {

    @Test
    void requiresTheExplicitFixtureOptInProperty() {
        ConditionalOnProperty condition = M113FixtureRunner.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(condition.prefix()).isEqualTo("yumpoo.verification.m1-13");
        assertThat(condition.name()).containsExactly("fixture-enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    void rejectsProductionEvenWhenLocalProfileIsAlsoActive() {
        MockEnvironment environment = enabledEnvironment("local", "m1-13-e2e", "prod");

        assertThatThrownBy(() -> runner(environment, pristine()).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forbidden in prod");
    }

    @Test
    void rejectsMissingRequiredProfilesAndControlledProvider() {
        MockEnvironment missingProfile = enabledEnvironment("local");
        assertThatThrownBy(() -> runner(missingProfile, pristine()).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires m1-13-e2e");

        MockEnvironment disabledProvider = enabledEnvironment("test", "m1-13-e2e")
                .withProperty("yumpoo.auth.controlled.enabled", "false");
        assertThatThrownBy(() -> runner(disabledProvider, pristine()).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("controlled authentication");
    }

    @Test
    void refusesToOverwriteExistingIdentityFacts() {
        IdentityAcceptanceFixtureStateQuery state = mock(IdentityAcceptanceFixtureStateQuery.class);
        when(state.current()).thenReturn(new IdentityAcceptanceFixtureState(1, 1, 0));

        assertThatThrownBy(() -> runner(
                enabledEnvironment("local", "m1-13-e2e"),
                state
        ).run(arguments())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pristine identity facts");
    }

    @Test
    void provisionsBothMembersAndUsesGovernedPortsForRoles() {
        MockEnvironment environment = enabledEnvironment("local", "m1-13-e2e");
        IdentityAcceptanceFixtureStateQuery state = pristine();
        CompanyConfigurationQuery company = mock(CompanyConfigurationQuery.class);
        IdentityAcceptanceFixtureProvisioner provisioner = mock(
                IdentityAcceptanceFixtureProvisioner.class
        );
        PlatformRoleMaintenanceUseCase maintenance = mock(PlatformRoleMaintenanceUseCase.class);
        PlatformRoleCommandPort roleCommands = mock(PlatformRoleCommandPort.class);
        UUID companyId = UUID.randomUUID();
        UUID controlledId = UUID.randomUUID();
        UUID backupId = UUID.randomUUID();
        Instant changedAt = Instant.parse("2026-08-15T03:00:00Z");
        when(company.current()).thenReturn(new CompanyConfigurationSnapshot(
                companyId, "Yumpoo", ZoneId.of("Asia/Shanghai"), DayOfWeek.MONDAY, 480, 0
        ));
        when(provisioner.provision(eq("member-m113"), any())).thenReturn(member(controlledId));
        when(provisioner.provision(eq("backup-m113"), any())).thenReturn(member(backupId));
        when(maintenance.execute(any())).thenReturn(new PlatformRoleMutationResult(
                UUID.randomUUID(), companyId, backupId, ManagedPlatformRole.APP_MANAGER,
                RoleAssignmentStatus.ACTIVE, 0, 1, 1, changedAt
        ));

        new M113FixtureRunner(
                environment,
                state,
                company,
                provisioner,
                maintenance,
                roleCommands,
                Clock.fixed(changedAt, ZoneId.of("UTC"))
        ).run(arguments());

        verify(provisioner).provision("member-m113", "M1-13 Controlled Company Admin");
        verify(provisioner).provision("backup-m113", "M1-13 Backup App Manager");
        verify(maintenance).execute(any());
        verify(roleCommands).grant(any(PlatformRoleGrantCommand.class));
    }

    private static M113FixtureRunner runner(
            MockEnvironment environment,
            IdentityAcceptanceFixtureStateQuery state
    ) {
        return new M113FixtureRunner(
                environment,
                state,
                mock(CompanyConfigurationQuery.class),
                mock(IdentityAcceptanceFixtureProvisioner.class),
                mock(PlatformRoleMaintenanceUseCase.class),
                mock(PlatformRoleCommandPort.class),
                Clock.systemUTC()
        );
    }

    private static IdentityAcceptanceFixtureStateQuery pristine() {
        IdentityAcceptanceFixtureStateQuery state = mock(IdentityAcceptanceFixtureStateQuery.class);
        when(state.current()).thenReturn(new IdentityAcceptanceFixtureState(0, 0, 0));
        return state;
    }

    private static MockEnvironment enabledEnvironment(String... profiles) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("yumpoo.auth.controlled.enabled", "true")
                .withProperty("yumpoo.auth.controlled.corp-id", "corp-m113")
                .withProperty("yumpoo.auth.controlled.member-id", "member-m113")
                .withProperty("yumpoo.verification.m1-13.backup-member-id", "backup-m113");
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private static ApplicationArguments arguments() {
        return mock(ApplicationArguments.class);
    }

    private static DirectoryMemberProvisioningResult member(UUID userId) {
        return new DirectoryMemberProvisioningResult(
                userId,
                UUID.randomUUID(),
                EmploymentStatus.ACTIVE,
                AccountStatus.ENABLED,
                0,
                0,
                DirectoryMemberProvisioningOutcome.CREATED
        );
    }
}
