package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentPage;

import java.util.List;

public record RoleAssignmentPageResponse(
        List<RoleAssignmentResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    static RoleAssignmentPageResponse from(RoleAssignmentPage source) {
        int pages = source.total() == 0 ? 0
                : Math.toIntExact((source.total() + source.pageSize() - 1) / source.pageSize());
        return new RoleAssignmentPageResponse(
                source.items().stream().map(RoleAssignmentResponse::from).toList(),
                source.page(), source.pageSize(), source.total(), pages);
    }
}
