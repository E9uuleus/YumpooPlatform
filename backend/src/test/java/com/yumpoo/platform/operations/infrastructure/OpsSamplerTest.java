package com.yumpoo.platform.operations.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yumpoo.platform.foundation.application.diagnostics.DeploymentDiagnosticsPort;
import com.yumpoo.platform.foundation.application.logging.LogQueryPort;
import com.yumpoo.platform.foundation.application.outbox.OutboxBacklogPort;
import com.yumpoo.platform.identityaccess.api.ActiveSessionQuery;
import com.yumpoo.platform.operations.application.OperationsAlertService;
import com.yumpoo.platform.operations.application.OperationsModels;
import com.yumpoo.platform.operations.application.OperationsRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

class OpsSamplerTest {

    final MutableClock clock = new MutableClock();
    final DataSource source = mock(DataSource.class);
    final OutboxBacklogPort outbox = mock(OutboxBacklogPort.class);
    final ActiveSessionQuery sessions = mock(ActiveSessionQuery.class);
    final DeploymentDiagnosticsPort disks = mock(DeploymentDiagnosticsPort.class);
    final LogQueryPort logs = mock(LogQueryPort.class);
    final OperationsRepository repository = mock(OperationsRepository.class);
    final OperationsAlertService alerts = mock(OperationsAlertService.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @SuppressWarnings("unchecked")
    OpsSampler sampler(boolean fakeJdbc) {
        when(disks.read()).thenReturn(new DeploymentDiagnosticsPort.Snapshot("UP", List.of()));
        when(outbox.read()).thenReturn(new OutboxBacklogPort.Backlog(1, 0, 0, 0, clock.instant()));
        when(sessions.countActive(any())).thenReturn(new ActiveSessionQuery.Counts(0, 0));
        when(logs.countsSince(any())).thenReturn(Map.of());
        when(alerts.cached()).thenReturn(List.of());
        var result = new OpsSampler(
            new SimpleMeterRegistry(),
            source,
            outbox,
            sessions,
            disks,
            logs,
            repository,
            alerts,
            new MockEnvironment(),
            mock(ObjectProvider.class),
            mock(ObjectProvider.class),
            clock
        );
        if (fakeJdbc) {
            when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
            when(jdbc.execute(any(org.springframework.jdbc.core.ConnectionCallback.class))).thenReturn(
                "PostgreSQL 17.10"
            );
            ReflectionTestUtils.setField(result, "jdbc", jdbc);
            ExecutorService worker = mock(ExecutorService.class);
            when(worker.submit(any(Runnable.class))).thenReturn(new CompletableFuture<>());
            ReflectionTestUtils.setField(result, "worker", worker);
        }
        return result;
    }

    @Test
    void blockedDatabaseWorkerDoesNotBlockLocalSamplingAndShutdownInterruptsIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1),
            release = new CountDownLatch(1);
        when(source.getConnection()).thenAnswer(call -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            throw new SQLException("test outage");
        });
        var sampler = sampler(false);
        try {
            sampler.start();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            clock.advance(15);
            sampler.sample();
            assertThat(sampler.recent()).hasSize(2);
            assertThat(sampler.snapshot().metrics().get("heap.used")).isPositive();
            assertThat(sampler.snapshot().components()).anyMatch(
                c -> c.code().equals("database") && c.status().equals("UNKNOWN")
            );
            verify(source, times(1)).getConnection();
        } finally {
            sampler.stop();
            release.countDown();
        }
        assertThat(sampler.isRunning()).isFalse();
    }

    @Test
    void diskCadenceStaleValuesAndBoundedBacklogRemainExplicit() {
        var sampler = sampler(true);
        for (int i = 0; i <= 4; i++) {
            sampler.collectIo(clock.instant(), new HashMap<>(), List.of());
            clock.advance(15);
        }
        verify(disks, times(2)).read();
        sampler.sample();
        assertThat(sampler.snapshot().metrics().get("outbox.backlog")).isEqualTo(1d);
        clock.advance(46);
        sampler.sample();
        assertThat(sampler.snapshot().metrics()).containsEntry("outbox.backlog", null);
        assertThat(sampler.snapshot().components()).anyMatch(
            c -> c.code().equals("database") && c.status().equals("UNKNOWN")
        );
        for (int i = 0; i < 250; i++) {
            clock.advance(60);
            sampler.sample();
        }
        assertThat(sampler.recent()).hasSize(240);
        assertThat(sampler.snapshot().lostSamples()).isGreaterThan(0);
    }

    @Test
    void failedCollectorDoesNotEraseHealthyJvmSamples() {
        var sampler = sampler(true);
        when(logs.countsSince(any())).thenThrow(new IllegalStateException("collector failed"));
        sampler.sample();
        assertThat(sampler.recent()).hasSize(1);
        assertThat(sampler.snapshot().metrics().get("heap.used")).isPositive();
        assertThat(sampler.snapshot().metrics()).containsEntry("logs.error", null);
    }

    @Test
    void twoProbeFailuresAreRecordedOnceAfterRecoveryAndMinutesRetryIdempotently() {
        var sampler = sampler(true);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenThrow(
            new org.springframework.dao.DataAccessResourceFailureException("offline")
        );
        sampler.sample();
        sampler.collectIo(clock.instant(), new HashMap<>(), List.of());
        clock.advance(15);
        sampler.sample();
        sampler.collectIo(clock.instant(), new HashMap<>(), List.of());
        Instant outage = clock.instant();
        verifyNoInteractions(repository);
        verify(alerts, never()).evaluate(anyMap(), anyList(), any());
        doReturn(1).when(jdbc).queryForObject("SELECT 1", Integer.class);
        clock.advance(60);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("write failed"))
            .doNothing()
            .when(repository)
            .saveMinute(any(), any(), any(), anyMap(), anyInt());
        sampler.collectIo(clock.instant(), new HashMap<>(), List.of());
        clock.advance(15);
        sampler.collectIo(clock.instant(), new HashMap<>(), List.of());
        verify(alerts, times(1)).recordDatabaseOutage(outage, 2);
        verify(repository, times(2)).saveMinute(
            any(),
            any(),
            eq(outage.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)),
            anyMap(),
            eq(2)
        );
        var evaluatedAt = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(alerts, times(4)).evaluate(anyMap(), anyList(), evaluatedAt.capture());
        assertThat(evaluatedAt.getAllValues()).containsExactly(
            outage.minusSeconds(15),
            outage,
            outage.plusSeconds(60),
            outage.plusSeconds(75)
        );
    }

    @Test
    void saturatedPoolKeepsLocalAlertEvidenceAndReplaysAllRulesAfterRecovery() {
        var sampler = sampler(true);
        var hikari = mock(com.zaxxer.hikari.HikariDataSource.class);
        var pool = mock(com.zaxxer.hikari.HikariPoolMXBean.class);
        when(hikari.getHikariPoolMXBean()).thenReturn(pool);
        when(hikari.getMaximumPoolSize()).thenReturn(8);
        when(pool.getActiveConnections()).thenReturn(8);
        when(pool.getIdleConnections()).thenReturn(0);
        when(pool.getThreadsAwaitingConnection()).thenReturn(7);
        ReflectionTestUtils.setField(sampler, "source", hikari);
        var registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(sampler, "registry", registry);
        var requests = io.micrometer.core.instrument.Timer.builder("http.server.requests")
            .tags("uri", "/api/v1/probe", "status", "500")
            .publishPercentiles(.95)
            .register(registry);
        var business = io.micrometer.core.instrument.Timer.builder("yumpoo.http.business")
            .publishPercentiles(.95)
            .register(registry);
        var recorded = captureAlerts(sampler, List.of("DB_POOL_SATURATED", "HTTP_ERROR_RATE", "HTTP_LATENCY_P95"));
        Instant began = clock.instant();
        for (int i = 0; i < 45; i++) {
            for (int request = 0; request < 30; request++) {
                requests.record(4, TimeUnit.SECONDS);
                business.record(4, TimeUnit.SECONDS);
            }
            sampler.sample();
            sampler.collectIo(clock.instant(), new HashMap<>(), List.of());
            assertThat(sampler.snapshot().health()).isEqualTo("DEGRADED");
            clock.advance(15);
        }
        assertThat(recorded).isEmpty();
        verify(jdbc, never()).queryForObject("SELECT 1", Integer.class);
        when(pool.getIdleConnections()).thenReturn(1);
        when(pool.getThreadsAwaitingConnection()).thenReturn(0);
        sampler.sample();
        sampler.collectIo(clock.instant(), new HashMap<>(), List.of());
        clock.advance(15);
        sampler.sample();
        sampler.collectIo(clock.instant(), new HashMap<>(), List.of());
        assertThat(recorded)
            .extracting(OperationsModels.Alert::ruleCode)
            .contains("DB_POOL_SATURATED", "HTTP_ERROR_RATE", "HTTP_LATENCY_P95");
        assertThat(
            recorded
                .stream()
                .filter(a -> a.ruleCode().equals("DB_POOL_SATURATED"))
                .findFirst()
                .orElseThrow()
                .startedAt()
        ).isEqualTo(began.plusSeconds(120));
    }

    @Test
    void slowProbeUsesContinuousSampleTimesInsteadOfIoCompletionIntervals() {
        var sampler = sampler(true);
        var recorded = captureAlerts(sampler, List.of("DB_SLOW"));
        Instant began = clock.instant();
        ReflectionTestUtils.setField(sampler, "probeStartedAt", began);
        for (int i = 0; i < 24; i++) {
            clock.advance(15);
            sampler.sample();
        }
        assertThat(recorded).isEmpty();
        sampler.collectIo(clock.instant(), new HashMap<>(), List.of());
        assertThat(recorded)
            .singleElement()
            .satisfies(alert -> {
                assertThat(alert.ruleCode()).isEqualTo("DB_SLOW");
                assertThat(alert.startedAt()).isEqualTo(began.plusSeconds(315));
                assertThat(alert.peakValue()).isGreaterThanOrEqualTo(315000);
            });
    }

    @Test
    void p95UsesGlobalTrafficRatherThanTheSlowestRoute() {
        var sampler = sampler(true);
        var registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(sampler, "registry", registry);
        var fast = io.micrometer.core.instrument.Timer.builder("http.server.requests")
            .tags("uri", "/api/v1/fast", "status", "200")
            .publishPercentiles(.95)
            .register(registry);
        var slow = io.micrometer.core.instrument.Timer.builder("http.server.requests")
            .tags("uri", "/api/v1/upload", "status", "200")
            .publishPercentiles(.95)
            .register(registry);
        var business = io.micrometer.core.instrument.Timer.builder("yumpoo.http.business")
            .publishPercentiles(.95)
            .register(registry);
        sampler.sample();
        for (int i = 0; i < 99; i++) {
            fast.record(100, TimeUnit.MILLISECONDS);
            business.record(100, TimeUnit.MILLISECONDS);
        }
        slow.record(10, TimeUnit.SECONDS);
        business.record(10, TimeUnit.SECONDS);
        clock.advance(15);
        sampler.sample();
        assertThat(sampler.snapshot().metrics().get("http.p95")).isBetween(90d, 200d);
    }

    private List<OperationsModels.Alert> captureAlerts(OpsSampler sampler, List<String> codes) {
        var recorded = new ArrayList<OperationsModels.Alert>();
        var rules = codes
            .stream()
            .map(code -> {
                var type = com.yumpoo.platform.operations.domain.AlertRuleType.valueOf(code);
                return new OperationsModels.Rule(
                    code,
                    true,
                    type.warning,
                    type.critical,
                    type.duration,
                    1,
                    "\"1\"",
                    type.below ? "LTE" : "GTE",
                    type.unit
                );
            })
            .toList();
        when(repository.rules()).thenReturn(rules);
        when(repository.lockRule(anyString())).thenAnswer(call ->
            rules
                .stream()
                .filter(rule -> rule.code().equals(call.getArgument(0)))
                .findFirst()
                .orElseThrow()
        );
        when(repository.firing()).thenAnswer(call ->
            recorded
                .stream()
                .filter(alert -> alert.status().equals("FIRING"))
                .toList()
        );
        when(repository.findAlert(any(), anyBoolean())).thenAnswer(call ->
            recorded
                .stream()
                .filter(alert -> alert.id().equals(call.getArgument(0)))
                .findFirst()
        );
        doAnswer(call -> {
            recorded.add(call.getArgument(0));
            return null;
        })
            .when(repository)
            .createAlert(any());
        doAnswer(call -> {
            OperationsModels.Alert updated = call.getArgument(0);
            recorded.removeIf(alert -> alert.id().equals(updated.id()));
            recorded.add(updated);
            return null;
        })
            .when(repository)
            .updateAlert(any());
        var service = new OperationsAlertService(
            repository,
            mock(com.yumpoo.platform.audit.api.SecurityAuditAppendPort.class),
            tools.jackson.databind.json.JsonMapper.builder().build(),
            clock
        );
        ReflectionTestUtils.setField(sampler, "alerts", service);
        return recorded;
    }

    static class MutableClock extends Clock {

        volatile Instant now = Instant.parse("2026-09-26T12:00:00Z");

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
