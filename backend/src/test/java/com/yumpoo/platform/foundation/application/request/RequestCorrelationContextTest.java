package com.yumpoo.platform.foundation.application.request;

import com.yumpoo.platform.foundation.application.logging.StructuredLoggingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestCorrelationContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void nestedScopesRestoreCorrelationAndMdcWithoutLeakingThreadState() {
        RequestCorrelation root = RequestCorrelation.root("m011-root");
        RequestCorrelation child = root.causedBy(UUID.randomUUID());

        try (
                RequestCorrelationContext.Scope ignoredRoot = RequestCorrelationContext.open(root);
                StructuredLoggingContext.Scope ignoredRootMdc = StructuredLoggingContext.open(Map.of(
                        StructuredLoggingContext.REQUEST_ID, root.requestId(),
                        StructuredLoggingContext.CORRELATION_ID, root.correlationId()
                ))
        ) {
            assertThat(RequestCorrelationContext.required()).isEqualTo(root);
            assertThat(MDC.get(StructuredLoggingContext.REQUEST_ID)).isEqualTo("m011-root");

            try (
                    RequestCorrelationContext.Scope ignoredChild = RequestCorrelationContext.open(child);
                    StructuredLoggingContext.Scope ignoredChildMdc = StructuredLoggingContext.open(Map.of(
                            StructuredLoggingContext.EVENT_ID, child.causationId()
                    ))
            ) {
                assertThat(RequestCorrelationContext.required()).isEqualTo(child);
                assertThat(MDC.get(StructuredLoggingContext.EVENT_ID))
                        .isEqualTo(child.causationId().toString());
            }

            assertThat(RequestCorrelationContext.required()).isEqualTo(root);
            assertThat(MDC.get(StructuredLoggingContext.EVENT_ID)).isNull();
        }

        assertThat(RequestCorrelationContext.current()).isEmpty();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void missingContextFailsClosed() {
        assertThatThrownBy(RequestCorrelationContext::required)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("request correlation context is required");
    }
}
