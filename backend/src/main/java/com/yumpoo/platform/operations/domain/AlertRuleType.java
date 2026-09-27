package com.yumpoo.platform.operations.domain;

public enum AlertRuleType {
    HOST_CPU_HIGH("cpu.system", "RATIO", false, .85, .95, 300),
    JVM_HEAP_HIGH("heap.ratio", "RATIO", false, .85, .95, 300),
    DISK_LOW("disk.freeRatio", "RATIO", true, .20, .10, 60),
    DB_UNAVAILABLE("db.failures", "COUNT", false, null, 2d, 0),
    DB_SLOW("db.ping", "MS", false, 200d, 1000d, 300),
    DB_POOL_SATURATED("db.pool.pending", "COUNT", false, 1d, 5d, 120),
    HTTP_ERROR_RATE("http.errorRate", "RATIO", false, .01, .05, 300),
    HTTP_LATENCY_P95("http.p95", "MS", false, 1500d, 3000d, 300),
    OUTBOX_BACKLOG_AGE("outbox.oldestAge", "SECONDS", false, 300d, 900d, 60),
    OUTBOX_DEAD("outbox.dead", "COUNT", false, 1d, 10d, 0),
    ERROR_LOG_BURST("logs.error5m", "COUNT", false, 10d, 50d, 0),
    CONFIG_POSTURE("posture.critical", "COUNT", false, null, 1d, 0);

    public final String metric;
    public final String unit;
    public final boolean below;
    public final Double warning;
    public final Double critical;
    public final int duration;

    AlertRuleType(String metric, String unit, boolean below, Double warning, Double critical, int duration) {
        this.metric = metric;
        this.unit = unit;
        this.below = below;
        this.warning = warning;
        this.critical = critical;
        this.duration = duration;
    }

    public String severity(double value, Double warning, Double critical) {
        if (critical != null && (below ? value <= critical : value >= critical)) return "CRITICAL";
        if (warning != null && (below ? value <= warning : value >= warning)) return "WARNING";
        return null;
    }

    public boolean valid(Double warning, Double critical, int seconds) {
        if (
            critical == null ||
            !Double.isFinite(critical) ||
            critical <= 0 ||
            (warning != null && (!Double.isFinite(warning) || warning <= 0))
        ) return false;
        if (seconds < 0 || seconds > 3600 || seconds % 15 != 0) return false;
        if (unit.equals("RATIO") && (critical > 1 || (warning != null && warning > 1))) return false;
        if (
            unit.equals("COUNT") &&
            (critical != Math.rint(critical) || (warning != null && warning != Math.rint(warning)))
        ) return false;
        if (critical > 1e9 || (warning != null && warning > 1e9)) return false;
        if ((this == DB_UNAVAILABLE || this == CONFIG_POSTURE) && (warning != null || seconds != 0)) return false;
        if (warning == null && this != DB_UNAVAILABLE && this != CONFIG_POSTURE) return false;
        return warning == null || (below ? warning > critical : warning < critical);
    }
}
