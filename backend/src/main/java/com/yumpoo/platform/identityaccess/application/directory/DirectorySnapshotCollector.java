package com.yumpoo.platform.identityaccess.application.directory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** 拉取完整企微成员 ID 页集，并在离开受信任边界前转换为 HMAC 指纹。 */
public final class DirectorySnapshotCollector {

    public static final int MIN_PAGE_SIZE = 1;
    public static final int MAX_PAGE_SIZE = 10_000;
    public static final int MAX_PAGE_COUNT = 10_000;

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

    private final WeComDirectoryGateway gateway;
    private final String corpId;
    private final byte[] hmacKey;
    private final int pageSize;

    public DirectorySnapshotCollector(
            WeComDirectoryGateway gateway,
            String corpId,
            String hmacKey,
            int pageSize
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.corpId = requireIdentifier(corpId, "corpId");
        this.hmacKey = requireStrongKey(hmacKey);
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and 10000");
        }
        this.pageSize = pageSize;
    }

    public DirectorySnapshotResult collect() {
        TreeSet<DirectoryMemberFingerprint> fingerprints = new TreeSet<>();
        Set<String> observedCursors = new HashSet<>();
        String cursor = "";
        int completedPageCount = 0;

        while (completedPageCount < MAX_PAGE_COUNT) {
            WeComDirectoryPage page;
            try {
                page = gateway.fetchPage(cursor, pageSize);
            } catch (WeComDirectoryGatewayException exception) {
                return incomplete(fingerprints, completedPageCount, exception.failure());
            }
            if (page == null) {
                return incomplete(
                        fingerprints,
                        completedPageCount,
                        DirectorySnapshotFailure.MALFORMED_RESPONSE
                );
            }

            completedPageCount++;
            for (String memberId : page.memberIds()) {
                fingerprints.add(fingerprintMember(memberId));
            }

            if (page.hasExplicitEnd()) {
                return complete(fingerprints, completedPageCount);
            }
            if (page.hasOmittedCursor()) {
                return incomplete(
                        fingerprints,
                        completedPageCount,
                        DirectorySnapshotFailure.MISSING_CURSOR
                );
            }
            if (!observedCursors.add(page.nextCursor())) {
                return incomplete(
                        fingerprints,
                        completedPageCount,
                        DirectorySnapshotFailure.CURSOR_LOOP
                );
            }
            cursor = page.nextCursor();
        }

        return incomplete(
                fingerprints,
                completedPageCount,
                DirectorySnapshotFailure.PAGE_LIMIT_EXCEEDED
        );
    }

    private DirectorySnapshotResult.Complete complete(
            TreeSet<DirectoryMemberFingerprint> fingerprints,
            int completedPageCount
    ) {
        List<DirectoryMemberFingerprint> members = List.copyOf(fingerprints);
        return new DirectorySnapshotResult.Complete(
                members,
                corpFingerprint(),
                fingerprintSet(members),
                completedPageCount
        );
    }

    private DirectorySnapshotResult.Incomplete incomplete(
            TreeSet<DirectoryMemberFingerprint> fingerprints,
            int completedPageCount,
            DirectorySnapshotFailure failure
    ) {
        List<DirectoryMemberFingerprint> members = List.copyOf(fingerprints);
        return new DirectorySnapshotResult.Incomplete(
                members,
                corpFingerprint(),
                fingerprintSet(members),
                completedPageCount,
                failure
        );
    }

    private String corpFingerprint() {
        return hmacHex("corp\0" + corpId);
    }

    private DirectoryMemberFingerprint fingerprintMember(String memberId) {
        return new DirectoryMemberFingerprint(
                hmacHex("member\0" + corpId + "\0" + memberId)
        );
    }

    private String fingerprintSet(List<DirectoryMemberFingerprint> fingerprints) {
        List<String> values = new ArrayList<>(fingerprints.size());
        for (DirectoryMemberFingerprint fingerprint : fingerprints) {
            values.add(fingerprint.value());
        }
        return hmacHex("snapshot\0" + String.join("\n", values));
    }

    private String hmacHex(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(hmacKey, HMAC_SHA_256));
            return HEX.formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static String requireIdentifier(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(field + " must be between 1 and 256 characters");
        }
        return value;
    }

    private static byte[] requireStrongKey(String value) {
        Objects.requireNonNull(value, "directory evidence HMAC key must not be null");
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
            throw new IllegalArgumentException(
                    "directory evidence HMAC key does not meet the strength policy"
            );
        }
        return encoded;
    }
}
