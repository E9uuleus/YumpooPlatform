package com.yumpoo.platform.identityaccess.application.bootstrap;

import com.yumpoo.platform.identityaccess.application.authorization.InitialRoleBootstrapResult;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMaintenanceUseCase;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberBinding;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningRepository;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryScanResult;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncAdministrationUseCase;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncClaimDisposition;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncCounts;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncExecutionResult;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncRunPhase;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncRunSnapshot;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncRunStatus;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncTriggerType;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.identity.EmploymentStatus;
import com.yumpoo.platform.identityaccess.domain.identity.ExternalIdentity;
import com.yumpoo.platform.identityaccess.domain.identity.ExternalIdentityProvider;
import com.yumpoo.platform.identityaccess.domain.identity.ProfileHash;
import com.yumpoo.platform.identityaccess.domain.identity.User;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InitialIdentityBootstrapServiceTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID DIRECTORY_RUN_ID = UUID.fromString("15000000-0000-4000-8000-000000000001");
    private static final UUID APP_MANAGER_ID = UUID.fromString("15000000-0000-4000-8000-000000000002");
    private static final UUID COMPANY_ADMIN_ID = UUID.fromString("15000000-0000-4000-8000-000000000003");

    private DirectorySyncAdministrationUseCase syncUseCase;
    private DirectoryMemberProvisioningRepository memberRepository;
    private PlatformRoleMaintenanceUseCase roleMaintenance;
    private InitialIdentityBootstrapService service;

    @BeforeEach
    void setUp() {
        CompanyConfigurationQuery companyQuery = mock(CompanyConfigurationQuery.class);
        when(companyQuery.current()).thenReturn(new CompanyConfigurationSnapshot(
                COMPANY_ID, "Yumpoo", ZoneId.of("Asia/Shanghai"),
                DayOfWeek.MONDAY, 480, 0));
        syncUseCase = mock(DirectorySyncAdministrationUseCase.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DirectorySyncAdministrationUseCase> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(syncUseCase);
        memberRepository = mock(DirectoryMemberProvisioningRepository.class);
        roleMaintenance = mock(PlatformRoleMaintenanceUseCase.class);
        service = new InitialIdentityBootstrapService(
                companyQuery, provider, memberRepository, roleMaintenance);
    }

    @Test
    void successfulFullSyncResolvesDistinctMembersAndAtomicallyBootstrapsRoles() {
        when(syncUseCase.executeWithDisposition(any())).thenReturn(new DirectorySyncExecutionResult(
                snapshot(DirectorySyncRunStatus.SUCCEEDED, 2, null),
                DirectorySyncClaimDisposition.NEW));
        when(memberRepository.findByExternalIdentity(
                COMPANY_ID, ExternalIdentityProvider.WECOM, "app-manager"))
                .thenReturn(Optional.of(binding(APP_MANAGER_ID, "app-manager")));
        when(memberRepository.findByExternalIdentity(
                COMPANY_ID, ExternalIdentityProvider.WECOM, "company-admin"))
                .thenReturn(Optional.of(binding(COMPANY_ADMIN_ID, "company-admin")));
        when(roleMaintenance.bootstrapInitialRoles(any())).thenReturn(
                new InitialRoleBootstrapResult(UUID.randomUUID(), UUID.randomUUID()));

        InitialIdentityBootstrapResult result = service.execute(
                input(), "approved", "m115-service-success");

        assertThat(result.directoryRunId()).isEqualTo(DIRECTORY_RUN_ID);
        verify(roleMaintenance).requireInitialIdentityBootstrapOpen(COMPANY_ID);
        verify(roleMaintenance).bootstrapInitialRoles(any());
    }

    @Test
    void partialSyncAndMissingTargetsNeverGrantRoles() {
        when(syncUseCase.executeWithDisposition(any())).thenReturn(new DirectorySyncExecutionResult(
                snapshot(DirectorySyncRunStatus.PARTIALLY_SUCCEEDED, 2, "DIRECTORY_PARTIAL_FAILURE"),
                DirectorySyncClaimDisposition.NEW));

        assertCode(() -> service.execute(input(), "approved", "m115-service-partial"),
                "INITIAL_IDENTITY_BOOTSTRAP_SYNC_FAILED");
        verify(roleMaintenance, never()).bootstrapInitialRoles(any());

        when(syncUseCase.executeWithDisposition(any())).thenReturn(new DirectorySyncExecutionResult(
                snapshot(DirectorySyncRunStatus.SUCCEEDED, 2, null),
                DirectorySyncClaimDisposition.NEW));
        when(memberRepository.findByExternalIdentity(
                COMPANY_ID, ExternalIdentityProvider.WECOM, "app-manager"))
                .thenReturn(Optional.empty());

        assertCode(() -> service.execute(input(), "approved", "m115-service-missing"),
                "INITIAL_IDENTITY_BOOTSTRAP_APP_MANAGER_TARGET_NOT_FOUND");
        verify(roleMaintenance, never()).bootstrapInitialRoles(any());
    }

    @Test
    void activeDirectoryConflictFailsBeforeTargetLookup() {
        when(syncUseCase.executeWithDisposition(any())).thenReturn(new DirectorySyncExecutionResult(
                snapshot(DirectorySyncRunStatus.RUNNING, 0, null),
                DirectorySyncClaimDisposition.ACTIVE_CONFLICT));

        assertCode(() -> service.execute(input(), "approved", "m115-service-conflict"),
                "INITIAL_IDENTITY_BOOTSTRAP_SYNC_CONFLICT");
        verify(memberRepository, never()).findByExternalIdentity(any(), any(), any());
        verify(roleMaintenance, never()).bootstrapInitialRoles(any());
    }

    @Test
    void emptyDirectoryAndIneligibleTargetsNeverGrantRoles() {
        when(syncUseCase.executeWithDisposition(any())).thenReturn(new DirectorySyncExecutionResult(
                snapshot(DirectorySyncRunStatus.SUCCEEDED, 0, null),
                DirectorySyncClaimDisposition.NEW));

        assertCode(() -> service.execute(input(), "approved", "m115-service-empty"),
                "INITIAL_IDENTITY_BOOTSTRAP_EMPTY_DIRECTORY");
        verify(memberRepository, never()).findByExternalIdentity(any(), any(), any());

        when(syncUseCase.executeWithDisposition(any())).thenReturn(new DirectorySyncExecutionResult(
                snapshot(DirectorySyncRunStatus.SUCCEEDED, 2, null),
                DirectorySyncClaimDisposition.NEW));
        when(memberRepository.findByExternalIdentity(
                COMPANY_ID, ExternalIdentityProvider.WECOM, "app-manager"))
                .thenReturn(Optional.of(binding(APP_MANAGER_ID, "app-manager")));
        when(memberRepository.findByExternalIdentity(
                COMPANY_ID, ExternalIdentityProvider.WECOM, "company-admin"))
                .thenReturn(Optional.of(binding(
                        COMPANY_ADMIN_ID,
                        "company-admin",
                        EmploymentStatus.ACTIVE,
                        AccountStatus.ENABLED,
                        EmploymentStatus.LEFT
                )));

        assertCode(() -> service.execute(input(), "approved", "m115-service-disabled"),
                "INITIAL_IDENTITY_BOOTSTRAP_COMPANY_ADMIN_TARGET_INELIGIBLE");
        verify(roleMaintenance, never()).bootstrapInitialRoles(any());
    }

    @Test
    void twoExternalIdsResolvingToOneInternalUserAreRejected() {
        when(syncUseCase.executeWithDisposition(any())).thenReturn(new DirectorySyncExecutionResult(
                snapshot(DirectorySyncRunStatus.SUCCEEDED, 2, null),
                DirectorySyncClaimDisposition.NEW));
        when(memberRepository.findByExternalIdentity(
                COMPANY_ID, ExternalIdentityProvider.WECOM, "app-manager"))
                .thenReturn(Optional.of(binding(APP_MANAGER_ID, "app-manager")));
        when(memberRepository.findByExternalIdentity(
                COMPANY_ID, ExternalIdentityProvider.WECOM, "company-admin"))
                .thenReturn(Optional.of(binding(APP_MANAGER_ID, "company-admin")));

        assertCode(() -> service.execute(input(), "approved", "m115-service-same-user"),
                "INITIAL_IDENTITY_BOOTSTRAP_TARGETS_NOT_DISTINCT");
        verify(roleMaintenance, never()).bootstrapInitialRoles(any());
    }

    private static InitialIdentityBootstrapInput input() {
        return new InitialIdentityBootstrapInput("ww-test", "app-manager", "company-admin");
    }

    private static DirectorySyncRunSnapshot snapshot(
            DirectorySyncRunStatus status,
            int discovered,
            String errorCode
    ) {
        Instant started = Instant.parse("2026-08-18T00:00:00Z");
        boolean running = status == DirectorySyncRunStatus.RUNNING;
        boolean completedScan = status != DirectorySyncRunStatus.RUNNING;
        int failed = status == DirectorySyncRunStatus.PARTIALLY_SUCCEEDED ? 1 : 0;
        int unchanged = status == DirectorySyncRunStatus.SUCCEEDED ? discovered : discovered - failed;
        return new DirectorySyncRunSnapshot(
                DIRECTORY_RUN_ID,
                COMPANY_ID,
                DirectorySyncTriggerType.SCHEDULED,
                running ? DirectorySyncRunPhase.COLLECTING_IDS : DirectorySyncRunPhase.COMPLETED,
                status,
                completedScan ? DirectoryScanResult.CursorTerminationMode.EXPLICIT_EMPTY : null,
                completedScan ? 1 : 0,
                completedScan,
                new DirectorySyncCounts(
                        discovered, discovered, 0, 0, unchanged, 0, 0, failed, 0),
                errorCode,
                "m115-service-snapshot",
                0,
                started,
                running ? null : started.plusSeconds(1)
        );
    }

    private static DirectoryMemberBinding binding(UUID userId, String externalUserId) {
        return binding(
                userId,
                externalUserId,
                EmploymentStatus.ACTIVE,
                AccountStatus.ENABLED,
                EmploymentStatus.ACTIVE
        );
    }

    private static DirectoryMemberBinding binding(
            UUID userId,
            String externalUserId,
            EmploymentStatus employmentStatus,
            AccountStatus accountStatus,
            EmploymentStatus providerEmploymentStatus
    ) {
        Instant now = Instant.parse("2026-08-18T00:00:00Z");
        User user = new User(
                userId, COMPANY_ID, employmentStatus, accountStatus,
                "M1-15 Member", null, null, null,
                now, null, null, null, null, null,
                0, 0, now, now);
        ExternalIdentity identity = new ExternalIdentity(
                UUID.randomUUID(), COMPANY_ID, userId, ExternalIdentityProvider.WECOM,
                externalUserId, providerEmploymentStatus, new ProfileHash("a".repeat(64)),
                now, now, now);
        return new DirectoryMemberBinding(user, identity);
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        InitialIdentityBootstrapException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(code));
    }
}
