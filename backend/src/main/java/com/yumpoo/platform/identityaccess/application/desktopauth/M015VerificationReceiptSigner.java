package com.yumpoo.platform.identityaccess.application.desktopauth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class M015VerificationReceiptSigner {

    private static final String HMAC_SHA_256 = "HmacSHA256";
    private static final int MINIMUM_KEY_BYTES = 32;
    private static final int MINIMUM_DISTINCT_CODE_POINTS = 8;
    private static final HexFormat HEX = HexFormat.of();
    private static final Set<String> INSECURE_KEY_MARKERS = Set.of(
            "change-me", "changeme", "placeholder", "password", "secret-key"
    );

    private final byte[] key;
    private final Clock clock;

    public M015VerificationReceiptSigner(String key, Clock clock) {
        this.key = requireStrongKey(key);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public DesktopIdentityFingerprint fingerprint(String corpId, String memberId) {
        String validatedCorpId = requireIdentity(corpId, "corpId");
        String validatedMemberId = requireIdentity(memberId, "memberId");
        return new DesktopIdentityFingerprint(
                hmacHex("M015_WECOM_CORP\0" + validatedCorpId),
                hmacHex("M015_WECOM_MEMBER\0" + validatedCorpId + "\0" + validatedMemberId)
        );
    }

    public M015VerificationReceipt sign(
            DesktopIdentityFingerprint fingerprint,
            String requestId
    ) {
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        M015VerificationReceipt unsigned = new M015VerificationReceipt(
                M015VerificationReceipt.CURRENT_SCHEMA_VERSION,
                M015VerificationReceipt.PASS,
                requestId,
                fingerprint.corpFingerprint(),
                fingerprint.memberFingerprint(),
                clock.instant(),
                "0".repeat(64)
        );
        return new M015VerificationReceipt(
                unsigned.schemaVersion(),
                unsigned.status(),
                unsigned.requestId(),
                unsigned.corpFingerprint(),
                unsigned.memberFingerprint(),
                unsigned.verifiedAt(),
                hmacHex(unsigned.canonicalUnsignedValue())
        );
    }

    public boolean verifies(M015VerificationReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt must not be null");
        byte[] expected = HEX.parseHex(hmacHex(receipt.canonicalUnsignedValue()));
        byte[] actual = HEX.parseHex(receipt.signature());
        return MessageDigest.isEqual(expected, actual);
    }

    private String hmacHex(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(key, HMAC_SHA_256));
            return HEX.formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static String requireIdentity(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static byte[] requireStrongKey(String value) {
        Objects.requireNonNull(value, "M0-15 evidence HMAC key must not be null");
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        String normalized = value.toLowerCase(Locale.ROOT);
        boolean insecureMarker = INSECURE_KEY_MARKERS.stream().anyMatch(normalized::contains);
        long distinctCodePoints = value.codePoints()
                .distinct()
                .limit(MINIMUM_DISTINCT_CODE_POINTS)
                .count();
        if (encoded.length < MINIMUM_KEY_BYTES
                || distinctCodePoints < MINIMUM_DISTINCT_CODE_POINTS
                || insecureMarker) {
            throw new IllegalArgumentException("M0-15 evidence HMAC key does not meet the strength policy");
        }
        return encoded;
    }
}
