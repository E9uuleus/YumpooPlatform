package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public interface ProjectMembershipQuery {
    ProjectMemberPage findMembers(CurrentActor actor, UUID projectId,
                                  ProjectMembershipStatus status, OffsetPageRequest page);
    ProjectMemberPage findMembers(CurrentActor actor, UUID projectId,
            ProjectMembershipStatus status, String query, OffsetPageRequest page);
    ProjectMemberCandidatePage findCandidates(CurrentActor actor, UUID projectId,
                                               String name, OffsetPageRequest page);
    ProjectAccessSnapshot requireVisible(CurrentActor actor, UUID projectId);
}
