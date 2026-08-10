package com.yumpoo.platform.foundation.api.error;

import java.util.Objects;

public record ApiFieldError(String field, String code, String message) {

    public ApiFieldError {
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
