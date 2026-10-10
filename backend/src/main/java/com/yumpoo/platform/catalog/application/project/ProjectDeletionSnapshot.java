package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.domain.project.Project;
import java.time.Instant;
import java.util.UUID;

public record ProjectDeletionSnapshot(UUID projectId,UUID companyId,String code,String lifecycle,UUID ownerUserId,
        long rowVersion,Instant requestedAt,UUID requestedBy,Instant purgeAfter,Instant purgeStartedAt) {
    static ProjectDeletionSnapshot from(Project p) {
        return new ProjectDeletionSnapshot(p.id(),p.companyId(),p.code(),p.lifecycle().name(),p.ownerUserId(),p.rowVersion(),
                p.deletionRequestedAt(),p.deletionRequestedBy(),p.purgeAfter(),p.purgeStartedAt());
    }
}
