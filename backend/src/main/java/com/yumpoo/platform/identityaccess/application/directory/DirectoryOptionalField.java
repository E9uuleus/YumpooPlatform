package com.yumpoo.platform.identityaccess.application.directory;

import java.util.Objects;

/** 可选目录字段的可见性与更新语义。 */
public record DirectoryOptionalField(State state, String value) {

    public DirectoryOptionalField {
        Objects.requireNonNull(state, "state must not be null");
        if (state == State.PRESENT) {
            Objects.requireNonNull(value, "PRESENT field requires value");
            value = value.trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("PRESENT field value must not be blank");
            }
        } else if (value != null) {
            throw new IllegalArgumentException(state + " field must not carry a value");
        }
    }

    public static DirectoryOptionalField present(String value) {
        return new DirectoryOptionalField(State.PRESENT, value);
    }

    public static DirectoryOptionalField clear() {
        return new DirectoryOptionalField(State.CLEAR, null);
    }

    public static DirectoryOptionalField unavailable() {
        return new DirectoryOptionalField(State.UNAVAILABLE, null);
    }

    public String applyTo(String currentValue) {
        return switch (state) {
            case PRESENT -> value;
            case CLEAR -> null;
            case UNAVAILABLE -> currentValue;
        };
    }

    @Override
    public String toString() {
        return "DirectoryOptionalField[state=" + state + ", value=REDACTED]";
    }

    public enum State {
        PRESENT,
        CLEAR,
        UNAVAILABLE
    }
}
