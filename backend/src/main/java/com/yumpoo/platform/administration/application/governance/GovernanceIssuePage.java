package com.yumpoo.platform.administration.application.governance;

import java.util.List;

public record GovernanceIssuePage(
        List<GovernanceIssueView> items,
        int page,
        int pageSize,
        long total
) {
    public GovernanceIssuePage {
        items = List.copyOf(items);
    }
}
