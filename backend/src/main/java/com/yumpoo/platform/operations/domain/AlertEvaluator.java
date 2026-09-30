package com.yumpoo.platform.operations.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class AlertEvaluator {

    private final Map<String, Window> windows = new HashMap<>();

    private record Window(long version, Instant last, Instant pending, Instant recovery, Instant deescalation) {}

    public record Decision(String event, String severity) {}

    public synchronized AlertEvaluator copy() {
        AlertEvaluator result = new AlertEvaluator();
        result.windows.putAll(windows);
        return result;
    }

    public synchronized Decision evaluate(
        String key,
        long version,
        AlertRuleType type,
        boolean enabled,
        Double warning,
        Double critical,
        int duration,
        Double value,
        String firingSeverity,
        Instant now
    ) {
        Window previous = windows.get(key);
        if (
            previous == null ||
            previous.version != version ||
            Duration.between(previous.last, now).isNegative() ||
            Duration.between(previous.last, now).getSeconds() > 45
        ) previous = new Window(version, now, null, null, null);
        if (!enabled) {
            windows.remove(key);
            return firingSeverity == null ? null : new Decision("RULE_DISABLED", firingSeverity);
        }
        if (value == null || !Double.isFinite(value)) {
            windows.put(key, new Window(version, now, null, null, null));
            return null;
        }
        String severity = type.severity(value, warning, critical);
        Instant pending = previous.pending,
            recovery = previous.recovery,
            deescalation = previous.deescalation;
        Decision decision = null;
        if (firingSeverity == null) {
            pending = severity == null ? null : pending == null ? now : pending;
            if (pending != null && Duration.between(pending, now).getSeconds() >= duration) decision = new Decision(
                "FIRED",
                severity
            );
            recovery = null;
            deescalation = null;
        } else if (severity == null) {
            recovery = recovery == null ? now : recovery;
            pending = null;
            deescalation = null;
            if (Duration.between(recovery, now).getSeconds() >= 60) decision = new Decision("RESOLVED", firingSeverity);
        } else {
            recovery = null;
            pending = null;
            if ("WARNING".equals(firingSeverity) && "CRITICAL".equals(severity)) decision = new Decision(
                "ESCALATED",
                severity
            );
            else if ("CRITICAL".equals(firingSeverity) && "WARNING".equals(severity)) {
                deescalation = deescalation == null ? now : deescalation;
                if (Duration.between(deescalation, now).getSeconds() >= 60) decision = new Decision(
                    "DEESCALATED",
                    severity
                );
            } else deescalation = null;
        }
        if (decision != null) {
            pending = null;
            recovery = null;
            deescalation = null;
        }
        windows.put(key, new Window(version, now, pending, recovery, deescalation));
        return decision;
    }
}
