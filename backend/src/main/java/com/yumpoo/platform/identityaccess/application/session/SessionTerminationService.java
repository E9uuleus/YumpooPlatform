package com.yumpoo.platform.identityaccess.application.session;

import com.yumpoo.platform.identityaccess.domain.session.SessionRevocationReason;
import com.yumpoo.platform.identityaccess.domain.session.SessionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class SessionTerminationService {

    private final SessionRepository repository;

    public SessionTerminationService(SessionRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void terminateAuthenticationFailure(
            UUID sessionId,
            SessionStatus status,
            SessionRevocationReason reason,
            Instant occurredAt
    ) {
        repository.terminateIfActive(sessionId, status, reason, occurredAt);
    }
}
