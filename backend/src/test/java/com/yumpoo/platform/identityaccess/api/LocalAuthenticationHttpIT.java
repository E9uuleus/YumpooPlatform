package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"test", "local"})
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "yumpoo.auth.local.enabled=true",
                "yumpoo.auth.local.member-id=local-http-admin",
                "yumpoo.auth.local.display-name=本地 HTTP 管理员",
                "yumpoo.auth.local.backup-member-id=local-http-backup",
                "yumpoo.auth.local.backup-display-name=本地 HTTP 备份管理员",
                "yumpoo.outbox.enabled=false"
        }
)
class LocalAuthenticationHttpIT {

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void anonymousMeCreatesSessionAndCsrfForTheGovernedLocalAccount() throws Exception {
        HttpResponse<String> me = get("/api/v1/auth/me", null);

        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        assertThat(me.body())
                .contains("本地 HTTP 管理员")
                .contains("\"roles\":[\"COMPANY_MEMBER\",\"COMPANY_ADMIN\",\"APP_MANAGER\"]");
        String session = cookie(me, SessionHttpCookies.SESSION_COOKIE);
        String csrf = cookie(me, SessionHttpCookies.CSRF_COOKIE);
        String securityCookies = SessionHttpCookies.SESSION_COOKIE + "=" + session
                + "; " + SessionHttpCookies.CSRF_COOKIE + "=" + csrf;

        HttpResponse<String> projects = get("/api/v1/projects", securityCookies);
        assertThat(projects.statusCode()).as(projects.body()).isEqualTo(200);

        HttpResponse<String> logout = client.send(
                HttpRequest.newBuilder(uri("/api/v1/auth/logout"))
                        .header("Cookie", securityCookies)
                        .header(SessionBoundCsrfTokenRepository.HEADER_NAME, csrf)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(logout.statusCode()).as(logout.body()).isEqualTo(204);
    }

    @Test
    void staleLocalCookieIsReplacedWithoutReturningAuthenticationRequired() throws Exception {
        HttpResponse<String> response = get(
                "/api/v1/auth/me",
                SessionHttpCookies.SESSION_COOKIE + "=" + "z".repeat(43)
        );

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("AUTHENTICATION_REQUIRED");
        assertThat(response.headers().allValues("set-cookie"))
                .anySatisfy(value -> assertThat(value)
                        .startsWith(SessionHttpCookies.SESSION_COOKIE + "=")
                        .contains("Secure", "HttpOnly", "SameSite=Lax"));
    }

    @Test
    void projectsPageConcurrentReferenceRequestsRepairOneMissingCsrfCookie() throws Exception {
        HttpResponse<String> me = get("/api/v1/auth/me", null);
        String session = cookie(me, SessionHttpCookies.SESSION_COOKIE);
        String sessionCookie = SessionHttpCookies.SESSION_COOKIE + "=" + session;
        List<String> paths = List.of(
                "/api/v1/projects?page=0&size=20",
                "/api/v1/workspaces",
                "/api/v1/project-templates",
                "/api/v1/admin/members?employmentStatus=ACTIVE&accountStatus=ENABLED&page=0&size=100"
        );
        CountDownLatch ready = new CountDownLatch(paths.size());
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(paths.size());
        List<Future<HttpResponse<String>>> pending = new ArrayList<>();
        try {
            for (String path : paths) {
                pending.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return get(path, sessionCookie);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Set<String> repairedTokens = new HashSet<>();
            for (Future<HttpResponse<String>> request : pending) {
                HttpResponse<String> response = request.get(10, TimeUnit.SECONDS);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                repairedTokens.add(cookie(response, SessionHttpCookies.CSRF_COOKIE));
            }
            assertThat(repairedTokens).hasSize(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private HttpResponse<String> get(String path, String cookies) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (cookies != null) {
            request.header("Cookie", cookies);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
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
}
