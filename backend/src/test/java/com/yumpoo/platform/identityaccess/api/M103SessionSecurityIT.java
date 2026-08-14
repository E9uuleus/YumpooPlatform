package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.identityaccess.application.account.AccountStatusChangeCommand;
import com.yumpoo.platform.identityaccess.application.account.AccountStatusUseCase;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Import({
        PostgreSqlTestContainerConfiguration.class,
        M103SessionSecurityIT.ProbeConfiguration.class
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class M103SessionSecurityIT {

    private static final UUID COMPANY_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000001"
    );
    private static final UUID USER_ID = UUID.fromString(
            "30000000-0000-4000-8000-000000000103"
    );
    private static final String API = "/api/v1/__test/m1-03/session";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private AccountStatusUseCase accountStatusUseCase;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private RevocationGate revocationGate;

    private IssuedSession issued;

    @BeforeEach
    void setUp() {
        deleteTestUser();
        jdbcClient.sql("""
                        INSERT INTO yumpoo.identity_user (
                            id, company_id, employment_status, account_status,
                            display_name, directory_synced_at,
                            authorization_version, row_version, created_at, updated_at
                        ) VALUES (
                            :userId, :companyId, 'ACTIVE', 'ENABLED',
                            'Web Security Test User', transaction_timestamp(),
                            0, 0, transaction_timestamp(), transaction_timestamp()
                        )
                        """)
                .param("userId", USER_ID)
                .param("companyId", COMPANY_ID)
                .update();
        issued = sessionService.issueWebSession(USER_ID, "web-security-it");
    }

    @AfterEach
    void tearDown() {
        deleteTestUser();
    }

    private void deleteTestUser() {
        jdbcClient.sql("DELETE FROM yumpoo.idempotency_record WHERE actor_user_id = :userId")
                .param("userId", USER_ID)
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.login_session WHERE user_id = :userId")
                .param("userId", USER_ID)
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.outbox_event WHERE aggregate_id = :userId")
                .param("userId", USER_ID)
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.identity_user WHERE id = :userId")
                .param("userId", USER_ID)
                .update();
    }

    @Test
    void anonymousAndMalformedWritesAreRejectedBeforeCsrf() throws Exception {
        HttpResponse<String> anonymous = send("POST", null, null, null);
        HttpResponse<String> malformed = send("POST", "malformed", null, null);
        HttpResponse<String> duplicate = send(
                "POST",
                issued.sessionCredential().value() + "; "
                        + SessionHttpCookies.SESSION_COOKIE + "=another",
                null,
                null
        );

        assertThat(anonymous.statusCode()).as(anonymous.body()).isEqualTo(401);
        assertThat(anonymous.body()).contains("AUTHENTICATION_REQUIRED");
        assertThat(malformed.statusCode()).isEqualTo(401);
        assertThat(duplicate.statusCode()).isEqualTo(401);
    }

    @Test
    void safeRequestRepairsMissingCsrfCookieWithSecurityAttributes() throws Exception {
        HttpResponse<String> response = send(
                "GET",
                issued.sessionCredential().value(),
                null,
                null
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(USER_ID.toString());
        assertThat(response.headers().allValues("set-cookie"))
                .anySatisfy(cookie -> assertThat(cookie)
                        .startsWith(SessionHttpCookies.CSRF_COOKIE + "=")
                        .contains("Path=/", "Secure", "SameSite=Lax")
                        .doesNotContain("HttpOnly", "Domain="));
    }

    @Test
    void unsafeRequestRequiresSessionBoundCookieAndHeader() throws Exception {
        String session = issued.sessionCredential().value();
        String csrf = issued.csrfCredential().value();

        HttpResponse<String> valid = send("POST", session, csrf, csrf);
        HttpResponse<String> missing = send("POST", session, null, null);
        HttpResponse<String> headerOnly = send("POST", session, null, csrf);
        HttpResponse<String> cookieOnly = send("POST", session, csrf, null);
        HttpResponse<String> mismatch = send("POST", session, csrf, "wrong-csrf-token");

        assertThat(valid.statusCode()).as(valid.body()).isEqualTo(204);
        assertThat(missing.statusCode()).isEqualTo(403);
        assertThat(headerOnly.statusCode()).isEqualTo(403);
        assertThat(cookieOnly.statusCode()).isEqualTo(403);
        assertThat(mismatch.statusCode()).isEqualTo(403);
        assertThat(missing.headers().allValues("set-cookie"))
                .anySatisfy(cookie -> assertThat(cookie)
                        .startsWith(SessionHttpCookies.CSRF_COOKIE + "=")
                        .contains("Max-Age=0"));
    }

    @Test
    void authorizationChangeInvalidatesAnAuthenticatedCredential() throws Exception {
        sessionService.incrementAuthorizationVersion(
                USER_ID,
                com.yumpoo.platform.identityaccess.domain.session.SessionRevocationReason
                        .AUTHORIZATION_CHANGED
        );

        HttpResponse<String> response = send(
                "GET",
                issued.sessionCredential().value(),
                issued.csrfCredential().value(),
                null
        );

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("AUTHENTICATION_REQUIRED");
    }

    @Test
    void currentActorBlocksRevocationThatCommitsAfterAuthentication() throws Exception {
        HttpRequest request = request("GET", issued.sessionCredential().value(), null, null)
                .uri(URI.create("http://127.0.0.1:" + port + API + "/race"))
                .build();
        var pending = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        assertThat(revocationGate.controllerEntered.await(10, TimeUnit.SECONDS)).isTrue();

        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root("m107-current-actor-race")
        )) {
            accountStatusUseCase.change(new AccountStatusChangeCommand(
                    COMPANY_ID,
                    USER_ID,
                    USER_ID,
                    AccountStatus.DISABLED,
                    0,
                    UUID.randomUUID(),
                    new RequestHash("7".repeat(64)),
                    "current-actor-race"
            ));
        }
        revocationGate.continueController.countDown();
        HttpResponse<String> response = pending.get(10, TimeUnit.SECONDS);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("ACCOUNT_DISABLED");
        assertThat(revocationGate.businessCodeReached).isFalse();
    }

    private HttpResponse<String> send(
            String method,
            String sessionValue,
            String csrfCookieValue,
            String csrfHeaderValue
    ) throws Exception {
        return httpClient.send(
                request(method, sessionValue, csrfCookieValue, csrfHeaderValue).build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpRequest.Builder request(
            String method,
            String sessionValue,
            String csrfCookieValue,
            String csrfHeaderValue
    ) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + API))
                .method(method, HttpRequest.BodyPublishers.noBody());
        String cookie = null;
        if (sessionValue != null) {
            cookie = SessionHttpCookies.SESSION_COOKIE + "=" + sessionValue;
        }
        if (csrfCookieValue != null) {
            String csrfCookie = SessionHttpCookies.CSRF_COOKIE + "=" + csrfCookieValue;
            cookie = cookie == null ? csrfCookie : cookie + "; " + csrfCookie;
        }
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        if (csrfHeaderValue != null) {
            request.header(SessionBoundCsrfTokenRepository.HEADER_NAME, csrfHeaderValue);
        }
        return request;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        ProbeController m103ProbeController(
                CurrentActorProvider currentActorProvider,
                RevocationGate revocationGate
        ) {
            return new ProbeController(currentActorProvider, revocationGate);
        }

        @Bean
        RevocationGate revocationGate() {
            return new RevocationGate();
        }
    }

    @RestController
    @RequestMapping(API)
    static class ProbeController {

        private final CurrentActorProvider currentActorProvider;
        private final RevocationGate revocationGate;

        ProbeController(CurrentActorProvider currentActorProvider, RevocationGate revocationGate) {
            this.currentActorProvider = currentActorProvider;
            this.revocationGate = revocationGate;
        }

        @GetMapping
        CurrentActor get() {
            return currentActorProvider.requiredActive();
        }

        @PostMapping
        ResponseEntity<Void> post() {
            currentActorProvider.requiredActive();
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/race")
        CurrentActor race() throws InterruptedException {
            revocationGate.controllerEntered.countDown();
            if (!revocationGate.continueController.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("revocation race timed out");
            }
            CurrentActor actor = currentActorProvider.requiredActive();
            revocationGate.businessCodeReached = true;
            return actor;
        }
    }

    static final class RevocationGate {

        private final CountDownLatch controllerEntered = new CountDownLatch(1);
        private final CountDownLatch continueController = new CountDownLatch(1);
        private volatile boolean businessCodeReached;
    }
}
