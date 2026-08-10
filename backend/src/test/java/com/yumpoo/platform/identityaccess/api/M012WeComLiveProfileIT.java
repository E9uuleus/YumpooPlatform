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

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("m0-12-live")
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "yumpoo.m012.wecom.enabled=true",
                "yumpoo.m012.wecom.corp-id=ww-live-profile-test",
                "yumpoo.m012.wecom.agent-id=100001",
                "yumpoo.m012.wecom.app-secret=live-profile-test-app-secret",
                "yumpoo.m012.wecom.callback-uri=https://login.example.test/_m0/m0-12/wecom/callback",
                "yumpoo.m012.wecom.allowed-member-ids=member-a",
                "yumpoo.m012.evidence-hmac-key=live-profile-test-evidence-key-at-least-32-bytes"
        }
)
class M012WeComLiveProfileIT {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @LocalServerPort
    private int port;

    @Test
    void explicitProfileAndEnableFlagRegisterTheSecureAuthorizationProbe() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + M012WeComOAuthProbeController.AUTHORIZE_PATH))
                .header("X-Request-Id", "m012-live-profile-it")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("location"))
                .hasValueSatisfying(location -> assertThat(location)
                        .startsWith("https://open.weixin.qq.com/connect/oauth2/authorize?")
                        .contains("redirect_uri=https%3A%2F%2Flogin.example.test%2F_m0%2Fm0-12%2Fwecom%2Fcallback")
                        .contains("scope=snsapi_base")
                        .contains("agentid=100001")
                        .endsWith("#wechat_redirect"));
        assertThat(response.headers().firstValue("set-cookie"))
                .hasValueSatisfying(cookie -> assertThat(cookie)
                        .startsWith(M012WeComOAuthProbeController.NONCE_COOKIE + "=")
                        .contains("Secure")
                        .contains("HttpOnly")
                        .contains("SameSite=Lax")
                        .doesNotContain("Domain="));
        assertThat(response.headers().firstValue("cache-control"))
                .hasValueSatisfying(value -> assertThat(value).contains("no-store"));
    }
}
