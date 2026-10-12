package com.yumpoo.platform.audit.api;

import java.util.UUID;
import java.util.List;

public interface ActivityQueryPort {
    CompanyActivityPage findCompany(UUID companyId, List<UUID> projectIds, List<UUID> actorUserIds,
            ActivityQuery query);
    ActivityPage findProject(UUID companyId, UUID projectId, ActivityQuery query);
    ActivityPage findWorkItem(UUID companyId, UUID projectId, UUID workItemId,
            ActivityQuery query);
}
