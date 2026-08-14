package com.yumpoo.platform.identityaccess.application.directory;

import com.yumpoo.platform.identityaccess.domain.identity.ProfileHash;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 固定长度前缀的 UTF-8 canonical SHA-256。 */
public final class DirectoryCanonicalHash {

    private static final HexFormat HEX = HexFormat.of();

    private DirectoryCanonicalHash() {
    }

    public static String strings(String domain, List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, domain);
            for (String value : values) {
                add(digest, value);
            }
            return HEX.formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static ProfileHash profile(WeComRawMemberProfile raw, String departmentSummary) {
        return new ProfileHash(strings("wecom-profile-v1", List.of(
                raw.externalUserId(),
                raw.displayName(),
                raw.email().state().name(),
                raw.email().value() == null ? "" : raw.email().value(),
                raw.mobile().state().name(),
                raw.mobile().value() == null ? "" : raw.mobile().value(),
                departmentSummary == null ? "" : departmentSummary
        )));
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
