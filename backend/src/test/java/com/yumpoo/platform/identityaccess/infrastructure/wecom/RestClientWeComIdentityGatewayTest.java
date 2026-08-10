package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import com.yumpoo.platform.identityaccess.application.oauth.WeComAuthenticationFailedException;
import com.yumpoo.platform.identityaccess.application.oauth.WeComDependencyUnavailableException;
import com.yumpoo.platform.identityaccess.application.oauth.WeComMemberIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestClientWeComIdentityGatewayTest {

    private static final String CORP_ID = "ww-test-corp";
    private static final String AGENT_ID = "100001";
    private static final String APP_SECRET = "super-sensitive-app-secret";
    private static final String API_BASE_URL = "https://qyapi.weixin.qq.com";
    private static final Instant INITIAL_TIME = Instant.parse("2026-08-10T01:00:00Z");

    @Test
    void buildsOfficialSnsapiBaseAuthorizationUriWithFixedCallback() {
        GatewayFixture fixture = fixture(new MutableClock(INITIAL_TIME));

        URI authorizationUri = fixture.gateway().buildAuthorizationUri("state-123");

        assertThat(authorizationUri.getScheme()).isEqualTo("https");
        assertThat(authorizationUri.getHost()).isEqualTo("open.weixin.qq.com");
        assertThat(authorizationUri.getPath()).isEqualTo("/connect/oauth2/authorize");
        assertThat(authorizationUri.getFragment()).isEqualTo("wechat_redirect");
        assertThat(authorizationUri.getRawQuery())
                .contains("appid=" + CORP_ID)
                .contains("redirect_uri=https%3A%2F%2Flogin.example.test%2F_m0%2Fm0-12%2Fwecom%2Fcallback")
                .contains("response_type=code")
                .contains("scope=snsapi_base")
                .contains("state=state-123")
                .contains("agentid=" + AGENT_ID);
    }

    @Test
    void exchangesCodeForInternalMemberIdentity() {
        GatewayFixture fixture = fixture(new MutableClock(INITIAL_TIME));
        expectToken(fixture.server(), "token-a", 7200);
        expectMember(fixture.server(), "token-a", "code-a", "member-a");

        WeComMemberIdentity identity = fixture.gateway().exchangeCode("code-a");

        assertThat(identity.corpId()).isEqualTo(CORP_ID);
        assertThat(identity.memberId()).isEqualTo("member-a");
        assertThat(identity.toString()).doesNotContain(CORP_ID, "member-a");
        fixture.server().verify();
    }

    @Test
    void cachesTokenUntilSixtySecondsBeforeExpiryThenRefreshes() {
        MutableClock clock = new MutableClock(INITIAL_TIME);
        GatewayFixture fixture = fixture(clock);
        expectToken(fixture.server(), "token-a", 120);
        expectMember(fixture.server(), "token-a", "code-a", "member-a");
        expectMember(fixture.server(), "token-a", "code-b", "member-a");
        expectToken(fixture.server(), "token-b", 120);
        expectMember(fixture.server(), "token-b", "code-c", "member-a");

        fixture.gateway().exchangeCode("code-a");
        clock.advance(Duration.ofSeconds(59));
        fixture.gateway().exchangeCode("code-b");
        clock.advance(Duration.ofSeconds(2));
        fixture.gateway().exchangeCode("code-c");

        fixture.server().verify();
    }

    @Test
    void rejectsExternalOpenIdOnlyIdentityAsAuthenticationFailure() {
        GatewayFixture fixture = fixture(new MutableClock(INITIAL_TIME));
        expectToken(fixture.server(), "token-a", 7200);
        fixture.server().expect(requestTo(userInfoUri("token-a", "external-code")))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"errmsg\":\"ok\",\"openid\":\"external-open-id\"}",
                        MediaType.APPLICATION_JSON
                ));

        assertThatThrownBy(() -> fixture.gateway().exchangeCode("external-code"))
                .isInstanceOf(WeComAuthenticationFailedException.class)
                .hasMessage("WeCom authorization could not be verified");
        fixture.server().verify();
    }

    @Test
    void classifiesInvalidOrReusedCodeAsAuthenticationFailure() {
        GatewayFixture fixture = fixture(new MutableClock(INITIAL_TIME));
        expectToken(fixture.server(), "token-a", 7200);
        fixture.server().expect(requestTo(userInfoUri("token-a", "used-code")))
                .andRespond(withSuccess(
                        "{\"errcode\":40029,\"errmsg\":\"invalid code\"}",
                        MediaType.APPLICATION_JSON
                ));

        assertThatThrownBy(() -> fixture.gateway().exchangeCode("used-code"))
                .isInstanceOf(WeComAuthenticationFailedException.class);
        fixture.server().verify();
    }

    @Test
    void classifiesExpiredAndAlreadyUsedCodesAsAuthenticationFailures() {
        GatewayFixture fixture = fixture(new MutableClock(INITIAL_TIME));
        expectToken(fixture.server(), "token-a", 7200);
        expectMemberError(fixture.server(), "token-a", "expired-code", 42003);
        expectMemberError(fixture.server(), "token-a", "used-code", 42022);

        assertThatThrownBy(() -> fixture.gateway().exchangeCode("expired-code"))
                .isInstanceOf(WeComAuthenticationFailedException.class);
        assertThatThrownBy(() -> fixture.gateway().exchangeCode("used-code"))
                .isInstanceOf(WeComAuthenticationFailedException.class);
        fixture.server().verify();
    }

    @Test
    void refreshesAnInvalidCachedAccessTokenOnceBeforeExchangingTheCode() {
        GatewayFixture fixture = fixture(new MutableClock(INITIAL_TIME));
        expectToken(fixture.server(), "token-a", 7200);
        expectMemberError(fixture.server(), "token-a", "code-a", 40014);
        expectToken(fixture.server(), "token-b", 7200);
        expectMember(fixture.server(), "token-b", "code-a", "member-a");

        WeComMemberIdentity identity = fixture.gateway().exchangeCode("code-a");

        assertThat(identity.memberId()).isEqualTo("member-a");
        fixture.server().verify();
    }

    @Test
    void clearsTheReplacementTokenWhenTheSingleRefreshIsAlsoRejected() {
        GatewayFixture fixture = fixture(new MutableClock(INITIAL_TIME));
        expectToken(fixture.server(), "token-a", 7200);
        expectMemberError(fixture.server(), "token-a", "code-a", 42001);
        expectToken(fixture.server(), "token-b", 7200);
        expectMemberError(fixture.server(), "token-b", "code-a", 40014);
        expectToken(fixture.server(), "token-c", 7200);
        expectMember(fixture.server(), "token-c", "code-b", "member-a");

        assertThatThrownBy(() -> fixture.gateway().exchangeCode("code-a"))
                .isInstanceOf(WeComDependencyUnavailableException.class);
        assertThat(fixture.gateway().exchangeCode("code-b").memberId()).isEqualTo("member-a");
        fixture.server().verify();
    }

    @Test
    void classifiesUnknownProviderErrorAsDependencyFailure() {
        GatewayFixture fixture = fixture(new MutableClock(INITIAL_TIME));
        expectToken(fixture.server(), "token-a", 7200);
        fixture.server().expect(requestTo(userInfoUri("token-a", "provider-code")))
                .andRespond(withSuccess(
                        "{\"errcode\":999999,\"errmsg\":\"unexpected provider failure\"}",
                        MediaType.APPLICATION_JSON
                ));

        assertThatThrownBy(() -> fixture.gateway().exchangeCode("provider-code"))
                .isInstanceOf(WeComDependencyUnavailableException.class)
                .hasMessage("WeCom identity service is unavailable");
        fixture.server().verify();
    }

    @Test
    void classifiesTokenConfigurationErrorAsDependencyFailure() {
        GatewayFixture fixture = fixture(new MutableClock(INITIAL_TIME));
        fixture.server().expect(requestTo(tokenUri()))
                .andRespond(withSuccess(
                        "{\"errcode\":40013,\"errmsg\":\"invalid corpid\"}",
                        MediaType.APPLICATION_JSON
                ));

        assertThatThrownBy(() -> fixture.gateway().exchangeCode("code-a"))
                .isInstanceOf(WeComDependencyUnavailableException.class);
        fixture.server().verify();
    }

    @Test
    void classifiesTimeoutAsDependencyFailure() {
        GatewayFixture fixture = fixture(new MutableClock(INITIAL_TIME));
        fixture.server().expect(requestTo(tokenUri()))
                .andRespond(request -> {
                    throw new SocketTimeoutException("simulated timeout");
                });

        assertThatThrownBy(() -> fixture.gateway().exchangeCode("code-a"))
                .isInstanceOf(WeComDependencyUnavailableException.class);
        fixture.server().verify();
    }

    @Test
    void stripsSecretsAndProviderBodyFromTransportFailureException() {
        String authorizationCode = "sensitive-authorization-code";
        String responseSecret = "sensitive-provider-response";
        GatewayFixture fixture = fixture(new MutableClock(INITIAL_TIME));
        fixture.server().expect(requestTo(tokenUri()))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errmsg\":\"" + responseSecret + "\"}"));

        WeComDependencyUnavailableException exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> fixture.gateway().exchangeCode(authorizationCode),
                WeComDependencyUnavailableException.class
        );

        StringWriter stackTrace = new StringWriter();
        exception.printStackTrace(new PrintWriter(stackTrace));
        assertThat(stackTrace.toString())
                .doesNotContain(APP_SECRET, CORP_ID, authorizationCode, responseSecret);
        assertThat(exception).hasNoCause();
        fixture.server().verify();
    }

    @Test
    void validatesEnabledConfigurationButLeavesDisabledDefaultsUsable() {
        M012WeComProperties disabled = new M012WeComProperties();
        assertThatCode(disabled::validateForEnabled).doesNotThrowAnyException();

        M012WeComProperties valid = validProperties();
        assertThatCode(valid::validateForEnabled).doesNotThrowAnyException();

        valid.setAgentId("agent-1");
        assertThatThrownBy(valid::validateForEnabled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("M0-12 WeCom live verification configuration is invalid");

        valid = validProperties();
        valid.setCallbackUri(URI.create("https://login.example.test/different/callback"));
        assertThatThrownBy(valid::validateForEnabled).isInstanceOf(IllegalStateException.class);

        valid = validProperties();
        valid.setCallbackUri(URI.create(
                "https://login.example.test/_m0/m0-12/wecom/callback?returnUrl=https://evil.example"
        ));
        assertThatThrownBy(valid::validateForEnabled).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void trimsIdentifiersAndRejectsBlankAllowlistEntry() {
        M012WeComProperties properties = validProperties();
        properties.setCorpId("  " + CORP_ID + "  ");
        properties.setAgentId("  " + AGENT_ID + "  ");
        properties.setAppSecret("  " + APP_SECRET + "  ");
        properties.setAllowedMemberIds(Set.of("  member-a  "));

        properties.validateForEnabled();

        assertThat(properties.getCorpId()).isEqualTo(CORP_ID);
        assertThat(properties.getAgentId()).isEqualTo(AGENT_ID);
        assertThat(properties.getAppSecret()).isEqualTo(APP_SECRET);
        assertThat(properties.getAllowedMemberIds()).containsExactly("member-a");

        properties.setAllowedMemberIds(Set.of("   "));
        assertThatThrownBy(properties::validateForEnabled).isInstanceOf(IllegalStateException.class);
    }

    private static GatewayFixture fixture(MutableClock clock) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientWeComIdentityGateway gateway = new RestClientWeComIdentityGateway(
                builder,
                validProperties(),
                clock
        );
        return new GatewayFixture(gateway, server);
    }

    private static M012WeComProperties validProperties() {
        M012WeComProperties properties = new M012WeComProperties();
        properties.setEnabled(true);
        properties.setCorpId(CORP_ID);
        properties.setAgentId(AGENT_ID);
        properties.setAppSecret(APP_SECRET);
        properties.setCallbackUri(URI.create("https://login.example.test/_m0/m0-12/wecom/callback"));
        properties.setAllowedMemberIds(Set.of("member-a"));
        return properties;
    }

    private static void expectToken(MockRestServiceServer server, String token, long expiresIn) {
        server.expect(requestTo(tokenUri()))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"errmsg\":\"ok\",\"access_token\":\""
                                + token + "\",\"expires_in\":" + expiresIn + "}",
                        MediaType.APPLICATION_JSON
                ));
    }

    private static void expectMember(
            MockRestServiceServer server,
            String token,
            String code,
            String memberId
    ) {
        server.expect(requestTo(userInfoUri(token, code)))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"errmsg\":\"ok\",\"userid\":\"" + memberId + "\"}",
                        MediaType.APPLICATION_JSON
                ));
    }

    private static void expectMemberError(
            MockRestServiceServer server,
            String token,
            String code,
            long errorCode
    ) {
        server.expect(requestTo(userInfoUri(token, code)))
                .andRespond(withSuccess(
                        "{\"errcode\":" + errorCode + ",\"errmsg\":\"provider error\"}",
                        MediaType.APPLICATION_JSON
                ));
    }

    private static String tokenUri() {
        return API_BASE_URL + "/cgi-bin/gettoken?corpid=" + CORP_ID + "&corpsecret=" + APP_SECRET;
    }

    private static String userInfoUri(String token, String code) {
        return API_BASE_URL + "/cgi-bin/auth/getuserinfo?access_token=" + token + "&code=" + code;
    }

    private record GatewayFixture(RestClientWeComIdentityGateway gateway, MockRestServiceServer server) {
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
