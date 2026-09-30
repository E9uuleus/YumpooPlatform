package com.yumpoo.platform.operations.application;

import com.yumpoo.platform.operations.application.OperationsModels.MetricPoint;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class MetricAggregation {

    private MetricAggregation() {}

    public static Map<String, Double> aggregate(List<MetricPoint> points) {
        Set<String> keys = new TreeSet<>();
        points.forEach(p -> keys.addAll(p.values().keySet()));
        Map<String, Double> result = new LinkedHashMap<>();
        for (String key : keys) {
            var values = points
                .stream()
                .map(p -> p.values().get(key))
                .filter(Objects::nonNull)
                .filter(Double::isFinite)
                .toList();
            if (values.isEmpty()) {
                result.put(key, null);
                continue;
            }
            double value;
            if (Set.of("http.requests", "http.errors", "gc.pause", "logs.warn", "logs.error").contains(key)) value =
                values.stream().mapToDouble(Double::doubleValue).sum();
            else if (key.startsWith("disk.") && key.endsWith(".free")) value = values
                .stream()
                .mapToDouble(Double::doubleValue)
                .min()
                .orElseThrow();
            else if (
                key.startsWith("cpu.") ||
                key.equals("memory.used") ||
                key.equals("heap.used") ||
                key.equals("nonheap.used")
            ) value = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            else if (key.endsWith(".total") || key.endsWith(".max")) value = values.getLast();
            else value = values.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }

    public static String unit(String key) {
        if (
            key.startsWith("cpu.") || key.endsWith("Ratio") || key.endsWith(".ratio") || key.equals("http.errorRate")
        ) return "RATIO";
        if (
            key.startsWith("memory.") ||
            key.startsWith("heap.") ||
            key.startsWith("nonheap.") ||
            key.startsWith("disk.")
        ) return "BYTES";
        if (Set.of("http.p95", "db.ping", "gc.pause").contains(key)) return "MS";
        if (key.equals("outbox.oldestAge")) return "SECONDS";
        return "COUNT";
    }
}
