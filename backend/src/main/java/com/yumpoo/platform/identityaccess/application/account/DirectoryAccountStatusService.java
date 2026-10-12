package com.yumpoo.platform.identityaccess.application.account;

import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.identityaccess.application.audit.IdentitySecurityAuditRecorder;
import com.yumpoo.platform.identityaccess.application.session.SessionRevocationService;
import com.yumpoo.platform.identityaccess.application.session.SessionRevocationTarget;
import com.yumpoo.platform.identityaccess.domain.session.SessionRevocationReason;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class DirectoryAccountStatusService {
    public static final String PROVIDER_DISABLED = "DIRECTORY_ACCOUNT_DISABLED";
    public static final String SNAPSHOT_MISSING = "DIRECTORY_SNAPSHOT_MISSING";

    private final AccountStatusRepository repository;
    private final TransactionalEventPort events;
    private final SessionRevocationService sessions;
    private final IdentitySecurityAuditRecorder audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DirectoryAccountStatusService(AccountStatusRepository repository, TransactionalEventPort events,
            SessionRevocationService sessions, IdentitySecurityAuditRecorder audit, ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.sessions = sessions;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AccountStatusSnapshot> disable(UUID companyId, UUID userId, String reason, EventActor actor) {
        if (!Set.of(PROVIDER_DISABLED, SNAPSHOT_MISSING).contains(reason)) {
            throw new IllegalArgumentException("Unsupported directory account disable reason");
        }
        var changed = repository.disableFromDirectory(companyId, userId, reason, clock.instant());
        changed.ifPresent(user -> {
            events.append(new EventDraft(AccountStatusService.USER_ACCOUNT_DISABLED, 2, "User", userId,
                    user.rowVersion(), companyId, actor, mapper.valueToTree(Map.of(
                            "userId", userId, "fromStatus", "ENABLED", "toStatus", "DISABLED",
                            "reasonCode", reason, "authorizationVersion", user.authorizationVersion()))));
            sessions.revokeActive(new SessionRevocationTarget(userId, companyId,
                    user.authorizationVersion(), user.rowVersion()), SessionRevocationReason.ACCOUNT_DISABLED, actor);
            audit.succeeded(companyId, "account-status:" + userId + ":" + user.rowVersion(),
                    "ACCOUNT_DISABLED", actor, Set.of(), "USER", userId, reason,
                    Map.of("accountStatus", "ENABLED"),
                    Map.of("accountStatus", "DISABLED", "reasonCode", reason,
                            "authorizationVersion", user.authorizationVersion()), null, null, null);
        });
        return changed;
    }
}
