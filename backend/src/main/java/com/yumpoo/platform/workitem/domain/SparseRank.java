package com.yumpoo.platform.workitem.domain;

import java.math.BigInteger;
import java.util.Optional;
import java.util.regex.Pattern;

public final class SparseRank {
    public static final int WIDTH = 39;
    private static final Pattern VALUE = Pattern.compile("^[0-9]{39}$");
    private static final BigInteger LOWER_BOUND = BigInteger.ZERO;
    private static final BigInteger UPPER_BOUND = BigInteger.TEN.pow(WIDTH).subtract(BigInteger.ONE);

    private SparseRank() {}

    public static String require(String value, String field) {
        if (value == null || !VALUE.matcher(value).matches())
            throw new IllegalArgumentException(field + " must be a 39 digit sparse position");
        BigInteger numeric = new BigInteger(value);
        if (numeric.compareTo(LOWER_BOUND) <= 0 || numeric.compareTo(UPPER_BOUND) >= 0)
            throw new IllegalArgumentException(field + " must stay inside reserved boundaries");
        return value;
    }

    public static Optional<String> between(String lower, String upper, String field) {
        BigInteger left = lower == null ? LOWER_BOUND : new BigInteger(require(lower, field));
        BigInteger right = upper == null ? UPPER_BOUND : new BigInteger(require(upper, field));
        if (left.compareTo(right) >= 0)
            throw new IllegalArgumentException(field + " boundaries must be ordered");
        BigInteger candidate = left.add(right).divide(BigInteger.TWO);
        if (candidate.equals(left) || candidate.equals(right)) return Optional.empty();
        return Optional.of(format(candidate));
    }

    public static String evenlySpaced(int position, int total) {
        if (position < 1 || total < position)
            throw new IllegalArgumentException("sparse rank spacing position is invalid");
        return format(UPPER_BOUND.multiply(BigInteger.valueOf(position))
                .divide(BigInteger.valueOf(total + 1L)));
    }

    public static Optional<String> evenlySpacedBetween(String lower, String upper,
            int position, int total, String field) {
        if (position < 1 || total < position)
            throw new IllegalArgumentException("sparse rank spacing position is invalid");
        BigInteger left = lower == null ? LOWER_BOUND : new BigInteger(require(lower, field));
        BigInteger right = upper == null ? UPPER_BOUND : new BigInteger(require(upper, field));
        if (left.compareTo(right) >= 0)
            throw new IllegalArgumentException(field + " boundaries must be ordered");
        BigInteger step = right.subtract(left).divide(BigInteger.valueOf(total + 1L));
        if (step.signum() == 0) return Optional.empty();
        return Optional.of(format(left.add(step.multiply(BigInteger.valueOf(position)))));
    }

    private static String format(BigInteger value) {
        return "%0".concat(Integer.toString(WIDTH)).concat("d").formatted(value);
    }
}
