package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.domain.project.Project;
import com.yumpoo.platform.catalog.domain.project.ProjectLifecycle;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProjectDeletionService {
    private final ProjectRepository projects;
    private final ProjectDeletionRepository deletion;
    public ProjectDeletionService(ProjectRepository projects, ProjectDeletionRepository deletion) {
        this.projects = projects; this.deletion = deletion;
    }
    public Optional<ProjectDeletionSnapshot> find(UUID companyId,UUID projectId) {
        return deletion.find(companyId,projectId,false).map(ProjectDeletionSnapshot::from);
    }
    @Transactional
    public Optional<ProjectDeletionSnapshot> lockForProjection(UUID companyId,UUID projectId) {
        return deletion.find(companyId,projectId,true).map(ProjectDeletionSnapshot::from);
    }
    public boolean purgingOrPurged(UUID companyId, UUID projectId) { return deletion.purgingOrPurged(companyId, projectId); }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectDeletionSnapshot schedule(CurrentActor actor, UUID projectId, long version, String confirmationCode,
            Duration gracePeriod, Instant now) {
        Project project = editable(actor, projectId, version);
        if (project.deletionRequestedAt() != null) throw invalid("DELETION_ALREADY_SCHEDULED");
        if (!project.code().equals(confirmationCode)) throw ApplicationException.validation(
                new FieldViolation("confirmationCode", "MISMATCH", "请输入准确的项目编号"));
        return ProjectDeletionSnapshot.from(deletion.schedule(project,actor.userId(),now,now.plus(gracePeriod)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectDeletionSnapshot cancel(CurrentActor actor, UUID projectId, long version, Instant now) {
        Project project = editable(actor, projectId, version);
        if (project.deletionRequestedAt() == null) throw invalid("DELETION_NOT_SCHEDULED");
        return ProjectDeletionSnapshot.from(deletion.cancel(project,actor.userId(),now));
    }

    private Project editable(CurrentActor actor, UUID projectId, long version) {
        Project project = projects.lockById(actor.companyId(), projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (!project.ownerUserId().equals(actor.userId()) && !actor.hasRole(PlatformRoleCode.COMPANY_ADMIN))
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        if (project.rowVersion() != version) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        if (project.lifecycle() != ProjectLifecycle.ARCHIVED) throw invalid("PROJECT_MUST_BE_ARCHIVED");
        return project;
    }
    private static ApplicationException invalid(String reason) {
        return ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION, reason);
    }
}
