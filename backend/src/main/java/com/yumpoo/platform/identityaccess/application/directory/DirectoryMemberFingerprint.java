package com.yumpoo.platform.identityaccess.application.directory;

import java.util.Objects;
import java.util.regex.Pattern;

/** 以独立证据密钥生成的企业微信成员 HMAC-SHA-256 指纹。 */
public record DirectoryMemberFingerprint(String value)
        implements Comparable<DirectoryMemberFingerprint> {

    private static final Pattern LOWERCASE_SHA_256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public DirectoryMemberFingerprint {
        Objects.requireNonNull(value, "directory member fingerprint must not be null");
        if (!LOWERCASE_SHA_256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "directory member fingerprint must be lowercase HMAC-SHA-256 hex"
            );
        }
    }

    @Override
    public int compareTo(DirectoryMemberFingerprint other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return "DirectoryMemberFingerprint[REDACTED]";
    }
}
