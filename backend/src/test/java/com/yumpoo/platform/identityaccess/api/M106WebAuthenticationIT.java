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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "yumpoo.auth.controlled.enabled=true",
                "yumpoo.auth.controlled.corp-id=corp-m106",
                "yumpoo.auth.controlled.member-id=member-m106",
                "yumpoo.outbox.enabled=false"
        }
)
class M106WebAuthenticationIT {

    private static final UUID COMPANY_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000001"
    );
    private static final UUID USER_ID = UUID.fromString(
            "60000000-0000-4000-8000-000000000106"
    );
    private static final UUID IDENTITY_ID = UUID.fromString(
            "60000000-0000-4000-8000-000000000107"
    );

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

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
                            display_name, directory_synced_at,
                            authorization_version, row_version, created_at, updated_at
                        ) VALUES (
                            :userId, :companyId, 'ACTIVE', 'ENABLED',
                            'M1-06 Web Member', transaction_timestamp(),
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
                            :identityId, :companyId, :userId, 'WECOM', 'member-m106',
                            'ACTIVE', :profileHash,
                            transaction_timestamp(), transaction_timestamp(), transaction_timestamp()
                        )
                        """)
                .param("identityId", IDENTITY_ID)
                .param("companyId", COMPANY_ID)
                .param("userId", USER_ID)
                .param("profileHash", "a".repeat(64))
                .update();
        insertRole(
                UUID.fromString("60000000-0000-4000-8000-000000000108"),
                "COMPANY_ADMIN",
                "COMPANY"
        );
        insertRole(
                UUID.fromString("60000000-0000-4000-8000-000000000109"),
                "APP_MANAGER",
                "PLATFORM"
        );
    }

    @AfterEach
    void tearDown() {
        deleteFixture();
    }

    @Test
    void controlledProviderCompletesLoginMeAndIdempotentLogout() throws Exception {
        HttpResponse<String> authorize = get("/api/v1/auth/wecom/authorize", null);
        assertThat(authorize.statusCode())
                .as("body=%s headers=%s", authorize.body(), authorize.headers().map())
                .isEqualTo(302);
        String nonce = cookie(authorize, WebAuthenticationController.OAUTH_NONCE_COOKIE);
        String callback = authorize.headers().firstValue("location").orElseThrow();

        HttpResponse<String> completed = get(
                callback,
                WebAuthenticationController.OAUTH_NONCE_COOKIE + "=" + nonce
        );
        assertThat(completed.statusCode()).isEqualTo(302);
        assertThat(completed.headers().firstValue("location")).contains("/");
        String session = cookie(completed, SessionHttpCookies.SESSION_COOKIE);
        String csrf = cookie(completed, SessionHttpCookies.CSRF_COOKIE);
        assertThat(completed.headers().allValues("set-cookie"))
                .allSatisfy(value -> assertThat(value).contains("Secure", "SameSite=Lax"));
        assertThat(jdbcClient.sql("""
                        SELECT count(*) FROM yumpoo.login_session
                        WHERE user_id = :userId AND status = 'ACTIVE'
                        """)
                .param("userId", USER_ID)
                .query(Integer.class)
                .single()).isOne();

        String securityCookies = SessionHttpCookies.SESSION_COOKIE + "=" + session
                + "; " + SessionHttpCookies.CSRF_COOKIE + "=" + csrf;
        HttpResponse<String> me = get("/api/v1/auth/me", securityCookies);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body())
                .contains(USER_ID.toString(), "M1-06 Web Member", "Yumpoo")
                .contains("Asia/Shanghai", "MONDAY")
                .contains("\"roles\":[\"COMPANY_MEMBER\",\"COMPANY_ADMIN\",\"APP_MANAGER\"]")
                .contains("WEB", "SUPPORTED");

        HttpResponse<String> firstLogout = logout(securityCookies, csrf);
        HttpResponse<String> repeatedLogout = logout(securityCookies, csrf);
        assertThat(firstLogout.statusCode()).isEqualTo(204);
        assertThat(repeatedLogout.statusCode()).isEqualTo(204);
        assertThat(firstLogout.headers().allValues("set-cookie"))
                .anySatisfy(value -> assertThat(value)
                        .startsWith(SessionHttpCookies.SESSION_COOKIE + "="))
                .anySatisfy(value -> assertThat(value)
                        .startsWith(SessionHttpCookies.CSRF_COOKIE + "="))
                .allSatisfy(value -> assertThat(value).contains("Max-Age=0"));
        assertThat(get("/api/v1/auth/me", securityCookies).statusCode()).isEqualTo(401);
        String unknownCookies = SessionHttpCookies.SESSION_COOKIE + "=" + "z".repeat(43)
                + "; " + SessionHttpCookies.CSRF_COOKIE + "=" + csrf;
        assertThat(logout(unknownCookies, csrf).statusCode()).isEqualTo(401);
        assertThat(eventCount("identity.login_succeeded")).isOne();
        assertThat(eventCount("identity.user_sessions_revoked")).isOne();
    }

    @Test
    void invalidNonceAndReplayNeverCreateAnotherSession() throws Exception {
        HttpResponse<String> authorize = get("/api/v1/auth/wecom/authorize", null);
        String nonce = cookie(authorize, WebAuthenticationController.OAUTH_NONCE_COOKIE);
        String callback = authorize.headers().firstValue("location").orElseThrow();

        HttpResponse<String> invalid = get(
                callback,
                WebAuthenticationController.OAUTH_NONCE_COOKIE + "=" + "x".repeat(43)
        );
        assertThat(invalid.statusCode()).isEqualTo(401);

        HttpResponse<String> success = get(
                callback,
                WebAuthenticationController.OAUTH_NONCE_COOKIE + "=" + nonce
        );
        HttpResponse<String> replay = get(
                callback,
                WebAuthenticationController.OAUTH_NONCE_COOKIE + "=" + nonce
        );
        assertThat(success.statusCode()).isEqualTo(302);
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(jdbcClient.sql("""
                        SELECT count(*) FROM yumpoo.login_session WHERE user_id = :userId
                        """)
                .param("userId", USER_ID)
                .query(Integer.class)
                .single()).isOne();
    }

    @Test
    void missingSynchronizedIdentityIsRejectedWithoutCreatingAUserOrSession() throws Exception {
        HttpResponse<String> authorize = get("/api/v1/auth/wecom/authorize", null);
        String nonce = cookie(authorize, WebAuthenticationController.OAUTH_NONCE_COOKIE);
        String callback = authorize.headers().firstValue("location").orElseThrow();
        jdbcClient.sql("DELETE FROM yumpoo.external_identity WHERE id = :identityId")
                .param("identityId", IDENTITY_ID)
                .update();

        HttpResponse<String> response = get(
                callback,
                WebAuthenticationController.OAUTH_NONCE_COOKIE + "=" + nonce
        );

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("AUTHENTICATION_REQUIRED");
        assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.login_session WHERE user_id = :id")
                .param("id", USER_ID)
                .query(Integer.class)
                .single()).isZero();
        assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.identity_user WHERE id = :id")
                .param("id", USER_ID)
                .query(Integer.class)
                .single()).isOne();
    }

    @Test
    void leftProviderIdentityAndDisabledUserAreUniformlyRejected() throws Exception {
        HttpResponse<String> leftAuthorize = get("/api/v1/auth/wecom/authorize", null);
        String leftNonce = cookie(leftAuthorize, WebAuthenticationController.OAUTH_NONCE_COOKIE);
        jdbcClient.sql("""
                        UPDATE yumpoo.external_identity
                        SET provider_employment_status = 'LEFT',
                            updated_at = transaction_timestamp()
                        WHERE id = :identityId
                        """)
                .param("identityId", IDENTITY_ID)
                .update();
        assertThat(get(
                leftAuthorize.headers().firstValue("location").orElseThrow(),
                WebAuthenticationController.OAUTH_NONCE_COOKIE + "=" + leftNonce
        ).statusCode()).isEqualTo(401);

        jdbcClient.sql("""
                        UPDATE yumpoo.external_identity
                        SET provider_employment_status = 'ACTIVE',
                            updated_at = transaction_timestamp()
                        WHERE id = :identityId
                        """)
                .param("identityId", IDENTITY_ID)
                .update();
        HttpResponse<String> disabledAuthorize = get("/api/v1/auth/wecom/authorize", null);
        String disabledNonce = cookie(
                disabledAuthorize,
                WebAuthenticationController.OAUTH_NONCE_COOKIE
        );
        jdbcClient.sql("""
                        UPDATE yumpoo.identity_user
                        SET account_status = 'DISABLED',
                            account_disabled_at = transaction_timestamp(),
                            account_disabled_by_user_id = id,
                            account_disabled_reason = 'M1-06 TEST',
                            updated_at = transaction_timestamp()
                        WHERE id = :userId
                        """)
                .param("userId", USER_ID)
                .update();
        assertThat(get(
                disabledAuthorize.headers().firstValue("location").orElseThrow(),
                WebAuthenticationController.OAUTH_NONCE_COOKIE + "=" + disabledNonce
        ).statusCode()).isEqualTo(401);
        assertThat(jdbcClient.sql("SELECT count(*) FROM yumpoo.login_session WHERE user_id = :id")
                .param("id", USER_ID)
                .query(Integer.class)
                .single()).isZero();
    }

    private HttpResponse<String> get(String path, String cookies) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (cookies != null) {
            request.header("Cookie", cookies);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> logout(String cookies, String csrf) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri("/api/v1/auth/logout"))
                        .header("Cookie", cookies)
                        .header(SessionBoundCsrfTokenRepository.HEADER_NAME, csrf)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private URI uri(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return URI.create(path);
        }
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static String cookie(HttpResponse<String> response, String name) {
        String prefix = name + "=";
        return response.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith(prefix))
                .map(value -> value.substring(prefix.length(), value.indexOf(';')))
                .filter(value -> !value.isEmpty())
                .findFirst()
                .orElseThrow();
    }

    private int eventCount(String eventType) {
        return jdbcClient.sql("""
                        SELECT count(*)
                        FROM yumpoo.outbox_event
                        WHERE event_type = :eventType
                          AND payload_json ->> 'userId' = :userId
                        """)
                .param("eventType", eventType)
                .param("userId", USER_ID.toString())
                .query(Integer.class)
                .single();
    }

    private void insertRole(UUID id, String role, String scope) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.platform_role_assignment (
                            id, company_id, user_id, role_code, scope_type, scope_id, status,
                            granted_by_actor_type, granted_by_system_code, grant_reason,
                            granted_at, row_version, created_at, updated_at
                        ) VALUES (
                            :id, :companyId, :userId, :role, :scope, :companyId, 'ACTIVE',
                            'SYSTEM', 'M1_08_TEST', 'authentication fixture',
                            transaction_timestamp(), 0, transaction_timestamp(), transaction_timestamp()
                        )
                        """)
                .param("id", id)
                .param("companyId", COMPANY_ID)
                .param("userId", USER_ID)
                .param("role", role)
                .param("scope", scope)
                .update();
    }

    private void deleteFixture() {
        jdbcClient.sql("DELETE FROM yumpoo.security_audit_event WHERE company_id = :companyId")
                .param("companyId", COMPANY_ID)
                .update();
        jdbcClient.sql("""
                        DELETE FROM yumpoo.outbox_event
                        WHERE event_type = 'identity.login_rejected'
                          AND actor_system_code = 'WECOM_AUTH'
                        """)
                .update();
        jdbcClient.sql("""
                        DELETE FROM yumpoo.outbox_event
                        WHERE payload_json ->> 'userId' = :userId
                        """)
                .param("userId", USER_ID.toString())
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.login_session WHERE user_id = :userId")
                .param("userId", USER_ID)
                .update();
        jdbcClient.sql("DELETE FROM yumpoo.platform_role_assignment WHERE user_id = :userId")
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
