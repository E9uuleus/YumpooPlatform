package com.yumpoo.platform.foundation.application.concurrency;

public final class StrongEtag {

    private StrongEtag() {
    }

    public static String format(long rowVersion) {
        if (rowVersion < 0) {
            throw new IllegalArgumentException("rowVersion must not be negative");
        }
        return '"' + Long.toString(rowVersion) + '"';
    }
}
