package com.yumpoo.platform.identityaccess.application.authentication;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.application.session.AuthenticatedSession;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CurrentAuthenticationQueryService {

    private final AuthenticationUserRepository userRepository;
    private final CompanyConfigurationQuery companyQuery;

    public CurrentAuthenticationQueryService(
            AuthenticationUserRepository userRepository,
            CompanyConfigurationQuery companyQuery
    ) {
        this.userRepository = userRepository;
        this.companyQuery = companyQuery;
    }

    public CurrentAuthenticationView current(AuthenticatedSession authenticatedSession) {
        AuthenticationUser user = userRepository.findByUserId(
                        authenticatedSession.user().userId()
                )
                .filter(AuthenticationUser::loginEligible)
                .orElseThrow(CurrentAuthenticationQueryService::authenticationRequired);
        var company = companyQuery.current();
        if (!company.companyId().equals(user.companyId())) {
            throw authenticationRequired();
        }
        return new CurrentAuthenticationView(
                user,
                company,
                authenticatedSession.session().clientType().name()
        );
    }

    private static ApplicationException authenticationRequired() {
        return new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }
}
