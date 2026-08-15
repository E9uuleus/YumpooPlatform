package com.yumpoo.platform.identityaccess.application.administration;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class IdentityAdministrationQueryService {

    private final IdentityAdminAccessPolicy accessPolicy;
    private final IdentityAdministrationRepository repository;
    private final WeComConfigurationStatusProvider configurationStatusProvider;

    public IdentityAdministrationQueryService(
            IdentityAdminAccessPolicy accessPolicy,
            IdentityAdministrationRepository repository,
            WeComConfigurationStatusProvider configurationStatusProvider
    ) {
        this.accessPolicy = accessPolicy;
        this.repository = repository;
        this.configurationStatusProvider = configurationStatusProvider;
    }

    public IdentityMemberPage members(
            UUID companyId,
            UUID actorUserId,
            IdentityMemberQuery query
    ) {
        accessPolicy.requireReader(companyId, actorUserId);
        return repository.findMembers(companyId, query);
    }

    public IdentityMemberView member(UUID companyId, UUID actorUserId, UUID userId) {
        accessPolicy.requireReader(companyId, actorUserId);
        return repository.findMember(companyId, userId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    public DirectorySyncRunPage runs(
            UUID companyId,
            UUID actorUserId,
            DirectoryRunQuery query
    ) {
        accessPolicy.requireReader(companyId, actorUserId);
        return repository.findRuns(companyId, query);
    }

    public DirectorySyncRunView run(UUID companyId, UUID actorUserId, UUID runId) {
        accessPolicy.requireReader(companyId, actorUserId);
        return repository.findRun(companyId, runId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    public DirectorySyncFailurePage failures(
            UUID companyId,
            UUID actorUserId,
            UUID runId,
            com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest pageRequest
    ) {
        accessPolicy.requireReader(companyId, actorUserId);
        if (repository.findRun(companyId, runId).isEmpty()) {
            throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        }
        return repository.findFailures(companyId, runId, pageRequest);
    }

    public WeComIntegrationStatusView integrationStatus(UUID companyId, UUID actorUserId) {
        accessPolicy.requireReader(companyId, actorUserId);
        WeComConfigurationStatus configuration = configurationStatusProvider.current();
        DirectoryRuntimeSnapshot runtime = repository.runtimeStatus(companyId);
        return new WeComIntegrationStatusView(
                configuration.oauth(),
                configuration.directory(),
                configuration.corpIdConsistent(),
                runtime.activeRunId(),
                runtime.lastSuccessfulRunAt(),
                runtime.lastProblemAt(),
                runtime.lastProblemCode()
        );
    }
}
