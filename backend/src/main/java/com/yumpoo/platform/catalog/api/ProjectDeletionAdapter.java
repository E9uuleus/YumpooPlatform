package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.project.ProjectDeletionService;
import com.yumpoo.platform.catalog.application.project.ProjectDeletionSnapshot;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class ProjectDeletionAdapter implements ProjectDeletionQuery, ProjectDeletionCommandPort {
    private final ProjectDeletionService service;
    public ProjectDeletionAdapter(ProjectDeletionService service) { this.service = service; }
    public Optional<State> find(UUID companyId, UUID projectId) { return service.find(companyId, projectId).map(ProjectDeletionAdapter::state); }
    @Transactional
    public Optional<State> lockForProjection(UUID companyId, UUID projectId) {
        return service.lockForProjection(companyId, projectId).map(ProjectDeletionAdapter::state);
    }
    @Transactional
    public boolean purgingOrPurged(UUID companyId, UUID projectId) {
        return service.lockForProjection(companyId, projectId).filter(p -> p.purgeStartedAt() != null).isPresent()
                || service.purgingOrPurged(companyId, projectId);
    }
    public State schedule(CurrentActor actor, UUID projectId, long version, String confirmationCode,
            Duration gracePeriod, Instant now) {
        return state(service.schedule(actor, projectId, version, confirmationCode, gracePeriod, now));
    }
    public State cancel(CurrentActor actor, UUID projectId, long version, Instant now) {
        return state(service.cancel(actor, projectId, version, now));
    }
    private static State state(ProjectDeletionSnapshot p) {
        return new State(p.projectId(),p.companyId(),p.code(),p.lifecycle(),p.ownerUserId(),p.rowVersion(),
                p.requestedAt(),p.requestedBy(),p.purgeAfter(),p.purgeStartedAt());
    }
}
