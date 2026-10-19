package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The desktop shell calls these endpoints with no session cookie, and the system browser may carry a stale
 * one. Without local authentication they must still reach the controllers instead of the session filter.
 */
@ActiveProfiles("test")
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "yumpoo.auth.controlled.enabled=true",
                "yumpoo.auth.controlled.corp-id=corp-desktop-it",
                "yumpoo.auth.controlled.member-id=member-desktop-it",
                "yumpoo.auth.local.enabled=false",
                "yumpoo.outbox.enabled=false"
        }
)
class DesktopAuthenticationIT {

    private static final UUID COMPANY_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000001"
    );
    private static final UUID USER_ID = UUID.fromString(
            "60000000-0000-4000-8000-000000000206"
    );
    private static final UUID IDENTITY_ID = UUID.fromString(
            "60000000-0000-4000-8000-000000000207"
    );
    private static final String CLIENT_VERSION = "0.0.0-desktop-it";
    private static final String STALE_SESSION = SessionHttpCookies.SESSION_COOKIE + "=" + "z".repeat(43);

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        deleteFixture();
        jdbcClient.sql("""
                        INSERT INTO yumpoo.identity_user (
                            id, company_id, employment_status, account_status,
                            display_name, workspace_slug, directory_synced_at,
                            authorization_version, row_version, created_at, updated_at
                        ) VALUES (
                            :userId, :companyId, 'ACTIVE', 'ENABLED',
                            'Desktop IT Member', 'member-desktop-it', transaction_timestamp(),
                            0, 0, transaction_timestamp(), transaction_timestamp()
                        )
                        """)
                .param("userId", USER_ID)
                .param("companyId", COMPANY_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO yumpoo.external_identity (
                            id, company_id, user_id, provider, external_user_id,
                            provider_employment_status, raw_profile_hash,
                            last_seen_at, created_at, updated_at
                        ) VALUES (
                            :identityId, :companyId, :userId, 'WECOM', 'member-desktop-it',
                            'ACTIVE', :profileHash,
                            transaction_timestamp(), transaction_timestamp(), transaction_timestamp()
                        )
                        """)
                .param("identityId", IDENTITY_ID)
                .param("companyId", COMPANY_ID)
                .param("userId", USER_ID)
                .param("profileHash", "b".repeat(64))
                .update();
    }

    @AfterEach
    void tearDown() {
        deleteFixture();
    }

    @Test
    void cookielessDesktopFlowIssuesAnElectronSession() throws Exception {
        String state = token();
        String verifier = token();

        HttpResponse<String> attempt = post(DesktopAuthenticationPaths.ATTEMPTS, null, Map.of(
                "state", state,
                "codeChallenge", challenge(verifier),
                "codeChallengeMethod", "S256"
        ));
        assertThat(attempt.statusCode()).as("body=%s", attempt.body()).isEqualTo(201);
        String authorizationUrl = json(attempt).get("authorizationUrl").asText();

        // The system browser may still hold an expired web session for this origin.
        HttpResponse<String> callback = get(authorizationUrl, STALE_SESSION);
        assertThat(callback.statusCode()).as("body=%s", callback.body()).isEqualTo(302);
        URI deepLink = URI.create(callback.headers().firstValue("location").orElseThrow());
        assertThat(deepLink.getScheme()).isEqualTo("yumpoo");
        Map<String, String> query = UriComponentsBuilder.fromUri(deepLink).build().getQueryParams().toSingleValueMap();
        assertThat(query.get("state")).isEqualTo(state);

        HttpResponse<String> exchange = post(DesktopAuthenticationPaths.EXCHANGE, null, Map.of(
                "handoffCode", query.get("code"),
                "state", state,
                "codeVerifier", verifier
        ));
        assertThat(exchange.statusCode()).as("body=%s", exchange.body()).isEqualTo(200);
        String session = json(exchange).get("sessionCredential").asText();

        HttpResponse<String> me = get(WebAuthenticationPaths.ME, SessionHttpCookies.SESSION_COOKIE + "=" + session);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains(USER_ID.toString(), "ELECTRON");
    }

    @Test
    void malformedDesktopRequestsReachTheControllersEvenWithAStaleCookie() throws Exception {
        HttpResponse<String> attempt = post(DesktopAuthenticationPaths.ATTEMPTS, STALE_SESSION, Map.of(
                "state", token(),
                "codeChallenge", challenge(token()),
                "codeChallengeMethod", "plain"
        ));
        HttpResponse<String> exchange = post(DesktopAuthenticationPaths.EXCHANGE, null, Map.of());
        HttpResponse<String> callback = get(DesktopAuthenticationPaths.CALLBACK, null);

        assertThat(attempt.statusCode()).as("body=%s", attempt.body()).isEqualTo(400);
        assertThat(json(attempt).get("code").asText()).isEqualTo("MALFORMED_REQUEST");
        assertThat(exchange.statusCode()).as("body=%s", exchange.body()).isEqualTo(400);
        assertThat(callback.statusCode()).isEqualTo(400);
        assertThat(callback.headers().firstValue("content-type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("text/html"));
        assertThat(attempt.headers().allValues("set-cookie")).isEmpty();
    }

    private HttpResponse<String> post(String path, String cookies, Map<String, String> body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Client-Type", "ELECTRON")
                .header("X-Client-Version", CLIENT_VERSION)
                .header("X-Client-Protocol-Version", "1")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        if (cookies != null) {
            request.header("Cookie", cookies);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String cookies) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (cookies != null) {
            request.header("Cookie", cookies);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return objectMapper.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private void deleteFixture() {
        jdbcClient.sql("DELETE FROM yumpoo.security_audit_event WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID)
                .update();
        jdbcClient.sql("""
                        DELETE FROM yumpoo.outbox_event
                        WHERE payload_json ->> 'userId' = :userId
                        """)
                .param("userId", USER_ID.toString())
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.desktop_auth_attempt WHERE client_version = :clientVersion")
                .param("clientVersion", CLIENT_VERSION)
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.login_session WHERE user_id = :userId")
                .param("userId", USER_ID)
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.external_identity WHERE user_id = :userId")
                .param("userId", USER_ID)
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.identity_user WHERE id = :userId")
                .param("userId", USER_ID)
                .update();
    }
}
