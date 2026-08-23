package com.yumpoo.platform.workitem.domain;

import java.math.BigInteger;
import java.util.Optional;
import java.util.regex.Pattern;

public final class KanbanRank {
    public static final int WIDTH = 39;
    private static final Pattern VALUE = Pattern.compile("^[0-9]{39}$");
    private static final BigInteger LOWER_BOUND = BigInteger.ZERO;
    private static final BigInteger UPPER_BOUND = BigInteger.TEN.pow(WIDTH).subtract(BigInteger.ONE);

    private KanbanRank() {}

    public static String require(String value) {
        if (value == null || !VALUE.matcher(value).matches())
            throw new IllegalArgumentException("rank must be a 39 digit Kanban position");
        BigInteger numeric = new BigInteger(value);
        if (numeric.compareTo(LOWER_BOUND) <= 0 || numeric.compareTo(UPPER_BOUND) >= 0)
            throw new IllegalArgumentException("rank must stay inside reserved Kanban boundaries");
        return value;
    }

    public static Optional<String> between(String lower, String upper) {
        BigInteger left = lower == null ? LOWER_BOUND : new BigInteger(require(lower));
        BigInteger right = upper == null ? UPPER_BOUND : new BigInteger(require(upper));
        if (left.compareTo(right) >= 0)
            throw new IllegalArgumentException("rank boundaries must be ordered");
        BigInteger candidate = left.add(right).divide(BigInteger.TWO);
        if (candidate.equals(left) || candidate.equals(right)) return Optional.empty();
        return Optional.of(format(candidate));
    }

    public static String evenlySpaced(int position, int total) {
        if (position < 1 || total < position)
            throw new IllegalArgumentException("rank spacing position is invalid");
        return format(UPPER_BOUND.multiply(BigInteger.valueOf(position))
                .divide(BigInteger.valueOf(total + 1L)));
    }

    private static String format(BigInteger value) {
        return "%0".concat(Integer.toString(WIDTH)).concat("d").formatted(value);
    }
}
