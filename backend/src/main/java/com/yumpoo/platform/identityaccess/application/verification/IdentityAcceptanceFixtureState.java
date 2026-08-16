package com.yumpoo.platform.identityaccess.application.verification;

public record IdentityAcceptanceFixtureState(
        long userCount,
        long externalIdentityCount,
        long platformRoleAssignmentCount
) {

    public IdentityAcceptanceFixtureState {
        if (userCount < 0 || externalIdentityCount < 0 || platformRoleAssignmentCount < 0) {
            throw new IllegalArgumentException("fixture state counts must not be negative");
        }
    }

    public boolean pristine() {
        return userCount == 0 && externalIdentityCount == 0
                && platformRoleAssignmentCount == 0;
    }
}
