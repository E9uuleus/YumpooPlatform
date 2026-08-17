package com.yumpoo.platform.identityaccess.application.desktopauth;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.application.authentication.AuthenticationEventService;
import com.yumpoo.platform.identityaccess.application.authentication.AuthenticationUser;
import com.yumpoo.platform.identityaccess.application.authentication.AuthenticationUserRepository;
import com.yumpoo.platform.identityaccess.application.authentication.WebIdentityProvider;
import com.yumpoo.platform.identityaccess.application.oauth.WeComMemberIdentity;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.identity.EmploymentStatus;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductDesktopAuthenticationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T03:00:00Z");
    private static final UUID COMPANY_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final DesktopAuthToken STATE = token('S');
    private static final DesktopAuthToken HANDOFF = token('H');
    private static final PkceVerifier VERIFIER = PkceVerifier.of("V".repeat(43));

    private DesktopAuthAttemptStore attemptStore;
    private WebIdentityProvider identityProvider;
    private AuthenticationUserRepository userRepository;
    private SessionService sessionService;
    private AuthenticationEventService eventService;
    private ProductDesktopAuthenticationService service;

    @BeforeEach
    void setUp() {
        attemptStore = mock(DesktopAuthAttemptStore.class);
        identityProvider = mock(WebIdentityProvider.class);
        userRepository = mock(AuthenticationUserRepository.class);
        sessionService = mock(SessionService.class);
        eventService = mock(AuthenticationEventService.class);
        CompanyConfigurationQuery companyQuery = mock(CompanyConfigurationQuery.class);

        when(identityProvider.expectedCorpId()).thenReturn("corp");
        when(companyQuery.current()).thenReturn(new CompanyConfigurationSnapshot(
                COMPANY_ID, "Yumpoo", ZoneId.of("Asia/Shanghai"), DayOfWeek.MONDAY, 480, 0
        ));
        service = new ProductDesktopAuthenticationService(
                attemptStore,
                () -> HANDOFF,
                new DesktopAuthTokenHasher(),
                identityProvider,
                userRepository,
                companyQuery,
                sessionService,
                eventService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void createsProductAttemptForOfficialQrAuthorization() {
        when(identityProvider.buildElectronAuthorizationUri(STATE.value())).thenReturn(URI.create(
                "https://open.work.weixin.qq.com/wwopen/sso/qrConnect?state=" + STATE.value()
        ));

        ProductDesktopAuthorization authorization = service.begin(
                STATE, VERIFIER.challenge(), "m114.begin-1", "0.1.0", "1"
        );

        assertThat(authorization.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(authorization.authorizationUri().getHost()).isEqualTo("open.work.weixin.qq.com");
        verify(attemptStore).createProduct(new ProductDesktopAuthAttempt(
                new DesktopAuthTokenHasher().hash(STATE), VERIFIER.challenge(),
                "m114.begin-1", "0.1.0", "1", NOW, NOW.plusSeconds(300)
        ));
    }

    @Test
    void claimsCallbackBeforeProviderExchangeAndIssuesSixtySecondHandoff() {
        AuthenticationUser user = eligibleUser();
        DesktopAuthTokenHash stateHash = new DesktopAuthTokenHasher().hash(STATE);
        when(attemptStore.claimProductAuthorization(stateHash, NOW)).thenReturn(true);
        when(identityProvider.exchangeCode("provider-code")).thenReturn(new WeComMemberIdentity("corp", "member"));
        when(userRepository.lockByWeComIdentity(COMPANY_ID, "member")).thenReturn(Optional.of(user));
        when(attemptStore.issueProductHandoff(
                stateHash, new DesktopAuthTokenHasher().hash(HANDOFF), USER_ID, NOW, NOW.plusSeconds(60)
        )).thenReturn(true);

        DesktopHandoffAuthorization authorization = service.completeAuthorization("provider-code", STATE);

        assertThat(authorization.handoffCode()).isEqualTo(HANDOFF);
        assertThat(authorization.desktopState()).isEqualTo(STATE);
        assertThat(authorization.expiresAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void rejectsReplayBeforeCallingProvider() {
        when(attemptStore.claimProductAuthorization(new DesktopAuthTokenHasher().hash(STATE), NOW)).thenReturn(false);

        assertThatThrownBy(() -> service.completeAuthorization("provider-code", STATE))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(StandardErrorCode.AUTHENTICATION_REQUIRED));
        verify(identityProvider, never()).exchangeCode("provider-code");
    }

    @Test
    void consumesPkceHandoffAndIssuesElectronSession() {
        AuthenticationUser user = eligibleUser();
        ProductDesktopAuthExchange exchange = new ProductDesktopAuthExchange(
                USER_ID, "0.1.0", "1", NOW.minusSeconds(10), NOW
        );
        IssuedSession issued = mock(IssuedSession.class);
        when(attemptStore.consumeProduct(
                new DesktopAuthTokenHasher().hash(STATE),
                new DesktopAuthTokenHasher().hash(HANDOFF),
                VERIFIER.challenge(), NOW
        )).thenReturn(Optional.of(exchange));
        when(sessionService.issueElectronSession(USER_ID, "0.1.0")).thenReturn(issued);
        when(userRepository.findByUserId(USER_ID)).thenReturn(Optional.of(user));

        assertThat(service.exchange(HANDOFF, STATE, VERIFIER)).isSameAs(issued);
        verify(sessionService).issueElectronSession(USER_ID, "0.1.0");
        verify(eventService).loginSucceeded(user, issued);
    }

    private static AuthenticationUser eligibleUser() {
        return new AuthenticationUser(
                USER_ID, COMPANY_ID, "Member", EmploymentStatus.ACTIVE,
                AccountStatus.ENABLED, EmploymentStatus.ACTIVE, 2, 3
        );
    }

    private static DesktopAuthToken token(char value) {
        return DesktopAuthToken.of(String.valueOf(value).repeat(43));
    }
}
