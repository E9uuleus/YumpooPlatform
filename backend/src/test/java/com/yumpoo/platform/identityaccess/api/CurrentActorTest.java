package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.identityaccess.application.authorization.ManagedPlatformRole;
import com.yumpoo.platform.identityaccess.application.authorization.RoleUserSnapshot;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class CurrentActorTest {
    @Test
    void appManagerInheritsCompanyAdminWithoutChangingAssignedRoleFacts() {
        var assigned = Set.of(PlatformRoleCode.APP_MANAGER);
        var actor = new CurrentActor(UUID.randomUUID(), UUID.randomUUID(), 0, assigned);
        assertThat(actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)).isTrue();
        assertThat(actor.memberTier()).isEqualTo(PlatformRoleTier.APP_MANAGER);
        assertThat(assigned).containsExactly(PlatformRoleCode.APP_MANAGER);
        var snapshot = new RoleUserSnapshot(actor.userId(), actor.companyId(), "ACTIVE", "ENABLED", 0, 0,
                Set.of(ManagedPlatformRole.APP_MANAGER));
        assertThat(snapshot.hasEffectiveRole(ManagedPlatformRole.COMPANY_ADMIN)).isTrue();
        assertThat(snapshot.activeRoles()).containsExactly(ManagedPlatformRole.APP_MANAGER);
    }
    @Test
    void lowerTiersNeverInheritHigherRoles() {
        var member = new CurrentActor(UUID.randomUUID(), UUID.randomUUID(), 0, Set.of());
        assertThat(member.memberTier()).isEqualTo(PlatformRoleTier.COMPANY_MEMBER);
        var admin = new CurrentActor(member.userId(), member.companyId(), 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));
        assertThat(admin.memberTier()).isEqualTo(PlatformRoleTier.COMPANY_ADMIN);
        assertThat(admin.hasRole(PlatformRoleCode.APP_MANAGER)).isFalse();
    }
}
