package com.yumpoo.platform.identityaccess.application.oauth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

public final class SecureOAuthAttemptTokenGenerator implements OAuthAttemptTokenGenerator {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom;

    public SecureOAuthAttemptTokenGenerator() {
        this(new SecureRandom());
    }

    SecureOAuthAttemptTokenGenerator(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
    }

    @Override
    public OAuthAttemptToken generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return OAuthAttemptToken.of(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
}
