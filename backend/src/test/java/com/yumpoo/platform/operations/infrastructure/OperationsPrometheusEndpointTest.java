package com.yumpoo.platform.operations.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yumpoo.platform.operations.application.OperationsAlertService;
import com.yumpoo.platform.operations.application.OperationsModels.RuntimeSnapshot;
import com.yumpoo.platform.operations.application.OperationsRuntime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OperationsPrometheusEndpointTest {
    @Test
    void scrapeReadsOnlyPublishedMemoryAndDoesNotExportPathsOrUnboundedMetricNames() {
        var runtime = mock(OperationsRuntime.class);
        var alerts = mock(OperationsAlertService.class);
        Instant at = Instant.parse("2026-10-23T12:00:00Z");
        when(runtime.sampledSnapshot()).thenReturn(Optional.of(new RuntimeSnapshot(
            at, at.minusSeconds(15), "DOWN", Map.of("heap.used", 123d, "private.path", 999d),
            List.of(), List.of(), null, 0
        )));
        when(alerts.cached()).thenReturn(List.of());
        when(alerts.cachedEvaluatedAt()).thenReturn(at.minusSeconds(30));
        String text = new OperationsPrometheusEndpoint(runtime, alerts).scrape();
        assertThat(text).contains("yumpoo_ops_heap_used 123.0", "yumpoo_ops_db_ping NaN")
            .doesNotContain("private", "HOST_CPU_HIGH", "DISK_LOW", "hostName", "subjectKey");
        verify(runtime, never()).snapshot();
        verify(alerts, never()).rules();
    }

    @Test
    void startupWithoutSamplesIsUnknownAndDoesNotSynthesizeFreshTimestamp() {
        var runtime = mock(OperationsRuntime.class);
        var alerts = mock(OperationsAlertService.class);
        when(runtime.sampledSnapshot()).thenReturn(Optional.empty());
        when(alerts.cached()).thenReturn(List.of());
        when(alerts.cachedEvaluatedAt()).thenReturn(Instant.EPOCH);
        assertThat(new OperationsPrometheusEndpoint(runtime, alerts).scrape())
            .contains("yumpoo_ops_sample_timestamp_seconds 0.0", "yumpoo_ops_alert_evaluation_timestamp_seconds 0.0");
        verify(runtime, never()).snapshot();
    }
}
