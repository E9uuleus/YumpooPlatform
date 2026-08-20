package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.domain.project.ProjectMembership;

public interface ProjectMembershipRepository {
    boolean insert(ProjectMembership membership);
}
