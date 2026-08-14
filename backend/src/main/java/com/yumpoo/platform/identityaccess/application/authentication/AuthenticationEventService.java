package com.yumpoo.platform.identityaccess.application.authentication;

import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
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
import java.util.UUID;

@Service
public class AuthenticationEventService {

    public static final String LOGIN_SUCCEEDED = "identity.login_succeeded";
    public static final String LOGIN_REJECTED = "identity.login_rejected";
    public static final String USER_SESSIONS_REVOKED = "identity.user_sessions_revoked";

    private final TransactionalEventPort eventPort;
    private final CompanyConfigurationQuery companyQuery;
    private final ObjectMapper objectMapper;

    public AuthenticationEventService(
            TransactionalEventPort eventPort,
            CompanyConfigurationQuery companyQuery,
            ObjectMapper objectMapper
    ) {
        this.eventPort = eventPort;
        this.companyQuery = companyQuery;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void loginSucceeded(AuthenticationUser user, IssuedSession issuedSession) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", user.userId());
        payload.put("clientType", "WEB");
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
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void loginRejected(String stage, String outcomeCode) {
        CompanyConfigurationSnapshot company = companyQuery.current();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", stage);
        payload.put("outcomeCode", outcomeCode);
        payload.put("clientType", "WEB");
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
    }
}
