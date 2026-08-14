package com.yumpoo.platform.catalog.api;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

public record ProjectAccessSnapshot(
        UUID projectId,
        UUID companyId,
        ProjectLifecycle lifecycle,
        ActorProjectAccess actorAccess,
        long projectVersion,
        OptionalLong membershipVersion
) {

    public ProjectAccessSnapshot {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        Objects.requireNonNull(actorAccess, "actorAccess must not be null");
        Objects.requireNonNull(membershipVersion, "membershipVersion must not be null");
        if (projectVersion < 0) {
            throw new IllegalArgumentException("projectVersion must not be negative");
        }
        if (membershipVersion.isPresent() && membershipVersion.getAsLong() < 0) {
            throw new IllegalArgumentException("membershipVersion must not be negative");
        }
        if (actorAccess.membershipBacked() != membershipVersion.isPresent()) {
            throw new IllegalArgumentException("membership access and version must be consistent");
        }
    }

    public enum ProjectLifecycle {
        DRAFT,
        ACTIVE,
        ARCHIVED
    }

    public enum ActorProjectAccess {
        MEMBER(true),
        OWNER(true),
        COMPANY_ADMIN_READ_ONLY(false);

        private final boolean membershipBacked;

        ActorProjectAccess(boolean membershipBacked) {
            this.membershipBacked = membershipBacked;
        }

        public boolean membershipBacked() {
            return membershipBacked;
        }
    }
}
