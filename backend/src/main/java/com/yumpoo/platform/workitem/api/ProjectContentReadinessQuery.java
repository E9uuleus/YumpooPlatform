package com.yumpoo.platform.workitem.api;

import java.util.UUID;

public interface ProjectContentReadinessQuery {
    boolean hasActiveContent(UUID companyId, UUID projectId, String templateKey, int templateVersion);
}
