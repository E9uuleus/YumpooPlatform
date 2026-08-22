package com.yumpoo.platform.workitem.api;

import java.util.UUID;

public interface WorkItemProjectArchiveBlockerPort {
    long countOpen(UUID companyId, UUID projectId);
}
