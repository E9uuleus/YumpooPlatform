package com.yumpoo.platform.identityaccess.infrastructure.session;

import com.yumpoo.platform.identityaccess.application.session.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SessionCleanupScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionCleanupScheduler.class);
    private final SessionService sessionService;

    public SessionCleanupScheduler(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Scheduled(
            fixedDelayString = "${yumpoo.session.cleanup-delay:1h}",
            initialDelayString = "${yumpoo.session.cleanup-delay:1h}"
    )
    public void purgeDueSessions() {
        int deleted = sessionService.purgeDueSessions();
        if (deleted > 0) {
            LOGGER.info("due sessions purged; deletedCount={}", deleted);
        }
    }
}
