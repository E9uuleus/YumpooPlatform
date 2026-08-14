package com.yumpoo.platform.identityaccess.application.authentication;

import com.yumpoo.platform.organization.api.CompanyConfigurationSnapshot;

import java.util.Objects;

public record CurrentAuthenticationView(
        AuthenticationUser user,
        CompanyConfigurationSnapshot company,
        String clientType
) {

    public CurrentAuthenticationView {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(company, "company must not be null");
        Objects.requireNonNull(clientType, "clientType must not be null");
        if (clientType.isBlank()) {
            throw new IllegalArgumentException("clientType must not be blank");
        }
    }
}
