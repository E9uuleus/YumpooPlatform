package com.yumpoo.platform.workitem.domain;

import java.util.Optional;

public final class KanbanRank {
    public static final int WIDTH = SparseRank.WIDTH;

    private KanbanRank() {}

    public static String require(String value) {
        return SparseRank.require(value, "rank");
    }

    public static Optional<String> between(String lower, String upper) {
        return SparseRank.between(lower, upper, "rank");
    }

    public static String evenlySpaced(int position, int total) {
        return SparseRank.evenlySpaced(position, total);
    }
}
