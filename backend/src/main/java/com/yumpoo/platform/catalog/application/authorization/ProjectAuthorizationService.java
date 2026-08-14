package com.yumpoo.platform.catalog.application.authorization;

import com.yumpoo.platform.catalog.domain.authorization.ProjectAccessFacts;
import com.yumpoo.platform.foundation.domain.authorization.AuthorizationDecision;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;

import java.util.Objects;
import java.util.Optional;

/** Pure policy over already scope-constrained access facts. */
public final class ProjectAuthorizationService {

    public AuthorizationDecision decide(
            CurrentActor actor,
            Optional<ProjectAccessFacts> visibleFacts,
            Action action
    ) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(visibleFacts, "visibleFacts must not be null");
        Objects.requireNonNull(action, "action must not be null");
        if (visibleFacts.isEmpty()) {
            return AuthorizationDecision.DENY_HIDDEN;
        }
        ProjectAccessFacts facts = visibleFacts.orElseThrow();
        if (!facts.companyId().equals(actor.companyId())) {
            return AuthorizationDecision.DENY_HIDDEN;
        }
        if (facts.activeMember()) {
            return AuthorizationDecision.ALLOW;
        }
        if (actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) {
            return action == Action.READ
                    ? AuthorizationDecision.ALLOW
                    : AuthorizationDecision.DENY_VISIBLE;
        }
        return AuthorizationDecision.DENY_HIDDEN;
    }

    public enum Action {
        READ,
        ORDINARY_WRITE
    }
}
