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

@ActiveProfiles({"m0-12-live", "m0-15-live"})
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "yumpoo.m012.wecom.enabled=true",
                "yumpoo.m012.wecom.corp-id=ww-dual-profile-test",
                "yumpoo.m012.wecom.agent-id=100012",
                "yumpoo.m012.wecom.app-secret=m012-dual-profile-app-credential",
                "yumpoo.m012.wecom.callback-uri=https://login.example.test/_m0/m0-12/wecom/callback",
                "yumpoo.m012.wecom.allowed-member-ids=member-a",
                "yumpoo.m012.evidence-hmac-key=M012-Live-Key-0123456789-abcdef!@#",
                "yumpoo.m015.wecom.enabled=true",
                "yumpoo.m015.wecom.corp-id=ww-dual-profile-test",
                "yumpoo.m015.wecom.agent-id=100015",
                "yumpoo.m015.wecom.app-secret=m015-dual-profile-app-credential",
                "yumpoo.m015.wecom.callback-uri=https://login.example.test/_m0/m0-15/wecom/callback",
                "yumpoo.m015.wecom.allowed-member-ids=member-a",
                "yumpoo.m015.evidence-hmac-key=M015-Live-Key-0123456789-abcdef!@#"
        }
)
class M015DualLiveProfileIT {

    private static final String DESKTOP_STATE = "D".repeat(43);
    private static final String PKCE_CHALLENGE = "C".repeat(43);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @LocalServerPort
    private int port;

    @Test
    void bothLiveProfilesStartWithoutGatewayBeanAmbiguityAndExposeTheirOwnRoutes() throws Exception {
        HttpResponse<String> m012 = get(M012WeComOAuthProbeController.AUTHORIZE_PATH);
        HttpResponse<String> m015 = get(
                M015DesktopAuthProbeController.AUTHORIZE_PATH
                        + "?state=" + DESKTOP_STATE
                        + "&codeChallenge=" + PKCE_CHALLENGE
                        + "&codeChallengeMethod=S256"
        );

        assertThat(m012.statusCode()).isEqualTo(302);
        assertThat(m015.statusCode()).isEqualTo(302);
        assertThat(m015.headers().firstValue("location"))
                .hasValueSatisfying(location -> assertThat(location)
                        .startsWith("https://open.weixin.qq.com/connect/oauth2/authorize?")
                        .contains("redirect_uri=https%3A%2F%2Flogin.example.test%2F_m0%2Fm0-15%2Fwecom%2Fcallback")
                        .contains("scope=snsapi_base")
                        .contains("agentid=100015")
                        .endsWith("#wechat_redirect"));
        assertThat(m015.headers().allValues("set-cookie"))
                .hasSize(2)
                .allSatisfy(cookie -> assertThat(cookie)
                        .contains("Secure", "HttpOnly", "SameSite=Lax")
                        .doesNotContain("Domain="));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Request-Id", "m015.dual-profile")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
