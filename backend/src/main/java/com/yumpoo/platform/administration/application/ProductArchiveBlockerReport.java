package com.yumpoo.platform.administration.application;

public record ProductArchiveBlockerReport(
        ProductArchiveBlockerSource source,
        long count,
        boolean complete
) {
    public ProductArchiveBlockerReport {
        if (count < 0) throw new IllegalArgumentException("count must not be negative");
    }
}
