package com.yumpoo.platform.identityaccess.application.desktopauth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

public final class SecureDesktopAuthTokenGenerator implements DesktopAuthTokenGenerator {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom;

    public SecureDesktopAuthTokenGenerator() {
        this(new SecureRandom());
    }

    SecureDesktopAuthTokenGenerator(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
    }

    @Override
    public DesktopAuthToken generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return DesktopAuthToken.of(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
}
