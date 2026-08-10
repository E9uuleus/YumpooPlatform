package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import com.yumpoo.platform.identityaccess.application.directory.DirectorySnapshotFailure;
import com.yumpoo.platform.identityaccess.application.directory.WeComDirectoryGatewayException;
import com.yumpoo.platform.identityaccess.application.directory.WeComDirectoryPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestClientWeComDirectoryGatewayTest {

    private static final String API_BASE_URL = "https://qyapi.weixin.qq.com";
    private static final String CORP_ID = "ww-directory-test";
    private static final String DIRECTORY_SECRET = "directory-sync-secret";
    private static final Instant INITIAL_TIME = Instant.parse("2026-08-10T01:00:00Z");

    @Test
    void postsTheOfficialPageShapeAndDeduplicatesMultiDepartmentMembers() {
        GatewayFixture fixture = fixture(DIRECTORY_SECRET);
        expectToken(fixture.server(), DIRECTORY_SECRET, "token-a");
        expectPage(
                fixture.server(),
                "token-a",
                null,
                1,
                """
                        {
                          "errcode": 0,
                          "errmsg": "ok",
                          "next_cursor": "cursor-1",
                          "dept_user": [
                            {"userid": "member-a", "department": 1},
                            {"userid": "member-a", "department": 2},
                            {"userid": "member-b", "department": 1}
                          ]
                        }
                        """
        );
        expectPage(
                fixture.server(),
                "token-a",
                "cursor-1",
                1,
                """
                        {
                          "errcode": 0,
                          "errmsg": "ok",
                          "next_cursor": "",
                          "dept_user": [{"userid": "member-c", "department": 3}]
                        }
                        """
        );

        WeComDirectoryPage first = fixture.gateway().fetchPage("", 1);
        WeComDirectoryPage second = fixture.gateway().fetchPage("cursor-1", 1);

        assertThat(first.memberIds()).containsExactly("member-a", "member-b");
        assertThat(first.nextCursor()).isEqualTo("cursor-1");
        assertThat(second.memberIds()).containsExactly("member-c");
        assertThat(second.hasExplicitEnd()).isTrue();
        assertThat(first.toString())
                .isEqualTo("WeComDirectoryPage[memberCount=2, cursorState=NEXT]")
                .doesNotContain("member-a", "member-b", "cursor-1");
        fixture.server().verify();
    }

    @Test
    void refreshesAnInvalidAccessTokenExactlyOnce() {
        GatewayFixture fixture = fixture(DIRECTORY_SECRET);
        expectToken(fixture.server(), DIRECTORY_SECRET, "token-a");
        expectPageError(fixture.server(), "token-a", null, 1, 40014);
        expectToken(fixture.server(), DIRECTORY_SECRET, "token-b");
        expectPage(
                fixture.server(),
                "token-b",
                null,
                1,
                successPage("member-a", "")
        );

        WeComDirectoryPage page = fixture.gateway().fetchPage("", 1);

        assertThat(page.memberIds()).containsExactly("member-a");
        fixture.server().verify();
    }

    @Test
    void rejectsAReplacementTokenAndClearsItForTheNextCall() {
        GatewayFixture fixture = fixture(DIRECTORY_SECRET);
        expectToken(fixture.server(), DIRECTORY_SECRET, "token-a");
        expectPageError(fixture.server(), "token-a", null, 1, 42001);
        expectToken(fixture.server(), DIRECTORY_SECRET, "token-b");
        expectPageError(fixture.server(), "token-b", null, 1, 40014);
        expectToken(fixture.server(), DIRECTORY_SECRET, "token-c");
        expectPage(fixture.server(), "token-c", null, 1, successPage("member-a", ""));

        assertThatThrownBy(() -> fixture.gateway().fetchPage("", 1))
                .isInstanceOfSatisfying(WeComDirectoryGatewayException.class, exception ->
                        assertThat(exception.failure())
                                .isEqualTo(DirectorySnapshotFailure.ACCESS_TOKEN_REJECTED)
                );
        assertThat(fixture.gateway().fetchPage("", 1).memberIds()).containsExactly("member-a");
        fixture.server().verify();
    }

    @ParameterizedTest
    @MethodSource("safeProviderFailures")
    void classifiesProviderErrorsWithoutRetainingErrmsg(
            long errorCode,
            DirectorySnapshotFailure expectedFailure
    ) {
        GatewayFixture fixture = fixture(DIRECTORY_SECRET);
        expectToken(fixture.server(), DIRECTORY_SECRET, "token-a");
        expectPageError(fixture.server(), "token-a", null, 1, errorCode);

        assertThatThrownBy(() -> fixture.gateway().fetchPage("", 1))
                .isInstanceOfSatisfying(WeComDirectoryGatewayException.class, exception -> {
                    assertThat(exception.failure()).isEqualTo(expectedFailure);
                    assertThat(exception)
                            .hasMessage("WeCom directory service is unavailable")
                            .hasNoCause();
                });
        fixture.server().verify();
    }

    @Test
    void rejectsMalformedSuccessfulResponses() {
        GatewayFixture fixture = fixture(DIRECTORY_SECRET);
        expectToken(fixture.server(), DIRECTORY_SECRET, "token-a");
        expectPage(
                fixture.server(),
                "token-a",
                null,
                1,
                "{" +
                        "\"errcode\":0," +
                        "\"errmsg\":\"ok\"," +
                        "\"next_cursor\":\"\"," +
                        "\"dept_user\":[{\"department\":1}]" +
                        "}"
        );

        assertThatThrownBy(() -> fixture.gateway().fetchPage("", 1))
                .isInstanceOfSatisfying(WeComDirectoryGatewayException.class, exception ->
                        assertThat(exception.failure())
                                .isEqualTo(DirectorySnapshotFailure.MALFORMED_MEMBER)
                );
        fixture.server().verify();
    }

    @Test
    void classifiesMissingMemberListWithoutRetainingTheResponse() {
        GatewayFixture fixture = fixture(DIRECTORY_SECRET);
        expectToken(fixture.server(), DIRECTORY_SECRET, "token-a");
        expectPage(
                fixture.server(),
                "token-a",
                null,
                1,
                "{\"errcode\":0,\"errmsg\":\"ok\",\"next_cursor\":\"\"}"
        );

        assertThatThrownBy(() -> fixture.gateway().fetchPage("", 1))
                .isInstanceOfSatisfying(WeComDirectoryGatewayException.class, exception -> {
                    assertThat(exception.failure())
                            .isEqualTo(DirectorySnapshotFailure.MALFORMED_MEMBER_LIST);
                    assertThat(exception).hasNoCause();
                });
        fixture.server().verify();
    }

    @Test
    void retainsMembersWhenTheProviderOmitsTheTerminalCursor() {
        GatewayFixture fixture = fixture(DIRECTORY_SECRET);
        expectToken(fixture.server(), DIRECTORY_SECRET, "token-a");
        expectPage(
                fixture.server(),
                "token-a",
                null,
                1,
                "{\"errcode\":0,\"errmsg\":\"ok\","
                        + "\"dept_user\":[{\"userid\":\"member-a\",\"department\":1}]}"
        );

        WeComDirectoryPage page = fixture.gateway().fetchPage("", 1);

        assertThat(page.memberIds()).containsExactly("member-a");
        assertThat(page.hasOmittedCursor()).isTrue();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.toString())
                .isEqualTo("WeComDirectoryPage[memberCount=1, cursorState=OMITTED]")
                .doesNotContain("member-a");
        fixture.server().verify();
    }

    @Test
    void treatsANullTerminalCursorAsOmittedWithoutRetainingTheResponse() {
        GatewayFixture fixture = fixture(DIRECTORY_SECRET);
        expectToken(fixture.server(), DIRECTORY_SECRET, "token-a");
        expectPage(
                fixture.server(),
                "token-a",
                null,
                1,
                "{\"errcode\":0,\"errmsg\":\"ok\",\"next_cursor\":null,"
                        + "\"dept_user\":[{\"userid\":\"member-a\",\"department\":1}]}"
        );

        WeComDirectoryPage page = fixture.gateway().fetchPage("", 1);

        assertThat(page.memberIds()).containsExactly("member-a");
        assertThat(page.hasOmittedCursor()).isTrue();
        fixture.server().verify();
    }

    @ParameterizedTest
    @MethodSource("invalidCursorCases")
    void rejectsInvalidNextCursor(
            String nextCursorJson,
            DirectorySnapshotFailure expectedFailure
    ) {
        GatewayFixture fixture = fixture(DIRECTORY_SECRET);
        expectToken(fixture.server(), DIRECTORY_SECRET, "token-a");
        expectPage(
                fixture.server(),
                "token-a",
                null,
                1,
                "{\"errcode\":0,\"errmsg\":\"ok\",\"next_cursor\":"
                        + nextCursorJson
                        + ",\"dept_user\":[{\"userid\":\"member-a\",\"department\":1}]}"
        );

        assertThatThrownBy(() -> fixture.gateway().fetchPage("", 1))
                .isInstanceOfSatisfying(WeComDirectoryGatewayException.class, exception -> {
                    assertThat(exception.failure())
                            .isEqualTo(expectedFailure);
                    assertThat(exception).hasNoCause();
                });
        fixture.server().verify();
    }

    private static Stream<Arguments> invalidCursorCases() {
        return Stream.of(
                Arguments.of("123", DirectorySnapshotFailure.INVALID_CURSOR_TYPE),
                Arguments.of("\"   \"", DirectorySnapshotFailure.INVALID_CURSOR_VALUE)
        );
    }

    @Test
    void rejectsSyntacticallyMalformedJsonWithoutRetainingTheResponseBody() {
        String sensitiveResponseBody = "sensitive-malformed-response-body";
        GatewayFixture fixture = fixture(DIRECTORY_SECRET);
        expectToken(fixture.server(), DIRECTORY_SECRET, "token-a");
        expectPage(
                fixture.server(),
                "token-a",
                null,
                1,
                "{\"errcode\":0,\"next_cursor\":\"\",\"dept_user\":[{\"userid\":\""
                        + sensitiveResponseBody
        );

        WeComDirectoryGatewayException exception = org.assertj.core.api.Assertions
                .catchThrowableOfType(
                        () -> fixture.gateway().fetchPage("", 1),
                        WeComDirectoryGatewayException.class
                );
        StringWriter stackTrace = new StringWriter();
        exception.printStackTrace(new PrintWriter(stackTrace));

        assertThat(exception.failure()).isEqualTo(DirectorySnapshotFailure.MALFORMED_RESPONSE);
        assertThat(exception).hasNoCause();
        assertThat(stackTrace.toString()).doesNotContain(sensitiveResponseBody);
        fixture.server().verify();
    }

    @Test
    void stripsSecretTokenCursorMemberAndProviderBodyFromTransportFailures() {
        String sensitiveCursor = "sensitive-cursor";
        String sensitiveMember = "sensitive-member";
        String sensitiveProviderBody = "sensitive-provider-body";
        GatewayFixture fixture = fixture(DIRECTORY_SECRET);
        expectToken(fixture.server(), DIRECTORY_SECRET, "sensitive-token");
        fixture.server().expect(requestTo(pageUri("sensitive-token")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errmsg\":\"" + sensitiveProviderBody
                                + "\",\"userid\":\"" + sensitiveMember + "\"}"));

        WeComDirectoryGatewayException exception = org.assertj.core.api.Assertions
                .catchThrowableOfType(
                        () -> fixture.gateway().fetchPage(sensitiveCursor, 1),
                        WeComDirectoryGatewayException.class
                );
        StringWriter stackTrace = new StringWriter();
        exception.printStackTrace(new PrintWriter(stackTrace));

        assertThat(stackTrace.toString()).doesNotContain(
                DIRECTORY_SECRET,
                "sensitive-token",
                sensitiveCursor,
                sensitiveMember,
                sensitiveProviderBody
        );
        assertThat(exception.failure()).isEqualTo(DirectorySnapshotFailure.TRANSPORT_ERROR);
        assertThat(exception).hasNoCause();
        fixture.server().verify();
    }

    @Test
    void keepsTokenCachesIndependentAcrossDifferentSecrets() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientWeComDirectoryGateway first = gateway(builder, "secret-a");
        RestClientWeComDirectoryGateway second = gateway(builder, "secret-b");
        expectToken(server, "secret-a", "token-a");
        expectPage(server, "token-a", null, 1, successPage("member-a", ""));
        expectToken(server, "secret-b", "token-b");
        expectPage(server, "token-b", null, 1, successPage("member-b", ""));

        assertThat(first.fetchPage("", 1).memberIds()).containsExactly("member-a");
        assertThat(second.fetchPage("", 1).memberIds()).containsExactly("member-b");
        server.verify();
    }

    @Test
    void refreshesTheTokenAtTheExactSixtySecondSafetyBoundary() {
        MutableClock clock = new MutableClock(INITIAL_TIME);
        GatewayFixture fixture = fixture(DIRECTORY_SECRET, clock);
        expectToken(fixture.server(), DIRECTORY_SECRET, "token-a", 120);
        expectPage(fixture.server(), "token-a", null, 1, successPage("member-a", ""));
        expectPage(fixture.server(), "token-a", null, 1, successPage("member-a", ""));
        expectToken(fixture.server(), DIRECTORY_SECRET, "token-b", 120);
        expectPage(fixture.server(), "token-b", null, 1, successPage("member-a", ""));

        fixture.gateway().fetchPage("", 1);
        clock.advance(Duration.ofSeconds(59));
        fixture.gateway().fetchPage("", 1);
        clock.advance(Duration.ofSeconds(1));
        fixture.gateway().fetchPage("", 1);

        fixture.server().verify();
    }

    @Test
    void validatesLimitBeforeCallingWeCom() {
        GatewayFixture fixture = fixture(DIRECTORY_SECRET);

        assertThatThrownBy(() -> fixture.gateway().fetchPage("", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 10000");
        assertThatThrownBy(() -> fixture.gateway().fetchPage("", 10_001))
                .isInstanceOf(IllegalArgumentException.class);
        fixture.server().verify();
    }

    @ParameterizedTest
    @EnumSource(DirectorySnapshotFailure.class)
    void exposesRetryabilityOnlyForDocumentedProviderFailures(
            DirectorySnapshotFailure failure
    ) {
        assertThat(failure.retryable()).isEqualTo(Set.of(
                DirectorySnapshotFailure.SYSTEM_BUSY,
                DirectorySnapshotFailure.RATE_LIMITED
        ).contains(failure));
    }

    private static Stream<Arguments> safeProviderFailures() {
        return Stream.of(
                Arguments.of(-1, DirectorySnapshotFailure.SYSTEM_BUSY),
                Arguments.of(40001, DirectorySnapshotFailure.INVALID_CREDENTIALS),
                Arguments.of(45009, DirectorySnapshotFailure.RATE_LIMITED),
                Arguments.of(48002, DirectorySnapshotFailure.PERMISSION_DENIED),
                Arguments.of(60020, DirectorySnapshotFailure.UNTRUSTED_IP),
                Arguments.of(999_999, DirectorySnapshotFailure.PROVIDER_ERROR)
        );
    }

    private static GatewayFixture fixture(String directorySecret) {
        return fixture(directorySecret, Clock.systemUTC());
    }

    private static GatewayFixture fixture(String directorySecret, Clock clock) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new GatewayFixture(gateway(builder, directorySecret, clock), server);
    }

    private static RestClientWeComDirectoryGateway gateway(
            RestClient.Builder builder,
            String directorySecret
    ) {
        return gateway(builder, directorySecret, Clock.systemUTC());
    }

    private static RestClientWeComDirectoryGateway gateway(
            RestClient.Builder builder,
            String directorySecret,
            Clock clock
    ) {
        return new RestClientWeComDirectoryGateway(
                builder,
                CORP_ID,
                directorySecret,
                clock
        );
    }

    private static void expectToken(
            MockRestServiceServer server,
            String secret,
            String token
    ) {
        expectToken(server, secret, token, 7200);
    }

    private static void expectToken(
            MockRestServiceServer server,
            String secret,
            String token,
            long expiresIn
    ) {
        server.expect(requestTo(tokenUri(secret)))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"errmsg\":\"ok\",\"access_token\":\""
                                + token + "\",\"expires_in\":" + expiresIn + "}",
                        MediaType.APPLICATION_JSON
                ));
    }

    private static void expectPage(
            MockRestServiceServer server,
            String token,
            String cursor,
            int limit,
            String response
    ) {
        String expectedRequest = cursor == null
                ? "{\"limit\":" + limit + "}"
                : "{\"cursor\":\"" + cursor + "\",\"limit\":" + limit + "}";
        server.expect(requestTo(pageUri(token)))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(expectedRequest, JsonCompareMode.STRICT))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private static void expectPageError(
            MockRestServiceServer server,
            String token,
            String cursor,
            int limit,
            long errorCode
    ) {
        expectPage(
                server,
                token,
                cursor,
                limit,
                "{\"errcode\":" + errorCode + ",\"errmsg\":\"sensitive provider error\"}"
        );
    }

    private static String successPage(String memberId, String nextCursor) {
        return "{\"errcode\":0,\"errmsg\":\"ok\",\"next_cursor\":\""
                + nextCursor + "\",\"dept_user\":[{\"userid\":\""
                + memberId + "\",\"department\":1}]}";
    }

    private static String tokenUri(String secret) {
        return API_BASE_URL + "/cgi-bin/gettoken?corpid=" + CORP_ID + "&corpsecret=" + secret;
    }

    private static String pageUri(String token) {
        return API_BASE_URL + "/cgi-bin/user/list_id?access_token=" + token;
    }

    private record GatewayFixture(
            RestClientWeComDirectoryGateway gateway,
            MockRestServiceServer server
    ) {
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
