package com.yumpoo.platform.foundation.infrastructure.outbox;

import com.yumpoo.platform.foundation.application.outbox.OutboxDispatcher;
import com.yumpoo.platform.foundation.application.logging.StructuredLoggingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "yumpoo.outbox", name = "enabled", matchIfMissing = true)
public class OutboxPollScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPollScheduler.class);

    private final OutboxDispatcher dispatcher;

    public OutboxPollScheduler(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(
            fixedDelayString = "${yumpoo.outbox.poll-delay:1s}",
            initialDelayString = "${yumpoo.outbox.initial-delay:1s}"
    )
    public void poll() {
        try {
            dispatcher.dispatchOnce();
        } catch (RuntimeException exception) {
            try (StructuredLoggingContext.Scope ignored = StructuredLoggingContext.open(Map.of(
                    StructuredLoggingContext.CONSUMER_NAME, "outbox.dispatcher",
                    StructuredLoggingContext.OUTCOME, "CYCLE_FAILED",
                    StructuredLoggingContext.ERROR_CODE, "DISPATCH_CYCLE_FAILURE"
            ))) {
                LOGGER.error(
                        "outbox dispatch cycle failed; exceptionType={}",
                        safeExceptionType(exception)
                );
            }
        }
    }

    private static String safeExceptionType(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String name = cause.getClass().getName();
        return name.length() <= 160 ? name : name.substring(0, 160);
    }
}
