package com.yumpoo.platform.identityaccess.domain.identity;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record WorkspaceSlug(String value) {

    private static final int MAX_LENGTH = 64;
    private static final Pattern VALID = Pattern.compile(
            "^[a-z0-9](?:[a-z0-9._@-]{0,62}[a-z0-9])?$"
    );
    private static final Pattern INVALID_RUN = Pattern.compile("[^a-z0-9._@-]+");
    private static final Pattern INVALID_START = Pattern.compile("^[^a-z0-9]+");
    private static final Pattern INVALID_END = Pattern.compile("[^a-z0-9]+$");
    private static final Set<String> RESERVED = Set.of("me", "new", "admin", "settings");

    public WorkspaceSlug {
        Objects.requireNonNull(value, "value must not be null");
        if (!VALID.matcher(value).matches() || RESERVED.contains(value)) {
            throw new IllegalArgumentException("workspace slug is invalid");
        }
    }

    public static WorkspaceSlug fromExternalUserId(String externalUserId, UUID userId) {
        Objects.requireNonNull(externalUserId, "externalUserId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        String candidate = Normalizer.normalize(externalUserId, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        candidate = INVALID_RUN.matcher(candidate).replaceAll("-");
        candidate = INVALID_START.matcher(candidate).replaceFirst("");
        candidate = INVALID_END.matcher(candidate).replaceFirst("");
        candidate = candidate.substring(0, Math.min(candidate.length(), MAX_LENGTH));
        candidate = INVALID_END.matcher(candidate).replaceFirst("");
        if (candidate.isEmpty() || RESERVED.contains(candidate)) {
            return fallback(userId);
        }
        return new WorkspaceSlug(candidate);
    }

    public WorkspaceSlug disambiguated(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        String suffix = "-" + uuidText(userId).substring(0, 8);
        String stem = value.substring(0, Math.min(value.length(), MAX_LENGTH - suffix.length()));
        stem = INVALID_END.matcher(stem).replaceFirst("");
        return stem.isEmpty() ? fallback(userId) : new WorkspaceSlug(stem + suffix);
    }

    public static WorkspaceSlug fallback(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return new WorkspaceSlug("u-" + uuidText(userId));
    }

    private static String uuidText(UUID userId) {
        return userId.toString().replace("-", "");
    }
}
