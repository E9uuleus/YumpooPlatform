package com.yumpoo.platform.administration.application;

public record ProjectArchiveBlockerReport(
        ProjectArchiveBlockerSource source,
        long count,
        boolean complete
) {
    public ProjectArchiveBlockerReport {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        if (count < 0) throw new IllegalArgumentException("count must not be negative");
    }
}
