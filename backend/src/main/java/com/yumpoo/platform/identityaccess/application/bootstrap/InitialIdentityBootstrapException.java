package com.yumpoo.platform.identityaccess.application.bootstrap;

import java.util.Objects;
import java.util.UUID;

public final class InitialIdentityBootstrapException extends RuntimeException {

    private final String stage;
    private final String errorCode;
    private final UUID directoryRunId;

    public InitialIdentityBootstrapException(String stage, String errorCode, String safeMessage) {
        this(stage, errorCode, null, safeMessage);
    }

    public InitialIdentityBootstrapException(
            String stage,
            String errorCode,
            UUID directoryRunId,
            String safeMessage
    ) {
        super(requireSafeMessage(safeMessage));
        this.stage = requireCode(stage, "stage");
        this.errorCode = requireCode(errorCode, "errorCode");
        this.directoryRunId = directoryRunId;
    }

    public String stage() {
        return stage;
    }

    public String errorCode() {
        return errorCode;
    }

    public UUID directoryRunId() {
        return directoryRunId;
    }

    private static String requireCode(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!value.matches("^[A-Z][A-Z0-9_]{1,63}$")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String requireSafeMessage(String value) {
        Objects.requireNonNull(value, "safeMessage must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("safeMessage must not be blank");
        }
        return value;
    }
}
