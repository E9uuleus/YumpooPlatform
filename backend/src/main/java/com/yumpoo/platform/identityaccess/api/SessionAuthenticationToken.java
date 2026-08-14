package com.yumpoo.platform.identityaccess.api;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

final class SessionAuthenticationToken extends AbstractAuthenticationToken {

    private final CurrentActor actor;

    SessionAuthenticationToken(CurrentActor actor) {
        super(List.of(new SimpleGrantedAuthority("ROLE_COMPANY_MEMBER")));
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
}
