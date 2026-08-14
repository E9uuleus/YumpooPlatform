package com.yumpoo.platform.identityaccess.application.authentication;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class WebLoginCompletionService {

    private final AuthenticationUserRepository userRepository;
    private final CompanyConfigurationQuery companyQuery;
    private final SessionService sessionService;
    private final AuthenticationEventService eventService;

    public WebLoginCompletionService(
            AuthenticationUserRepository userRepository,
            CompanyConfigurationQuery companyQuery,
            SessionService sessionService,
            AuthenticationEventService eventService
    ) {
        this.userRepository = userRepository;
        this.companyQuery = companyQuery;
        this.sessionService = sessionService;
        this.eventService = eventService;
    }

    @Transactional
    public IssuedSession complete(String externalUserId) {
        Objects.requireNonNull(externalUserId, "externalUserId must not be null");
        AuthenticationUser user = userRepository.lockByWeComIdentity(
                        companyQuery.current().companyId(),
                        externalUserId
                )
                .filter(AuthenticationUser::loginEligible)
                .orElseThrow(WebLoginCompletionService::authenticationRequired);
        IssuedSession issued = sessionService.issueWebSession(user.userId(), null);
        eventService.loginSucceeded(user, issued);
        return issued;
    }

    private static ApplicationException authenticationRequired() {
        return new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }
}
