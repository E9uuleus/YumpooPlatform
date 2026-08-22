package com.yumpoo.platform.administration.application;

import java.util.UUID;

public interface ProjectArchiveBlockerProvider {
    ProjectArchiveBlockerSource source();
    ProjectArchiveBlockerReport report(UUID companyId, UUID projectId);
}
