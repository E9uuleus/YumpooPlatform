package com.yumpoo.platform.foundation.application.error;

import java.util.Objects;

/**
 * 可安全公开的字段错误，不保存被拒绝的原始值。
 */
public record FieldViolation(String field, String code, String message) {

    public FieldViolation {
        field = requireText(field, "field");
        code = requireText(code, "code");
        message = requireText(message, "message");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
