package com.yumpoo.platform.identityaccess.application.authorization;

import java.util.List;

public record RoleAssignmentPage(
        List<RoleAssignmentView> items,
        int page,
        int pageSize,
        long total
) {
    public RoleAssignmentPage {
        items = List.copyOf(items);
    }
}
