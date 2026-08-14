package com.yumpoo.platform.identityaccess.application.session;

import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.identityaccess.domain.session.SessionRevocationReason;
import com.yumpoo.platform.identityaccess.application.audit.IdentitySecurityAuditRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class SessionRevocationService {

    public static final String USER_SESSIONS_REVOKED = "identity.user_sessions_revoked";

    private final SessionRepository repository;
    private final TransactionalEventPort eventPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final IdentitySecurityAuditRecorder auditRecorder;

    public SessionRevocationService(
            SessionRepository repository,
            TransactionalEventPort eventPort,
            ObjectMapper objectMapper,
            Clock clock,
            IdentitySecurityAuditRecorder auditRecorder
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.eventPort = Objects.requireNonNull(eventPort, "eventPort must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder must not be null");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeActive(
            SessionRevocationTarget target,
            SessionRevocationReason reason,
            EventActor actor
    ) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        requireBulkReason(reason);
        int revokedCount = repository.terminateActiveForUser(
                target.userId(),
                reason,
                clock.instant()
        );
        if (revokedCount == 0) {
            return 0;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", target.userId());
        payload.put("reasonCode", reason.name());
        payload.put("revokedCount", revokedCount);
        payload.put("authorizationVersion", target.authorizationVersion());
        eventPort.append(new EventDraft(
                USER_SESSIONS_REVOKED,
                2,
                "User",
                target.userId(),
                target.aggregateVersion(),
                target.companyId(),
                actor,
                objectMapper.valueToTree(payload)
        ));
        auditRecorder.succeeded(
                target.companyId(),
                "session-revocation:" + target.userId() + ":" + reason + ":" + target.aggregateVersion(),
                "SESSIONS_REVOKED", actor, Set.of(), "USER", target.userId(),
                actor.reasonReference(), null,
                Map.of("reasonCode", reason.name(), "revokedCount", revokedCount,
                        "authorizationVersion", target.authorizationVersion()),
                null, null, null);
        return revokedCount;
    }

    private static void requireBulkReason(SessionRevocationReason reason) {
        if (reason != SessionRevocationReason.EMPLOYMENT_LEFT
                && reason != SessionRevocationReason.ACCOUNT_DISABLED
                && reason != SessionRevocationReason.AUTHORIZATION_CHANGED
                && reason != SessionRevocationReason.ADMIN_FORCED) {
            throw new IllegalArgumentException("reason cannot revoke all user sessions");
        }
    }
}
