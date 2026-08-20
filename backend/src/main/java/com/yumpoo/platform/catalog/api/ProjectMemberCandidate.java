package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public record ProjectMemberCandidate(
        UUID userId, String displayName, String employmentStatus, String accountStatus,
        String membershipStatus, boolean owner, Long membershipRowVersion, String membershipEtag
) {}
