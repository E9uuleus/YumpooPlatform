package com.yumpoo.platform.identityaccess.application.session;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

public final class SecureSessionCredentialGenerator implements SessionCredentialGenerator {

    private static final int TOKEN_BYTES = 32;
    private final SecureRandom secureRandom;

    public SecureSessionCredentialGenerator() {
        this(new SecureRandom());
    }

    SecureSessionCredentialGenerator(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
    }

    @Override
    public SessionCredential generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return new SessionCredential(
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        );
    }
}
