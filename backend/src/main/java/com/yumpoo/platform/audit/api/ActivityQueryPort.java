package com.yumpoo.platform.audit.api;

import java.util.UUID;

public interface ActivityQueryPort {
    ActivityPage findProject(UUID companyId, UUID projectId, ActivityQuery query);
    ActivityPage findWorkItem(UUID companyId, UUID projectId, UUID workItemId,
            ActivityQuery query);
}
