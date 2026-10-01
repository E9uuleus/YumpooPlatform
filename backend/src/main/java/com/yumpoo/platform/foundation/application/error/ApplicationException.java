package com.yumpoo.platform.foundation.application.error;

import java.util.List;
import java.util.Objects;

/**
 * 应用层可预期拒绝。该类型只承载已审查的公开消息和字段错误，不暴露内部 cause。
 */
public final class ApplicationException extends RuntimeException {

    private final StandardErrorCode errorCode;
    private final List<FieldViolation> fieldViolations;
    private final String reason;
    private final List<SafeBlocker> blockers;
    private final ConnectionTargetUse connectionTargetUse;

    public record ConnectionTargetUse(java.util.UUID targetProjectId, long activeConnectionCount) {
        public ConnectionTargetUse {
            Objects.requireNonNull(targetProjectId);
            if (activeConnectionCount < 1) throw new IllegalArgumentException("active connection count must be positive");
        }
    }

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
        this(errorCode, safeMessage, fieldViolations, reason, blockers, null);
    }

    private ApplicationException(StandardErrorCode errorCode, String safeMessage,
            List<FieldViolation> fieldViolations, String reason, List<SafeBlocker> blockers,
            ConnectionTargetUse connectionTargetUse) {
        super(requireSafeMessage(safeMessage));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        this.fieldViolations = List.copyOf(fieldViolations);
        this.reason = normalizeReason(reason);
        this.blockers = List.copyOf(blockers).stream()
                .sorted(java.util.Comparator.comparing(SafeBlocker::code)).toList();
        this.connectionTargetUse = connectionTargetUse;
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

    public ConnectionTargetUse connectionTargetUse() { return connectionTargetUse; }

    public static ApplicationException connectionTargetInUse(java.util.UUID projectId, long count) {
        return new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION,
                StandardErrorCode.INVALID_STATE_TRANSITION.defaultMessage(), List.of(),
                "CONNECT_TARGET_IN_USE", List.of(), new ConnectionTargetUse(projectId, count));
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
}
