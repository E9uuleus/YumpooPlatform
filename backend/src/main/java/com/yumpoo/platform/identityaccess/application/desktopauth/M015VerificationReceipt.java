package com.yumpoo.platform.identityaccess.application.desktopauth;

import com.yumpoo.platform.foundation.application.request.RequestIdContext;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record M015VerificationReceipt(
        int schemaVersion,
        String status,
        String requestId,
        String corpFingerprint,
        String memberFingerprint,
        Instant verifiedAt,
        String signature
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String PASS = "PASS";

    private static final Pattern SHA_256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public M015VerificationReceipt {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported M0-15 receipt schema version");
        }
        if (!PASS.equals(status)) {
            throw new IllegalArgumentException("M0-15 receipt status must be PASS");
        }
        if (!RequestIdContext.isValid(requestId)) {
            throw new IllegalArgumentException("requestId has an invalid format");
        }
        requireSha256(corpFingerprint, "corpFingerprint");
        requireSha256(memberFingerprint, "memberFingerprint");
        Objects.requireNonNull(verifiedAt, "verifiedAt must not be null");
        requireSha256(signature, "signature");
    }

    String canonicalUnsignedValue() {
        return "schemaVersion=" + schemaVersion
                + "\nstatus=" + status
                + "\nrequestId=" + requestId
                + "\ncorpFingerprint=" + corpFingerprint
                + "\nmemberFingerprint=" + memberFingerprint
                + "\nverifiedAt=" + verifiedAt;
    }

    private static void requireSha256(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!SHA_256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256 hex");
        }
    }
}
