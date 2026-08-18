package com.yumpoo.platform.identityaccess.application.bootstrap;

import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.identityaccess.application.authorization.InitialRoleBootstrapCommand;
import com.yumpoo.platform.identityaccess.application.authorization.InitialRoleBootstrapResult;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMaintenanceUseCase;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberBinding;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningRepository;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncAdministrationUseCase;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncClaimDisposition;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncCommand;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncExecutionResult;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncRunSnapshot;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncRunStatus;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncTriggerType;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.identity.EmploymentStatus;
import com.yumpoo.platform.identityaccess.domain.identity.ExternalIdentityProvider;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class InitialIdentityBootstrapService {

    private final CompanyConfigurationQuery companyQuery;
    private final ObjectProvider<DirectorySyncAdministrationUseCase> syncUseCaseProvider;
    private final DirectoryMemberProvisioningRepository memberRepository;
    private final PlatformRoleMaintenanceUseCase roleMaintenance;

    public InitialIdentityBootstrapService(
            CompanyConfigurationQuery companyQuery,
            ObjectProvider<DirectorySyncAdministrationUseCase> syncUseCaseProvider,
            DirectoryMemberProvisioningRepository memberRepository,
            PlatformRoleMaintenanceUseCase roleMaintenance
    ) {
        this.companyQuery = companyQuery;
        this.syncUseCaseProvider = syncUseCaseProvider;
        this.memberRepository = memberRepository;
        this.roleMaintenance = roleMaintenance;
    }

    public InitialIdentityBootstrapResult execute(
            InitialIdentityBootstrapInput input,
            String reasonReference,
            String requestId
    ) {
        UUID companyId = companyQuery.current().companyId();
        try {
            roleMaintenance.requireInitialIdentityBootstrapOpen(companyId);
        } catch (ApplicationException exception) {
            throw new InitialIdentityBootstrapException(
                    "PREFLIGHT",
                    "INITIAL_IDENTITY_BOOTSTRAP_PERMANENTLY_CLOSED",
                    "Initial identity bootstrap is not available"
            );
        }

        DirectorySyncAdministrationUseCase syncUseCase = syncUseCaseProvider.getIfAvailable();
        if (syncUseCase == null) {
            throw failure(
                    "PREFLIGHT",
                    "INITIAL_IDENTITY_BOOTSTRAP_DIRECTORY_DISABLED",
                    "Directory synchronization is not enabled"
            );
        }

        DirectorySyncExecutionResult execution = syncUseCase.executeWithDisposition(
                new DirectorySyncCommand(
                        "m1-15-initial-identity-" + UUID.randomUUID(),
                        DirectorySyncTriggerType.SCHEDULED,
                        EventActor.system(InitialIdentityBootstrapAuditService.SYSTEM_CODE),
                        requestId
                )
        );
        DirectorySyncRunSnapshot run = execution.snapshot();
        if (execution.disposition() != DirectorySyncClaimDisposition.NEW) {
            throw new InitialIdentityBootstrapException(
                    "DIRECTORY_SYNC",
                    "INITIAL_IDENTITY_BOOTSTRAP_SYNC_CONFLICT",
                    run.runId(),
                    "Another directory synchronization owns the execution"
            );
        }
        if (run.status() != DirectorySyncRunStatus.SUCCEEDED) {
            throw new InitialIdentityBootstrapException(
                    "DIRECTORY_SYNC",
                    "INITIAL_IDENTITY_BOOTSTRAP_SYNC_FAILED",
                    run.runId(),
                    "Directory synchronization did not complete successfully"
            );
        }
        if (run.counts().discovered() == 0) {
            throw new InitialIdentityBootstrapException(
                    "DIRECTORY_SYNC",
                    "INITIAL_IDENTITY_BOOTSTRAP_EMPTY_DIRECTORY",
                    run.runId(),
                    "Directory synchronization returned an empty snapshot"
            );
        }

        DirectoryMemberBinding appManager = requireEligible(
                companyId, input.appManagerWeComUserId(), "APP_MANAGER_TARGET", run.runId());
        DirectoryMemberBinding companyAdmin = requireEligible(
                companyId, input.companyAdminWeComUserId(), "COMPANY_ADMIN_TARGET", run.runId());
        if (appManager.user().id().equals(companyAdmin.user().id())) {
            throw new InitialIdentityBootstrapException(
                    "TARGET_VALIDATION",
                    "INITIAL_IDENTITY_BOOTSTRAP_TARGETS_NOT_DISTINCT",
                    run.runId(),
                    "Initial bootstrap role holders must be distinct"
            );
        }

        InitialRoleBootstrapResult roles;
        try {
            roles = roleMaintenance.bootstrapInitialRoles(
                    new InitialRoleBootstrapCommand(
                            companyId,
                            appManager.user().id(),
                            companyAdmin.user().id(),
                            run.runId(),
                            reasonReference
                    )
            );
        } catch (ApplicationException exception) {
            throw new InitialIdentityBootstrapException(
                    "ROLE_BOOTSTRAP",
                    "INITIAL_IDENTITY_BOOTSTRAP_ROLE_REJECTED",
                    run.runId(),
                    "Initial role bootstrap was rejected"
            );
        }
        return new InitialIdentityBootstrapResult(
                run.runId(), roles.appManagerAssignmentId(), roles.companyAdminAssignmentId());
    }

    private DirectoryMemberBinding requireEligible(
            UUID companyId,
            String externalUserId,
            String targetCode,
            UUID directoryRunId
    ) {
        DirectoryMemberBinding binding = memberRepository.findByExternalIdentity(
                        companyId, ExternalIdentityProvider.WECOM, externalUserId)
                .orElseThrow(() -> new InitialIdentityBootstrapException(
                        "TARGET_VALIDATION",
                        "INITIAL_IDENTITY_BOOTSTRAP_" + targetCode + "_NOT_FOUND",
                        directoryRunId,
                        "Initial bootstrap target is not eligible"
                ));
        boolean eligible = binding.user().companyId().equals(companyId)
                && binding.externalIdentity().companyId().equals(companyId)
                && binding.externalIdentity().provider() == ExternalIdentityProvider.WECOM
                && binding.user().employmentStatus() == EmploymentStatus.ACTIVE
                && binding.user().accountStatus() == AccountStatus.ENABLED
                && binding.externalIdentity().providerEmploymentStatus() == EmploymentStatus.ACTIVE;
        if (!eligible) {
            throw new InitialIdentityBootstrapException(
                    "TARGET_VALIDATION",
                    "INITIAL_IDENTITY_BOOTSTRAP_" + targetCode + "_INELIGIBLE",
                    directoryRunId,
                    "Initial bootstrap target is not eligible"
            );
        }
        return binding;
    }

    private static InitialIdentityBootstrapException failure(
            String stage,
            String code,
            String message
    ) {
        return new InitialIdentityBootstrapException(stage, code, message);
    }
}
