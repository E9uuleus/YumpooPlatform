package com.yumpoo.platform.catalog.application.authorization;

import com.yumpoo.platform.catalog.domain.authorization.ProjectAccessFacts;
import com.yumpoo.platform.foundation.domain.authorization.AuthorizationDecision;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.catalog.application.authorization.ProjectAuthorizationService.Action.ORDINARY_WRITE;
import static com.yumpoo.platform.catalog.application.authorization.ProjectAuthorizationService.Action.READ;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectAuthorizationServiceTest {

    private static final UUID COMPANY = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_COMPANY = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private final ProjectAuthorizationService service = new ProjectAuthorizationService();

    @Test
    void memberAndOwnerCanReadAndWriteRegardlessOfPlatformRole() {
        for (Set<PlatformRoleCode> roles : roleSets()) {
            CurrentActor actor = actor(roles);
            assertThat(decide(actor, new ProjectAccessFacts(COMPANY, true, false), READ))
                    .isEqualTo(AuthorizationDecision.ALLOW);
            assertThat(decide(actor, new ProjectAccessFacts(COMPANY, true, true), ORDINARY_WRITE))
                    .isEqualTo(AuthorizationDecision.ALLOW);
        }
    }

    @Test
    void companyAdminNonMemberIsReadOnlyAndCombinedRolesUseCapabilityUnion() {
        for (Set<PlatformRoleCode> roles : ListSets.adminSets()) {
            CurrentActor actor = actor(roles);
            ProjectAccessFacts facts = new ProjectAccessFacts(COMPANY, false, false);
            assertThat(decide(actor, facts, READ)).isEqualTo(AuthorizationDecision.ALLOW);
            assertThat(decide(actor, facts, ORDINARY_WRITE))
                    .isEqualTo(AuthorizationDecision.DENY_VISIBLE);
        }
    }

    @Test
    void appManagerOnlyAndOrdinaryNonMemberAreHidden() {
        ProjectAccessFacts facts = new ProjectAccessFacts(COMPANY, false, false);
        assertThat(decide(actor(Set.of(PlatformRoleCode.APP_MANAGER)), facts, READ))
                .isEqualTo(AuthorizationDecision.DENY_HIDDEN);
        assertThat(decide(actor(Set.of()), facts, ORDINARY_WRITE))
                .isEqualTo(AuthorizationDecision.DENY_HIDDEN);
    }

    @Test
    void missingAndCrossCompanyResourcesAreHiddenEvenFromAdmin() {
        CurrentActor admin = actor(Set.of(PlatformRoleCode.COMPANY_ADMIN));
        assertThat(service.decide(admin, Optional.empty(), READ))
                .isEqualTo(AuthorizationDecision.DENY_HIDDEN);
        assertThat(decide(admin, new ProjectAccessFacts(OTHER_COMPANY, true, true), READ))
                .isEqualTo(AuthorizationDecision.DENY_HIDDEN);
    }

    private AuthorizationDecision decide(
            CurrentActor actor,
            ProjectAccessFacts facts,
            ProjectAuthorizationService.Action action
    ) {
        return service.decide(actor, Optional.of(facts), action);
    }

    private static CurrentActor actor(Set<PlatformRoleCode> roles) {
        return new CurrentActor(USER, COMPANY, 7, roles);
    }

    private static java.util.List<Set<PlatformRoleCode>> roleSets() {
        return java.util.List.of(
                Set.of(),
                Set.of(PlatformRoleCode.COMPANY_ADMIN),
                Set.of(PlatformRoleCode.APP_MANAGER),
                Set.of(PlatformRoleCode.COMPANY_ADMIN, PlatformRoleCode.APP_MANAGER)
        );
    }

    private static final class ListSets {
        private ListSets() {
        }

        static java.util.List<Set<PlatformRoleCode>> adminSets() {
            return java.util.List.of(
                    Set.of(PlatformRoleCode.COMPANY_ADMIN),
                    Set.of(PlatformRoleCode.COMPANY_ADMIN, PlatformRoleCode.APP_MANAGER)
            );
        }
    }
}
