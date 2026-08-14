package com.yumpoo.platform.identityaccess.application.authentication;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttempt;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptHash;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptHasher;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptStore;
import com.yumpoo.platform.identityaccess.application.oauth.SecureOAuthAttemptTokenGenerator;
import com.yumpoo.platform.identityaccess.application.oauth.WeComDependencyUnavailableException;
import com.yumpoo.platform.identityaccess.application.oauth.WeComMemberIdentity;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebAuthenticationServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-14T06:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void dependencyFailureConsumesAttemptBeforeProviderAndReplayBecomesUnauthorized() {
        InMemoryAttemptStore store = new InMemoryAttemptStore();
        AtomicInteger exchanges = new AtomicInteger();
        WebIdentityProvider provider = provider(code -> {
            exchanges.incrementAndGet();
            throw new WeComDependencyUnavailableException();
        });
        AuthenticationEventService events = mock(AuthenticationEventService.class);
        WebAuthenticationService service = service(store, provider, events, mock(WebLoginCompletionService.class));
        WebLoginAuthorization authorization = service.begin("m106-dependency");
        String state = query(authorization.authorizationUri(), "state");

        assertError(
                () -> service.complete("one-time-code", state, authorization.nonce().value()),
                StandardErrorCode.DEPENDENCY_UNAVAILABLE
        );
        assertError(
                () -> service.complete("one-time-code", state, authorization.nonce().value()),
                StandardErrorCode.AUTHENTICATION_REQUIRED
        );
        assertThat(exchanges).hasValue(1);
        verify(events).loginRejected("CALLBACK", "DEPENDENCY_UNAVAILABLE");
        verify(events).loginRejected("CALLBACK", "AUTHENTICATION_REQUIRED");
    }

    @Test
    void corpMismatchIsUniformlyRejectedWithoutResolvingTheLocalIdentity() {
        InMemoryAttemptStore store = new InMemoryAttemptStore();
        WebIdentityProvider provider = provider(
                code -> new WeComMemberIdentity("other-corp", "member-test")
        );
        AuthenticationEventService events = mock(AuthenticationEventService.class);
        WebLoginCompletionService completion = mock(WebLoginCompletionService.class);
        WebAuthenticationService service = service(store, provider, events, completion);
        WebLoginAuthorization authorization = service.begin("m106-corp-mismatch");

        assertError(
                () -> service.complete(
                        "one-time-code",
                        query(authorization.authorizationUri(), "state"),
                        authorization.nonce().value()
                ),
                StandardErrorCode.AUTHENTICATION_REQUIRED
        );
        verify(completion, never()).complete("member-test");
    }

    @Test
    void validExistingIdentityDelegatesToTransactionalCompletion() {
        InMemoryAttemptStore store = new InMemoryAttemptStore();
        WebIdentityProvider provider = provider(
                code -> new WeComMemberIdentity("corp-test", "member-test")
        );
        WebLoginCompletionService completion = mock(WebLoginCompletionService.class);
        IssuedSession issued = mock(IssuedSession.class);
        when(completion.complete("member-test")).thenReturn(issued);
        WebAuthenticationService service = service(
                store,
                provider,
                mock(AuthenticationEventService.class),
                completion
        );
        WebLoginAuthorization authorization = service.begin("m106-success");

        assertThat(service.complete(
                "one-time-code",
                query(authorization.authorizationUri(), "state"),
                authorization.nonce().value()
        )).isSameAs(issued);
    }

    private static WebAuthenticationService service(
            OAuthAttemptStore store,
            WebIdentityProvider provider,
            AuthenticationEventService events,
            WebLoginCompletionService completion
    ) {
        return new WebAuthenticationService(
                store,
                provider,
                new SecureOAuthAttemptTokenGenerator(),
                new OAuthAttemptHasher(),
                completion,
                events,
                CLOCK
        );
    }

    private static WebIdentityProvider provider(Exchange exchange) {
        return new WebIdentityProvider() {
            @Override
            public String expectedCorpId() {
                return "corp-test";
            }

            @Override
            public URI buildAuthorizationUri(String state) {
                return URI.create("https://identity.test/authorize?state=" + state);
            }

            @Override
            public WeComMemberIdentity exchangeCode(String code) {
                return exchange.apply(code);
            }
        };
    }

    private static String query(URI uri, String name) {
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (name.equals(parts[0])) {
                return parts[1];
            }
        }
        throw new IllegalArgumentException("missing query parameter " + name);
    }

    private static void assertError(Runnable call, StandardErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(expected));
    }

    @FunctionalInterface
    private interface Exchange {
        WeComMemberIdentity apply(String code);
    }

    private static final class InMemoryAttemptStore implements OAuthAttemptStore {

        private OAuthAttempt current;
        private boolean consumed;

        @Override
        public void create(OAuthAttempt attempt) {
            current = attempt;
        }

        @Override
        public synchronized boolean consume(
                OAuthAttemptHash stateHash,
                OAuthAttemptHash nonceHash,
                Instant consumedAt
        ) {
            if (current == null
                    || consumed
                    || !current.stateHash().equals(stateHash)
                    || !current.nonceHash().equals(nonceHash)
                    || consumedAt.isBefore(current.createdAt())
                    || !consumedAt.isBefore(current.expiresAt())) {
                return false;
            }
            consumed = true;
            return true;
        }
    }
}
