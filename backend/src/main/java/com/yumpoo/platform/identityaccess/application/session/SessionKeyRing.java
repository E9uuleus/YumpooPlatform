package com.yumpoo.platform.identityaccess.application.session;

import com.yumpoo.platform.identityaccess.domain.session.LoginSession;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class SessionKeyRing {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String CSRF_REPAIR_PREFIX = "yumpoo:csrf-repair:v1:";
    private final Key current;
    private final Key previous;

    public SessionKeyRing(Key current, Key previous) {
        this.current = Objects.requireNonNull(current, "current must not be null");
        this.previous = previous;
        if (previous != null && previous.version().equals(current.version())) {
            throw new IllegalArgumentException("key versions must be distinct");
        }
    }

    public CredentialFingerprint fingerprintCurrent(
            CredentialPurpose purpose,
            SessionCredential credential
    ) {
        return fingerprint(current, purpose, credential);
    }

    public SessionCredential deriveCurrentCsrfRepairCredential(LoginSession session) {
        Objects.requireNonNull(session, "session must not be null");
        String material = CSRF_REPAIR_PREFIX
                + current.version() + ":"
                + session.id() + ":"
                + session.sessionKeyVersion() + ":"
                + session.sessionTokenFingerprint();
        return new SessionCredential(
                Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(current, material))
        );
    }

    public Optional<CredentialFingerprint> fingerprint(
            String keyVersion,
            CredentialPurpose purpose,
            SessionCredential credential,
            Instant now
    ) {
        Objects.requireNonNull(now, "now must not be null");
        if (current.version().equals(keyVersion)) {
            return Optional.of(fingerprint(current, purpose, credential));
        }
        if (previous != null
                && previous.version().equals(keyVersion)
                && previous.acceptUntil() != null
                && now.isBefore(previous.acceptUntil())) {
            return Optional.of(fingerprint(previous, purpose, credential));
        }
        return Optional.empty();
    }

    public List<CredentialFingerprint> candidates(
            CredentialPurpose purpose,
            SessionCredential credential,
            Instant now
    ) {
        var result = new java.util.ArrayList<CredentialFingerprint>();
        result.add(fingerprint(current, purpose, credential));
        if (previous != null
                && previous.acceptUntil() != null
                && now.isBefore(previous.acceptUntil())) {
            result.add(fingerprint(previous, purpose, credential));
        }
        return List.copyOf(result);
    }

    public static boolean matches(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static CredentialFingerprint fingerprint(
            Key key,
            CredentialPurpose purpose,
            SessionCredential credential
    ) {
        byte[] digest = hmac(key, purpose.prefix() + credential.value());
        return new CredentialFingerprint(key.version(), HexFormat.of().formatHex(digest));
    }

    private static byte[] hmac(Key key, String material) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key.secret(), ALGORITHM));
            return mac.doFinal(material.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    @Override
    public String toString() {
        return "SessionKeyRing[current=" + current.version()
                + ", previous=" + (previous == null ? "none" : previous.version())
                + ", secrets=REDACTED]";
    }

    public record Key(String version, byte[] secret, Instant acceptUntil) {

        public Key {
            Objects.requireNonNull(version, "version must not be null");
            Objects.requireNonNull(secret, "secret must not be null");
            if (!version.matches("[A-Za-z0-9._-]{1,32}") || secret.length < 32) {
                throw new IllegalArgumentException("session key is invalid");
            }
            secret = secret.clone();
        }

        @Override
        public byte[] secret() {
            return secret.clone();
        }

        @Override
        public String toString() {
            return "Key[version=" + version + ", secret=REDACTED, acceptUntil=" + acceptUntil + "]";
        }
    }
}
