package com.yumpoo.platform.administration.application.governance;

import java.time.Instant;
import java.util.UUID;

public record GovernanceIssueView(
        UUID issueId,
        UUID companyId,
        GovernanceIssueType issueType,
        GovernanceIssueStatus status,
        String safeSummaryCode,
        UUID detectedEventId,
        Instant detectedAt,
        UUID resolvedEventId,
        Instant resolvedAt,
        long rowVersion
) {
}
