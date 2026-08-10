package com.yumpoo.platform.identityaccess.application.oauth;

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

/**
 * 使用独立密钥生成稳定的身份指纹，并签署 M0-12 验证收据。
 */
public final class M012VerificationReceiptSigner {

    private static final String HMAC_SHA_256 = "HmacSHA256";
    private static final int MINIMUM_KEY_BYTES = 32;
    private static final int MINIMUM_DISTINCT_CODE_POINTS = 8;
    private static final HexFormat HEX = HexFormat.of();
    private static final Set<String> INSECURE_KEY_MARKERS = Set.of(
            "change-me",
            "changeme",
            "placeholder",
            "password",
            "secret-key"
    );

    private final byte[] key;
    private final Clock clock;

    public M012VerificationReceiptSigner(String key, Clock clock) {
        this.key = requireStrongKey(key);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public M012VerificationReceipt sign(String corpId, String memberId, String requestId) {
        String corpFingerprint = hmacHex("WECOM_CORP\0" + requireIdentity(corpId, "corpId"));
        String memberFingerprint = hmacHex(
                "WECOM_MEMBER\0" + corpId + "\0" + requireIdentity(memberId, "memberId")
        );
        M012VerificationReceipt unsigned = new M012VerificationReceipt(
                M012VerificationReceipt.CURRENT_SCHEMA_VERSION,
                M012VerificationReceipt.PASS,
                requestId,
                corpFingerprint,
                memberFingerprint,
                clock.instant(),
                "0".repeat(64)
        );
        return new M012VerificationReceipt(
                unsigned.schemaVersion(),
                unsigned.status(),
                unsigned.requestId(),
                unsigned.corpFingerprint(),
                unsigned.memberFingerprint(),
                unsigned.verifiedAt(),
                hmacHex(unsigned.canonicalUnsignedValue())
        );
    }

    public boolean verifies(M012VerificationReceipt receipt) {
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
        Objects.requireNonNull(value, "M0-12 evidence HMAC key must not be null");
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
            throw new IllegalArgumentException("M0-12 evidence HMAC key does not meet the strength policy");
        }
        return encoded;
    }
}
