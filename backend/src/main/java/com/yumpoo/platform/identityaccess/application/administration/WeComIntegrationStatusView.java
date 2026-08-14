package com.yumpoo.platform.identityaccess.application.administration;

import java.time.Instant;
import java.util.UUID;

public record WeComIntegrationStatusView(
        WeComConfigurationStatus.OAuthStatus oauth,
        WeComConfigurationStatus.DirectoryStatus directory,
        boolean corpIdConsistent,
        UUID activeRunId,
        Instant lastSuccessfulRunAt,
        Instant lastProblemAt,
        String lastProblemCode
) {
}
