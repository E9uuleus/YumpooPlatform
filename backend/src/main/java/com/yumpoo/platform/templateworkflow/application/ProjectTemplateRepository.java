package com.yumpoo.platform.templateworkflow.application;

import com.yumpoo.platform.templateworkflow.domain.ProjectTemplateDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectTemplateRepository {

    Optional<ProjectTemplateDefinition> find(String templateKey, int version, boolean lock);

    List<ProjectTemplateDefinition> findPublished();

    boolean publish(UUID id, long expectedRowVersion, UUID actorUserId, Instant changedAt);

    boolean retire(UUID id, long expectedRowVersion, UUID actorUserId, String reason, Instant changedAt);
}
