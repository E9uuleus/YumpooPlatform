package com.yumpoo.platform.foundation.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yumpoo.platform.foundation.api.web.HttpAccessLog;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class HttpAccessLogTest {

    @Test
    void globalBusinessTimerExcludesMonitoringAndIncludesFailedBusinessRequests() throws Exception {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("meterRegistry", registry);
        var filter = new com.yumpoo.platform.foundation.api.web.RequestIdFilter(
            new com.yumpoo.platform.foundation.api.error.ApiErrorWriter(
                tools.jackson.databind.json.JsonMapper.builder().build()
            ),
            beans.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class)
        );
        for (String path : List.of(
            "/api/v1/projects",
            "/api/v1/projects/failure",
            "/api/v1/admin/operations/logs",
            "/actuator/health"
        )) {
            filter.doFilter(
                new MockHttpServletRequest("GET", path),
                new MockHttpServletResponse(),
                (request, response) -> {
                    if (path.endsWith("failure")) throw new IllegalStateException("business failure");
                }
            );
        }
        var timer = registry.get("yumpoo.http.business").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.getId().getTags()).isEmpty();
        assertThat(timer.takeSnapshot().percentileValues()).hasSize(1);
    }

    @Test
    void failuresDescribeTheirOutcomeAndOnlyTheTwoPollingReadsUseDebug() {
        Logger logger = (Logger) LoggerFactory.getLogger(HttpAccessLog.class);
        Level previous = logger.getLevel();
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        logger.setLevel(Level.DEBUG);
        try {
            for (String path : List.of("logs", "sessions", "logs/tail", "alerts/summary")) {
                var request = new MockHttpServletRequest("GET", "/api/v1/admin/operations/" + path);
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, request.getRequestURI());
                HttpAccessLog.completed(request, new MockHttpServletResponse(), System.nanoTime());
            }
            assertThat(captured.list)
                .extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.INFO, Level.INFO, Level.DEBUG, Level.DEBUG);
            var request = new MockHttpServletRequest("GET", "/api/v1/projects/123");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/projects/{projectId}");
            var response = new MockHttpServletResponse();
            response.setStatus(500);
            HttpAccessLog.completed(request, response, System.nanoTime());
            var failure = captured.list.getLast();
            assertThat(failure.getFormattedMessage()).isEqualTo("http request failed");
            assertThat(failure.getKeyValuePairs()).anyMatch(
                field -> field.key.equals("route") && field.value.equals("/api/v1/projects/{projectId}")
            );
        } finally {
            logger.detachAppender(captured);
            captured.stop();
            logger.setLevel(previous);
        }
    }
}
