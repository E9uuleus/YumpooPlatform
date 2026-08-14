package com.yumpoo.platform.identityaccess.application.directory;

/** 仅携带稳定错误码与安全摘要的同步失败。 */
public final class DirectorySyncException extends RuntimeException {

    private final String errorCode;
    private final String safeSummary;
    private final DirectorySyncFailureScope scope;

    public DirectorySyncException(String errorCode, String safeSummary) {
        this(errorCode, safeSummary, DirectorySyncFailureScope.RUN_FATAL);
    }

    public DirectorySyncException(
            String errorCode,
            String safeSummary,
            DirectorySyncFailureScope scope
    ) {
        super(errorCode);
        if (errorCode == null || !errorCode.matches("^[A-Z][A-Z0-9_]{0,79}$")) {
            throw new IllegalArgumentException("errorCode is invalid");
        }
        if (safeSummary == null || safeSummary.isBlank() || safeSummary.length() > 500) {
            throw new IllegalArgumentException("safeSummary is invalid");
        }
        this.errorCode = errorCode;
        this.safeSummary = safeSummary.trim();
        this.scope = java.util.Objects.requireNonNull(scope, "scope must not be null");
    }

    public String errorCode() {
        return errorCode;
    }

    public String safeSummary() {
        return safeSummary;
    }

    public DirectorySyncFailureScope scope() {
        return scope;
    }

    @Override
    public String toString() {
        return "DirectorySyncException[errorCode=" + errorCode + "]";
    }
}
