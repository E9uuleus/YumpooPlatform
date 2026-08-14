package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.identityaccess.application.authentication.CurrentAuthenticationView;

import java.util.List;
import java.util.UUID;

public record CurrentAuthenticationResponse(
        User user,
        Company company,
        List<Role> roles,
        Client client
) {

    static CurrentAuthenticationResponse from(CurrentAuthenticationView view) {
        return new CurrentAuthenticationResponse(
                new User(view.user().userId(), view.user().displayName()),
                new Company(
                        view.company().companyId(),
                        view.company().displayName(),
                        view.company().timezone().getId(),
                        view.company().weekStartDay().name()
                ),
                List.of(Role.COMPANY_MEMBER),
                new Client(ClientType.valueOf(view.clientType()), Compatibility.SUPPORTED)
        );
    }

    public record User(UUID id, String displayName) {
    }

    public record Company(
            UUID id,
            String displayName,
            String timezone,
            String weekStartDay
    ) {
    }

    public record Client(ClientType type, Compatibility compatibility) {
    }

    public enum Role {
        COMPANY_MEMBER,
        COMPANY_ADMIN,
        APP_MANAGER
    }

    public enum ClientType {
        WEB,
        ELECTRON
    }

    public enum Compatibility {
        SUPPORTED,
        DEPRECATED,
        BLOCKED
    }
}
