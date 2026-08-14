package com.yumpoo.platform.identityaccess.application.session;

import java.util.Objects;

public record CredentialFingerprint(String keyVersion, String value) {

    public CredentialFingerprint {
        Objects.requireNonNull(keyVersion, "keyVersion must not be null");
        Objects.requireNonNull(value, "value must not be null");
        if (!keyVersion.matches("[A-Za-z0-9._-]{1,32}") || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("credential fingerprint is invalid");
        }
    }

    @Override
    public String toString() {
        return "CredentialFingerprint[keyVersion=" + keyVersion + ", value=REDACTED]";
    }
}
