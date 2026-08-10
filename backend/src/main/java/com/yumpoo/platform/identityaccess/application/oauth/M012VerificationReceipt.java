package com.yumpoo.platform.identityaccess.application.oauth;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * M0-12 真实企微验证的脱敏、可校验成功收据。
 */
public record M012VerificationReceipt(
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

    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$");
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public M012VerificationReceipt {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported M0-12 receipt schema version");
        }
        if (!PASS.equals(status)) {
            throw new IllegalArgumentException("M0-12 receipt status must be PASS");
        }
        requirePattern(requestId, REQUEST_ID, "requestId");
        requirePattern(corpFingerprint, SHA256_HEX, "corpFingerprint");
        requirePattern(memberFingerprint, SHA256_HEX, "memberFingerprint");
        Objects.requireNonNull(verifiedAt, "verifiedAt must not be null");
        requirePattern(signature, SHA256_HEX, "signature");
    }

    String canonicalUnsignedValue() {
        return "schemaVersion=" + schemaVersion
                + "\nstatus=" + status
                + "\nrequestId=" + requestId
                + "\ncorpFingerprint=" + corpFingerprint
                + "\nmemberFingerprint=" + memberFingerprint
                + "\nverifiedAt=" + verifiedAt;
    }

    private static void requirePattern(String value, Pattern pattern, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " has an invalid format");
        }
    }
}
