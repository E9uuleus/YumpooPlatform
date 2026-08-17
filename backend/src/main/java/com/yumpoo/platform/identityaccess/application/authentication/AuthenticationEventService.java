package com.yumpoo.platform.identityaccess.application.authentication;

import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.application.audit.IdentitySecurityAuditRecorder;
import com.yumpoo.platform.identityaccess.application.session.AuthenticatedSession;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthenticationEventService {

    public static final String LOGIN_SUCCEEDED = "identity.login_succeeded";
    public static final String LOGIN_REJECTED = "identity.login_rejected";
    public static final String USER_SESSIONS_REVOKED = "identity.user_sessions_revoked";

    private final TransactionalEventPort eventPort;
    private final CompanyConfigurationQuery companyQuery;
    private final ObjectMapper objectMapper;
    private final IdentitySecurityAuditRecorder auditRecorder;

    public AuthenticationEventService(
            TransactionalEventPort eventPort,
            CompanyConfigurationQuery companyQuery,
            ObjectMapper objectMapper,
            IdentitySecurityAuditRecorder auditRecorder
    ) {
        this.eventPort = eventPort;
        this.companyQuery = companyQuery;
        this.objectMapper = objectMapper;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void loginSucceeded(AuthenticationUser user, IssuedSession issuedSession) {
        String clientType = issuedSession.session().clientType().name();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", user.userId());
        payload.put("clientType", clientType);
        eventPort.append(new EventDraft(
                LOGIN_SUCCEEDED,
                1,
                "LoginSession",
                issuedSession.session().id(),
                0,
                user.companyId(),
                EventActor.user(user.userId()),
                objectMapper.valueToTree(payload)
        ));
        auditRecorder.succeeded(
                user.companyId(), "login:" + issuedSession.session().id(), "LOGIN_SUCCEEDED",
                EventActor.user(user.userId()), Set.of(), "LOGIN_SESSION", issuedSession.session().id(),
                null, null, Map.of("clientType", clientType), null, clientType,
                issuedSession.session().clientVersion());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void loginRejected(String stage, String outcomeCode) {
        loginRejected(stage, outcomeCode, "WEB");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void loginRejected(String stage, String outcomeCode, String clientType) {
        CompanyConfigurationSnapshot company = companyQuery.current();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", stage);
        payload.put("outcomeCode", outcomeCode);
        payload.put("clientType", clientType);
        eventPort.append(new EventDraft(
                LOGIN_REJECTED,
                1,
                "AuthenticationAttempt",
                UUID.randomUUID(),
                0,
                company.companyId(),
                EventActor.system("WECOM_AUTH"),
                objectMapper.valueToTree(payload)
        ));
        String requestId = RequestCorrelationContext.required().requestId();
        auditRecorder.outcome(
                company.companyId(), "login-rejected:" + requestId + ":" + stage,
                "LOGIN_REJECTED", com.yumpoo.platform.audit.api.SecurityAuditOutcome.FAILED,
                EventActor.system("WECOM_AUTH"), Set.of(), "AUTHENTICATION_ATTEMPT", requestId,
                null, null, Map.of("stage", stage, "clientType", clientType), outcomeCode,
                clientType, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void userSessionsRevoked(AuthenticatedSession authenticatedSession) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", authenticatedSession.user().userId());
        payload.put("reasonCode", "USER_LOGOUT");
        payload.put("revokedCount", 1);
        payload.put("clientType", authenticatedSession.session().clientType().name());
        eventPort.append(new EventDraft(
                USER_SESSIONS_REVOKED,
                1,
                "LoginSession",
                authenticatedSession.session().id(),
                0,
                authenticatedSession.user().companyId(),
                EventActor.user(authenticatedSession.user().userId()),
                objectMapper.valueToTree(payload)
        ));
        auditRecorder.succeeded(
                authenticatedSession.user().companyId(),
                "logout:" + authenticatedSession.session().id(), "LOGOUT_SUCCEEDED",
                EventActor.user(authenticatedSession.user().userId()), Set.of(),
                "LOGIN_SESSION", authenticatedSession.session().id(), null,
                Map.of("status", "ACTIVE"), Map.of("status", "REVOKED"), null,
                authenticatedSession.session().clientType().name(),
                authenticatedSession.session().clientVersion());
    }
}
