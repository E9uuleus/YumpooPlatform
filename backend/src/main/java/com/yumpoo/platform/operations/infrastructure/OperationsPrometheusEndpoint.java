package com.yumpoo.platform.operations.infrastructure;

import com.yumpoo.platform.operations.application.OperationsAlertService;
import com.yumpoo.platform.operations.application.OperationsModels.RuntimeSnapshot;
import com.yumpoo.platform.operations.application.OperationsRuntime;
import com.yumpoo.platform.operations.domain.AlertRuleType;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

@Component
@Endpoint(id = "prometheus")
public class OperationsPrometheusEndpoint {
    private static final List<String> METRICS = List.of(
        "heap.used", "heap.max", "heap.ratio", "nonheap.used", "threads.live", "gc.pause",
        "http.requests", "http.errors", "http.errorRate", "http.p95",
        "db.pool.active", "db.pool.pending", "db.pool.max", "db.failures", "db.ping",
        "outbox.oldestAge", "outbox.dead", "logs.error5m", "alerts.bufferedSamples", "alerts.droppedSamples"
    );
    private final OperationsRuntime runtime;
    private final OperationsAlertService alerts;

    public OperationsPrometheusEndpoint(OperationsRuntime runtime, OperationsAlertService alerts) {
        this.runtime = runtime;
        this.alerts = alerts;
    }

    @ReadOperation(produces = "text/plain;version=0.0.4;charset=utf-8")
    public String scrape() {
        var cached = runtime.sampledSnapshot();
        StringBuilder output = new StringBuilder();
        gauge(output, "yumpoo_ops_sample_timestamp_seconds", cached.map(RuntimeSnapshot::sampledAt).map(Instant::getEpochSecond).orElse(0L));
        gauge(output, "yumpoo_ops_io_timestamp_seconds", cached.map(RuntimeSnapshot::ioUpdatedAt).map(Instant::getEpochSecond).orElse(0L));
        gauge(output, "yumpoo_ops_alert_evaluation_timestamp_seconds", alerts.cachedEvaluatedAt().getEpochSecond());
        if (cached.isPresent()) {
            for (String metric : METRICS) {
                Double value = cached.get().metrics().get(metric);
                gauge(output, "yumpoo_ops_" + metric.replace('.', '_').toLowerCase(Locale.ROOT), value == null ? Double.NaN : value);
            }
        }
        var active = alerts.cached();
        for (AlertRuleType rule : AlertRuleType.values()) {
            if (rule == AlertRuleType.HOST_CPU_HIGH || rule == AlertRuleType.DISK_LOW) continue;
            for (String severity : List.of("WARNING", "CRITICAL")) {
                long count = active.stream().filter(a -> a.ruleCode().equals(rule.name()) && a.severity().equals(severity)).count();
                output.append("yumpoo_ops_alert_active{rule=\"").append(rule.name()).append("\",severity=\"")
                    .append(severity.toLowerCase(Locale.ROOT)).append("\"} ").append(count).append('\n');
            }
        }
        return output.toString();
    }

    private static void gauge(StringBuilder output, String name, double value) {
        output.append(name).append(' ').append(value).append('\n');
    }
}
