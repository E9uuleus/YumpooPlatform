package com.yumpoo.platform.identityaccess.api;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;

final class SessionAuthenticationToken extends AbstractAuthenticationToken {

    private final CurrentActor actor;

    SessionAuthenticationToken(CurrentActor actor) {
        super(authorities(actor));
        this.actor = actor;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "REDACTED";
    }

    @Override
    public CurrentActor getPrincipal() {
        return actor;
    }

    private static List<SimpleGrantedAuthority> authorities(CurrentActor actor) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_COMPANY_MEMBER"));
        if (actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_COMPANY_ADMIN"));
        }
        if (actor.hasRole(PlatformRoleCode.APP_MANAGER)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_APP_MANAGER"));
        }
        return List.copyOf(authorities);
    }
}
