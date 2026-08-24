package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public interface ProjectModerationGuard {
    ProjectModerationSnapshot lockForModeration(CurrentActor actor, UUID projectId);
}
