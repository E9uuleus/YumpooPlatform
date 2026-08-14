package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.identityaccess.application.authorization.GovernanceMemberState;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record GovernanceStateResponse(
        UUID userId,
        String displayName,
        String employmentStatus,
        String accountStatus,
        Set<String> platformRoles,
        long authorizationVersion,
        long rowVersion
) {
    static GovernanceStateResponse from(GovernanceMemberState state) {
        return new GovernanceStateResponse(
                state.userId(), state.displayName(), state.employmentStatus(),
                state.accountStatus(), state.platformRoles().stream()
                        .map(Enum::name).collect(Collectors.toUnmodifiableSet()),
                state.authorizationVersion(), state.rowVersion());
    }
}
