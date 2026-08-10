package com.yumpoo.platform.foundation.application.error;

import java.util.List;
import java.util.Objects;

/**
 * 应用层可预期拒绝。该类型只承载已审查的公开消息和字段错误，不暴露内部 cause。
 */
public final class ApplicationException extends RuntimeException {

    private final StandardErrorCode errorCode;
    private final List<FieldViolation> fieldViolations;

    public ApplicationException(StandardErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), List.of());
    }

    public ApplicationException(StandardErrorCode errorCode, String safeMessage) {
        this(errorCode, safeMessage, List.of());
    }

    public ApplicationException(
            StandardErrorCode errorCode,
            String safeMessage,
            List<FieldViolation> fieldViolations
    ) {
        super(requireSafeMessage(safeMessage));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        this.fieldViolations = List.copyOf(fieldViolations);
    }

    public StandardErrorCode errorCode() {
        return errorCode;
    }

    public List<FieldViolation> fieldViolations() {
        return fieldViolations;
    }

    public static ApplicationException validation(FieldViolation... violations) {
        return new ApplicationException(
                StandardErrorCode.VALIDATION_FAILED,
                StandardErrorCode.VALIDATION_FAILED.defaultMessage(),
                List.of(violations)
        );
    }

    private static String requireSafeMessage(String message) {
        Objects.requireNonNull(message, "safeMessage must not be null");
        if (message.isBlank()) {
            throw new IllegalArgumentException("safeMessage must not be blank");
        }
        return message;
    }
}
