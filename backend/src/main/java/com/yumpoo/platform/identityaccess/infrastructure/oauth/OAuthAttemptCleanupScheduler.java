package com.yumpoo.platform.identityaccess.infrastructure.oauth;

import com.yumpoo.platform.identityaccess.application.authentication.WebAuthenticationService;
import com.yumpoo.platform.identityaccess.infrastructure.wecom.WebOAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OAuthAttemptCleanupScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            OAuthAttemptCleanupScheduler.class
    );

    private final WebAuthenticationService authenticationService;
    private final WebOAuthProperties properties;

    public OAuthAttemptCleanupScheduler(
            WebAuthenticationService authenticationService,
            WebOAuthProperties properties
    ) {
        this.authenticationService = authenticationService;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${yumpoo.wecom.oauth.cleanup-delay:1h}",
            initialDelayString = "${yumpoo.wecom.oauth.cleanup-delay:1h}"
    )
    public void purgeExpiredAttempts() {
        int deleted = authenticationService.purgeExpiredAttempts(
                properties.getPurgeBatchSize(),
                properties.getPurgeMaxBatches()
        );
        if (deleted > 0) {
            LOGGER.info("expired OAuth attempts purged; deletedCount={}", deleted);
        }
    }
}
