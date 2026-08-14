package com.yumpoo.platform.identityaccess.application.directory;

public record DirectorySyncCounts(
        int discovered,
        int staged,
        int created,
        int updated,
        int unchanged,
        int left,
        int returned,
        int failed,
        int notApplied
) {
    public DirectorySyncCounts {
        if (discovered < 0 || staged < 0 || created < 0 || updated < 0
                || unchanged < 0 || left < 0 || returned < 0 || failed < 0
                || notApplied < 0) {
            throw new IllegalArgumentException("directory sync counts must not be negative");
        }
        long outcomeTotal = (long) created + updated + unchanged + returned + failed + notApplied;
        if (staged > discovered || outcomeTotal > discovered) {
            throw new IllegalArgumentException("directory sync counts exceed the discovered total");
        }
    }
}
