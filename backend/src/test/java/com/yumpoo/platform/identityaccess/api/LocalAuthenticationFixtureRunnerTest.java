package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.identityaccess.application.authorization.MaintenanceRoleActor;
import com.yumpoo.platform.identityaccess.application.authorization.ManagedPlatformRole;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMaintenanceUseCase;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningOutcome;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningResult;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureProvisioner;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.identity.EmploymentStatus;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAuthenticationFixtureRunnerTest {

    private static final Instant NOW = Instant.parse("2026-08-21T08:00:00Z");

    @Test
    void requiresExplicitOptInAndRejectsNonLocalOrProductionRuntime() {
        ConditionalOnProperty condition = LocalAuthenticationFixtureRunner.class
                .getAnnotation(ConditionalOnProperty.class);
        assertThat(condition.prefix()).isEqualTo("yumpoo.auth.local");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();

        IdentityAcceptanceFixtureProvisioner provisioner = mock(
                IdentityAcceptanceFixtureProvisioner.class
        );
        assertThatThrownBy(() -> runner(environment("test"), provisioner).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local profile");
        assertThatThrownBy(() -> runner(
                environment("local", "prod"),
                provisioner
        ).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forbidden in prod");
        verify(provisioner, never()).provision(any(), any(), any());
    }

    @Test
    void rejectsRemoteBindingAndRealIdentityProviders() {
        IdentityAcceptanceFixtureProvisioner provisioner = mock(
                IdentityAcceptanceFixtureProvisioner.class
        );
        MockEnvironment remote = environment("local")
                .withProperty("server.address", "0.0.0.0");
        assertThatThrownBy(() -> runner(remote, provisioner).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback-only");

        MockEnvironment wecom = environment("local")
                .withProperty("yumpoo.wecom.oauth.enabled", "true");
        assertThatThrownBy(() -> runner(wecom, provisioner).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot run with WeCom");
    }

    @Test
    void provisionsTheLocalAccountAndGrantsBothAdministrativeRoles() {
        UUID companyId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID backupId = UUID.randomUUID();
        CompanyConfigurationQuery company = mock(CompanyConfigurationQuery.class);
        IdentityAcceptanceFixtureProvisioner provisioner = mock(
                IdentityAcceptanceFixtureProvisioner.class
        );
        PlatformRoleMaintenanceUseCase maintenance = mock(PlatformRoleMaintenanceUseCase.class);
        PlatformRoleCommandPort roleCommands = mock(PlatformRoleCommandPort.class);
        PlatformRoleQuery roleQuery = mock(PlatformRoleQuery.class);
        when(company.current()).thenReturn(new CompanyConfigurationSnapshot(
                companyId,
                "Yumpoo",
                ZoneId.of("Asia/Shanghai"),
                DayOfWeek.MONDAY,
                480,
                0
        ));
        when(provisioner.provision(eq("local-admin"), eq("本地管理员"), any()))
                .thenReturn(member(adminId));
        when(provisioner.provision(eq("local-backup"), eq("本地备份管理员"), any()))
                .thenReturn(member(backupId));
        when(roleQuery.findActiveRoleCodes(companyId, adminId)).thenReturn(Set.of());
        when(roleQuery.findActiveRoleCodes(companyId, backupId)).thenReturn(Set.of());
        when(maintenance.ensureAvailableAppManager(companyId, backupId, REASON))
                .thenReturn(new MaintenanceRoleActor(backupId, 1));
        when(roleCommands.grant(any())).thenAnswer(invocation -> {
            PlatformRoleGrantCommand command = invocation.getArgument(0);
            long nextVersion = command.role() == PlatformRoleCode.COMPANY_ADMIN ? 1 : 2;
            return new PlatformRoleCommandReceipt(new PlatformRoleAssignmentMutation(
                    UUID.randomUUID(),
                    companyId,
                    adminId,
                    command.role(),
                    PlatformRoleAssignmentStatus.ACTIVE,
                    0,
                    nextVersion,
                    nextVersion,
                    NOW
            ), false);
        });

        configuredRunner(
                environment("local"),
                company,
                provisioner,
                maintenance,
                roleCommands,
                roleQuery
        ).run(arguments());

        verify(provisioner).provision("local-admin", "本地管理员", "Local Development");
        verify(provisioner).provision(
                "local-backup",
                "本地备份管理员",
                "Local Development"
        );
        verify(maintenance).ensureAvailableAppManager(companyId, backupId, REASON);
        verify(roleCommands, org.mockito.Mockito.times(2)).grant(any());
    }

    private static final String REASON = "Local development identity fixture";

    private static LocalAuthenticationFixtureRunner runner(
            MockEnvironment environment,
            IdentityAcceptanceFixtureProvisioner provisioner
    ) {
        return configuredRunner(
                environment,
                mock(CompanyConfigurationQuery.class),
                provisioner,
                mock(PlatformRoleMaintenanceUseCase.class),
                mock(PlatformRoleCommandPort.class),
                mock(PlatformRoleQuery.class)
        );
    }

    private static LocalAuthenticationFixtureRunner configuredRunner(
            MockEnvironment environment,
            CompanyConfigurationQuery company,
            IdentityAcceptanceFixtureProvisioner provisioner,
            PlatformRoleMaintenanceUseCase maintenance,
            PlatformRoleCommandPort roleCommands,
            PlatformRoleQuery roleQuery
    ) {
        LocalAuthenticationProperties properties = new LocalAuthenticationProperties();
        properties.setEnabled(true);
        properties.setMemberId("local-admin");
        properties.setDisplayName("本地管理员");
        properties.setBackupMemberId("local-backup");
        properties.setBackupDisplayName("本地备份管理员");
        return new LocalAuthenticationFixtureRunner(
                environment,
                properties,
                company,
                provisioner,
                maintenance,
                roleCommands,
                roleQuery,
                Clock.fixed(NOW, ZoneId.of("UTC"))
        );
    }

    private static MockEnvironment environment(String... profiles) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.address", "127.0.0.1");
        environment.setActiveProfiles(profiles);
        return environment;
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

    private static ApplicationArguments arguments() {
        return mock(ApplicationArguments.class);
    }
}
