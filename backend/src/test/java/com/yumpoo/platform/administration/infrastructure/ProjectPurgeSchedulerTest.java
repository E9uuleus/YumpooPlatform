package com.yumpoo.platform.administration.infrastructure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yumpoo.platform.administration.application.ProjectPurgeService;
import com.yumpoo.platform.catalog.api.ProjectPurgeQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.UncheckedIOException;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectPurgeSchedulerTest {
    private final ProjectPurgeService service = mock(ProjectPurgeService.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ProjectPurgeScheduler scheduler = new ProjectPurgeScheduler(service, metrics);
    private final ProjectPurgeQueue.Lease lease = new ProjectPurgeQueue.Lease(UUID.randomUUID(), UUID.randomUUID(),
            "FILESTORAGE", UUID.randomUUID(), Instant.now().plusSeconds(180));

    @Test
    void stageFailureIsLoggedWithStageProjectAndRootCauseWithoutEscaping() {
        when(service.claim(any())).thenReturn(Optional.of(lease));
        when(service.process(lease)).thenThrow(new IllegalStateException("project blob physical purge failed",
                new UncheckedIOException(new IOException("disk"))));

        var failure = capture();

        assertThat(failure.get("phase")).isEqualTo("FILESTORAGE");
        assertThat(failure.get("projectId")).isEqualTo(lease.projectId());
        assertThat(failure.get("exceptionType")).isEqualTo(IOException.class.getName());
        assertThat(metrics.counter("yumpoo.projects.purge.failures", "phase", "FILESTORAGE").count()).isOne();
    }

    @Test
    void completionFailureIsAttributedToTheCompletePhase() {
        var catalog = new ProjectPurgeQueue.Lease(lease.companyId(), lease.projectId(), "CATALOG", lease.token(), lease.until());
        when(service.claim(any())).thenReturn(Optional.of(catalog));
        when(service.process(catalog)).thenReturn(true);
        when(service.complete(catalog)).thenThrow(new DataIntegrityViolationException("fk"));

        var failure = capture();

        assertThat(failure.get("phase")).isEqualTo("COMPLETE");
        assertThat(failure.get("projectId")).isEqualTo(catalog.projectId());
        assertThat(failure.get("exceptionType")).isEqualTo(DataIntegrityViolationException.class.getName());
    }

    @Test
    void failureBeforeClaimHasNoProject() {
        when(service.remindOne()).thenThrow(new IllegalStateException("reminder"));

        var failure = capture();

        assertThat(failure.get("phase")).isEqualTo("REMIND");
        assertThat(failure.get("projectId")).isNull();
    }

    private Map<String, Object> capture() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProjectPurgeScheduler.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            assertThatCode(scheduler::poll).doesNotThrowAnyException();
            assertThat(captured.list).hasSize(1);
            var event = captured.list.getFirst();
            assertThat(event.getThrowableProxy()).isNotNull();
            return event.getKeyValuePairs().stream()
                    .filter(pair -> pair.value != null)
                    .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }
}
