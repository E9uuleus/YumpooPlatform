package com.yumpoo.platform.identityaccess.application.administration;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;

import java.util.Optional;
import java.util.UUID;

public interface IdentityAdministrationRepository {

    IdentityMemberPage findMembers(UUID companyId, IdentityMemberQuery query);

    Optional<IdentityMemberView> findMember(UUID companyId, UUID userId);

    DirectorySyncRunPage findRuns(UUID companyId, DirectoryRunQuery query);

    Optional<DirectorySyncRunView> findRun(UUID companyId, UUID runId);

    DirectorySyncFailurePage findFailures(
            UUID companyId,
            UUID runId,
            OffsetPageRequest pageRequest
    );

    DirectoryRuntimeSnapshot runtimeStatus(UUID companyId);
}
