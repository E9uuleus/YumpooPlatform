package com.yumpoo.platform.workitem.domain;

import java.util.Optional;

public final class ProjectSortKey {
    private static final String FIELD = "projectSortKey";

    private ProjectSortKey() {}

    public static String require(String value) {
        return SparseRank.require(value, FIELD);
    }

    public static Optional<String> between(String lower, String upper) {
        return SparseRank.between(lower, upper, FIELD);
    }

    public static String evenlySpaced(int position, int total) {
        return SparseRank.evenlySpaced(position, total);
    }

    public static Optional<String> evenlySpacedBetween(String lower, String upper,
            int position, int total) {
        return SparseRank.evenlySpacedBetween(lower, upper, position, total, FIELD);
    }
}
