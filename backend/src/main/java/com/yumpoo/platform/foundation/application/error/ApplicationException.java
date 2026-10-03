package com.yumpoo.platform.foundation.application.error;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 应用层可预期拒绝。该类型只承载已审查的公开消息和字段错误，不暴露内部 cause。
 */
public final class ApplicationException extends RuntimeException {

    private final StandardErrorCode errorCode;
    private final List<FieldViolation> fieldViolations;
    private final String reason;
    private final List<SafeBlocker> blockers;
    private final Map<String, Object> safeDetails;

    public ApplicationException(StandardErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), List.of(), null, List.of());
    }

    public ApplicationException(StandardErrorCode errorCode, String safeMessage) {
        this(errorCode, safeMessage, List.of(), null, List.of());
    }

    public ApplicationException(
            StandardErrorCode errorCode,
            String safeMessage,
            List<FieldViolation> fieldViolations
    ) {
        this(errorCode, safeMessage, fieldViolations, null, List.of());
    }

    public ApplicationException(
            StandardErrorCode errorCode,
            String safeMessage,
            List<FieldViolation> fieldViolations,
            String reason
    ) {
        this(errorCode, safeMessage, fieldViolations, reason, List.of());
    }

    public ApplicationException(StandardErrorCode errorCode, String safeMessage,
            List<FieldViolation> fieldViolations, String reason, List<SafeBlocker> blockers) {
        this(errorCode, safeMessage, fieldViolations, reason, blockers, Map.of());
    }

    private ApplicationException(StandardErrorCode errorCode, String safeMessage,
            List<FieldViolation> fieldViolations, String reason, List<SafeBlocker> blockers,
            Map<String, ?> safeDetails) {
        super(requireSafeMessage(safeMessage));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        this.fieldViolations = List.copyOf(fieldViolations);
        this.reason = normalizeReason(reason);
        this.blockers = List.copyOf(blockers).stream()
                .sorted(java.util.Comparator.comparing(SafeBlocker::code)).toList();
        this.safeDetails = copySafeDetails(safeDetails);
    }

    public StandardErrorCode errorCode() {
        return errorCode;
    }

    public List<FieldViolation> fieldViolations() {
        return fieldViolations;
    }

    public String reason() {
        return reason;
    }

    public List<SafeBlocker> blockers() {
        return blockers;
    }

    public Map<String, Object> safeDetails() { return safeDetails; }

    /** Only explicitly reviewed, contract-defined public scalar values may be supplied by application code. */
    public static ApplicationException withSafeDetails(StandardErrorCode errorCode, String reason,
            Map<String, ?> safeDetails) {
        return new ApplicationException(errorCode, errorCode.defaultMessage(), List.of(), reason, List.of(), safeDetails);
    }

    public static ApplicationException withReason(StandardErrorCode errorCode, String reason) {
        return new ApplicationException(errorCode, errorCode.defaultMessage(), List.of(), reason);
    }

    public static ApplicationException withBlockers(StandardErrorCode errorCode, String reason,
            List<SafeBlocker> blockers) {
        return new ApplicationException(errorCode, errorCode.defaultMessage(), List.of(), reason, blockers);
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

    private static String normalizeReason(String reason) {
        if (reason == null) {
            return null;
        }
        String normalized = reason.strip();
        if (normalized.isEmpty() || normalized.length() > 80
                || !normalized.matches("^[A-Z][A-Z0-9_]{1,79}$")) {
            throw new IllegalArgumentException("reason must be a stable uppercase code");
        }
        return normalized;
    }

    private static Map<String, Object> copySafeDetails(Map<String, ?> details) {
        details.forEach((key, value) -> {
            if (key == null || !key.matches("[a-z][a-zA-Z0-9]*") || key.equals("reason") || key.equals("blockers"))
                throw new IllegalArgumentException("additional detail keys must not replace standard details");
            if (!(value instanceof String || value instanceof UUID || value instanceof Boolean
                    || value instanceof Integer || value instanceof Long))
                throw new IllegalArgumentException("additional details must contain immutable public scalar values");
        });
        return Map.copyOf(details);
    }
}
