package com.yumpoo.platform.identityaccess.application.desktopauth;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.application.authentication.AuthenticationEventService;
import com.yumpoo.platform.identityaccess.application.authentication.AuthenticationUser;
import com.yumpoo.platform.identityaccess.application.authentication.AuthenticationUserRepository;
import com.yumpoo.platform.identityaccess.application.authentication.WebIdentityProvider;
import com.yumpoo.platform.identityaccess.application.oauth.WeComAuthenticationFailedException;
import com.yumpoo.platform.identityaccess.application.oauth.WeComDependencyUnavailableException;
import com.yumpoo.platform.identityaccess.application.oauth.WeComMemberIdentity;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public class ProductDesktopAuthenticationService {

    public static final Duration AUTHORIZE_TTL = Duration.ofMinutes(5);
    public static final Duration HANDOFF_TTL = Duration.ofSeconds(60);
    private static final int MAX_AUTHORIZATION_CODE_LENGTH = 512;

    private final DesktopAuthAttemptStore attemptStore;
    private final DesktopAuthTokenGenerator tokenGenerator;
    private final DesktopAuthTokenHasher tokenHasher;
    private final WebIdentityProvider identityProvider;
    private final AuthenticationUserRepository userRepository;
    private final CompanyConfigurationQuery companyQuery;
    private final SessionService sessionService;
    private final AuthenticationEventService eventService;
    private final Clock clock;

    public ProductDesktopAuthenticationService(
            DesktopAuthAttemptStore attemptStore,
            DesktopAuthTokenGenerator tokenGenerator,
            DesktopAuthTokenHasher tokenHasher,
            WebIdentityProvider identityProvider,
            AuthenticationUserRepository userRepository,
            CompanyConfigurationQuery companyQuery,
            SessionService sessionService,
            AuthenticationEventService eventService,
            Clock clock
    ) {
        this.attemptStore = Objects.requireNonNull(attemptStore);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.tokenHasher = Objects.requireNonNull(tokenHasher);
        this.identityProvider = Objects.requireNonNull(identityProvider);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.companyQuery = Objects.requireNonNull(companyQuery);
        this.sessionService = Objects.requireNonNull(sessionService);
        this.eventService = Objects.requireNonNull(eventService);
        this.clock = Objects.requireNonNull(clock);
    }

    public ProductDesktopAuthorization begin(
            DesktopAuthToken state,
            PkceS256Challenge challenge,
            String requestId,
            String clientVersion,
            String clientProtocolVersion
    ) {
        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plus(AUTHORIZE_TTL);
        URI authorizationUri;
        try {
            authorizationUri = identityProvider.buildElectronAuthorizationUri(state.value());
            attemptStore.createProduct(new ProductDesktopAuthAttempt(
                    tokenHasher.hash(state), challenge, requestId, clientVersion,
                    clientProtocolVersion, createdAt, expiresAt
            ));
        } catch (WeComAuthenticationFailedException | IllegalArgumentException exception) {
            throw authenticationRequired();
        } catch (WeComDependencyUnavailableException | DataAccessResourceFailureException exception) {
            throw dependencyUnavailable();
        }
        return new ProductDesktopAuthorization(authorizationUri, expiresAt);
    }

    @Transactional
    public DesktopHandoffAuthorization completeAuthorization(
            String authorizationCode,
            DesktopAuthToken state
    ) {
        Instant claimedAt = clock.instant();
        DesktopAuthTokenHash stateHash = tokenHasher.hash(state);
        if (!validAuthorizationCode(authorizationCode)
                || !attemptStore.claimProductAuthorization(stateHash, claimedAt)) {
            reject("ELECTRON_CALLBACK", StandardErrorCode.AUTHENTICATION_REQUIRED);
            throw authenticationRequired();
        }

        WeComMemberIdentity identity;
        try {
            identity = Objects.requireNonNull(identityProvider.exchangeCode(authorizationCode));
        } catch (WeComAuthenticationFailedException exception) {
            reject("ELECTRON_CALLBACK", StandardErrorCode.AUTHENTICATION_REQUIRED);
            throw authenticationRequired();
        } catch (RuntimeException exception) {
            reject("ELECTRON_CALLBACK", StandardErrorCode.DEPENDENCY_UNAVAILABLE);
            throw dependencyUnavailable();
        }
        if (!identityProvider.expectedCorpId().equals(identity.corpId())) {
            reject("ELECTRON_CALLBACK", StandardErrorCode.AUTHENTICATION_REQUIRED);
            throw authenticationRequired();
        }

        AuthenticationUser user = userRepository.lockByWeComIdentity(
                        companyQuery.current().companyId(), identity.memberId()
                )
                .filter(AuthenticationUser::loginEligible)
                .orElseThrow(ProductDesktopAuthenticationService::authenticationRequired);
        DesktopAuthToken handoffCode = tokenGenerator.generate();
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(HANDOFF_TTL);
        if (!attemptStore.issueProductHandoff(
                stateHash, tokenHasher.hash(handoffCode), user.userId(), issuedAt, expiresAt
        )) {
            throw authenticationRequired();
        }
        return new DesktopHandoffAuthorization(handoffCode, state, expiresAt);
    }

    @Transactional
    public IssuedSession exchange(
            DesktopAuthToken handoffCode,
            DesktopAuthToken state,
            PkceVerifier verifier
    ) {
        ProductDesktopAuthExchange exchange = attemptStore.consumeProduct(
                        tokenHasher.hash(state), tokenHasher.hash(handoffCode),
                        verifier.challenge(), clock.instant()
                )
                .orElseThrow(ProductDesktopAuthenticationService::authenticationRequired);
        IssuedSession issued = sessionService.issueElectronSession(
                exchange.userId(), exchange.clientVersion()
        );
        AuthenticationUser user = userRepository.findByUserId(exchange.userId())
                .orElseThrow(ProductDesktopAuthenticationService::authenticationRequired);
        eventService.loginSucceeded(user, issued);
        return issued;
    }

    private void reject(String stage, StandardErrorCode code) {
        try {
            eventService.loginRejected(stage, code.name(), "ELECTRON");
        } catch (RuntimeException ignored) {
            // Authentication failure remains authoritative; audit is best effort here.
        }
    }

    private static boolean validAuthorizationCode(String code) {
        return code != null && !code.isBlank() && code.length() <= MAX_AUTHORIZATION_CODE_LENGTH;
    }

    private static ApplicationException authenticationRequired() {
        return new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static ApplicationException dependencyUnavailable() {
        return new ApplicationException(StandardErrorCode.DEPENDENCY_UNAVAILABLE);
    }
}
