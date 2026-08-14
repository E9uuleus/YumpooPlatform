package com.yumpoo.platform.identityaccess.api;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SessionAuthenticationTokenTest {

    @Test
    void authoritiesMatchTheBoundRoleSnapshotInStableOrder() {
        CurrentActor actor = new CurrentActor(
                UUID.fromString("81000000-0000-4000-8000-000000000120"),
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                3,
                Set.of(PlatformRoleCode.APP_MANAGER, PlatformRoleCode.COMPANY_ADMIN)
        );

        assertThat(new SessionAuthenticationToken(actor).getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .containsExactly(
                        "ROLE_COMPANY_MEMBER",
                        "ROLE_COMPANY_ADMIN",
                        "ROLE_APP_MANAGER"
                );
    }
}
