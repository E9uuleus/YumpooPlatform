package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.domain.project.Project;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProjectDeletionRepository {
    Optional<Project> find(UUID companyId, UUID projectId, boolean shareLock);
    boolean purgingOrPurged(UUID companyId, UUID projectId);
    Project schedule(Project project, UUID actorUserId, Instant now, Instant purgeAfter);
    Project cancel(Project project, UUID actorUserId, Instant now);
}
