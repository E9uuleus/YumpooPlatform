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

    static CurrentAuthenticationResponse from(
            CurrentAuthenticationView view,
            java.util.Set<PlatformRoleCode> platformRoles
    ) {
        java.util.ArrayList<Role> roles = new java.util.ArrayList<>();
        roles.add(Role.COMPANY_MEMBER);
        if (platformRoles.contains(PlatformRoleCode.COMPANY_ADMIN)) {
            roles.add(Role.COMPANY_ADMIN);
        }
        if (platformRoles.contains(PlatformRoleCode.APP_MANAGER)) {
            roles.add(Role.APP_MANAGER);
        }
        return new CurrentAuthenticationResponse(
                new User(
                        view.user().userId(),
                        view.user().displayName(),
                        view.user().workspaceSlug()
                ),
                new Company(
                        view.company().companyId(),
                        view.company().displayName(),
                        view.company().timezone().getId(),
                        view.company().weekStartDay().name()
                ),
                List.copyOf(roles),
                new Client(ClientType.valueOf(view.clientType()), Compatibility.SUPPORTED)
        );
    }

    public record User(UUID id, String displayName, String workspaceSlug) {
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
