package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public interface ProjectDeletionCommandPort {
    ProjectDeletionQuery.State schedule(CurrentActor actor, UUID projectId, long version,
            String confirmationCode, Duration gracePeriod, Instant now);
    ProjectDeletionQuery.State cancel(CurrentActor actor, UUID projectId, long version, Instant now);
}
