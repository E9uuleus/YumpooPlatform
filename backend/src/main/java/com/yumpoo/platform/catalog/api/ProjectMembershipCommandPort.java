package com.yumpoo.platform.catalog.api;

public interface ProjectMembershipCommandPort {
    ProjectMemberMutationResult add(ProjectMemberMutation mutation);
    ProjectMemberMutationResult remove(ProjectMemberMutation mutation);
    ProjectOwnerReassignmentResult reassignOwner(ProjectOwnerReassignmentMutation mutation);
}
