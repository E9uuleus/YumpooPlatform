package com.yumpoo.platform.audit.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record SecurityAuditActor(
        String type,
        UUID userId,
        String systemCode,
        Set<String> roleSnapshot
) {
    public SecurityAuditActor {
        Objects.requireNonNull(type, "type must not be null");
        roleSnapshot = roleSnapshot == null ? Set.of() : Set.copyOf(roleSnapshot);
    }

    public static SecurityAuditActor anonymous() {
        return new SecurityAuditActor("ANONYMOUS", null, null, Set.of());
    }

    public static SecurityAuditActor user(UUID userId, Set<String> roles) {
        return new SecurityAuditActor("USER", Objects.requireNonNull(userId), null, roles);
    }

    public static SecurityAuditActor system(String code) {
        return new SecurityAuditActor("SYSTEM", null, code, Set.of());
    }

    public static SecurityAuditActor integration(String code) {
        return new SecurityAuditActor("INTEGRATION", null, code, Set.of());
    }
}
