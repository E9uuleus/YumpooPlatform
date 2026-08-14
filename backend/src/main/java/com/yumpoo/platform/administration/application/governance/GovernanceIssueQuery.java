package com.yumpoo.platform.administration.application.governance;

import java.util.Objects;
import java.util.UUID;

public record GovernanceIssueQuery(
        UUID companyId,
        GovernanceIssueType issueType,
        GovernanceIssueStatus status,
        int page,
        int pageSize
) {
    public GovernanceIssueQuery {
        Objects.requireNonNull(companyId, "companyId must not be null");
        if (page < 0 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("invalid governance issue pagination");
        }
    }
}
