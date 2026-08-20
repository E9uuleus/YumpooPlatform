package com.yumpoo.platform.catalog.api;

public record ProjectOwnerReassignmentResult(
        ProjectSnapshot before, ProjectSnapshot after, ProjectMemberSnapshot ownerMembership,
        boolean membershipAdded
) {}
