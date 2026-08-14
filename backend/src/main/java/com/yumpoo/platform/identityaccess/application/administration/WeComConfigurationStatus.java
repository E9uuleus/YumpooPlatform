package com.yumpoo.platform.identityaccess.application.administration;

public record WeComConfigurationStatus(
        OAuthStatus oauth,
        DirectoryStatus directory,
        boolean corpIdConsistent
) {
    public record OAuthStatus(
            boolean enabled,
            boolean configured,
            String corpIdMasked,
            boolean agentIdConfigured,
            boolean appSecretConfigured,
            boolean callbackConfigured
    ) {
    }

    public record DirectoryStatus(
            boolean enabled,
            boolean configured,
            String corpIdMasked,
            boolean directorySecretConfigured,
            boolean profileSecretConfigured
    ) {
    }
}
