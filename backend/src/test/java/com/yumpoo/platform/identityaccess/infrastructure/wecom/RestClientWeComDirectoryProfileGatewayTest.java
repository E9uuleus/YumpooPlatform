package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import com.yumpoo.platform.identityaccess.application.directory.DirectoryOptionalField;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncException;
import com.yumpoo.platform.identityaccess.application.directory.WeComRawMemberProfile;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestClientWeComDirectoryProfileGatewayTest {

    private static final String BASE = "https://qyapi.weixin.qq.com";
    private static final String CORP_ID = "ww-profile-test";
    private static final String PROFILE_SECRET = "profile-secret";

    @Test
    void readsDepartmentAndMemberWithOneProfileTokenAndPreservesVisibilityStates() {
        Fixture fixture = fixture();
        expectToken(fixture.server(), "token-a");
        fixture.server().expect(requestTo(BASE
                        + "/cgi-bin/department/list?access_token=token-a"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"errcode":0,"errmsg":"ok","department":[
                          {"id":20,"name":"华东区"},{"id":3,"name":"研发部"}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo(BASE
                        + "/cgi-bin/user/get?access_token=token-a&userid=member-a"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"errcode":0,"errmsg":"ok","userid":"member-a","name":"Alice",
                         "mobile":"","department":[20,3]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.gateway().fetchDepartmentNames())
                .containsEntry(3L, "研发部")
                .containsEntry(20L, "华东区");
        WeComRawMemberProfile profile = fixture.gateway().fetchMemberProfile("member-a");

        assertThat(profile.externalUserId()).isEqualTo("member-a");
        assertThat(profile.email().state()).isEqualTo(DirectoryOptionalField.State.UNAVAILABLE);
        assertThat(profile.mobile().state()).isEqualTo(DirectoryOptionalField.State.CLEAR);
        assertThat(profile.departmentIds()).containsExactly(20L, 3L);
        assertThat(profile.toString()).doesNotContain("member-a", "Alice");
        fixture.server().verify();
    }

    @Test
    void refreshesRejectedProfileTokenExactlyOnce() {
        Fixture fixture = fixture();
        expectToken(fixture.server(), "token-a");
        expectMember(fixture.server(), "token-a", "{\"errcode\":40014,\"errmsg\":\"expired\"}");
        expectToken(fixture.server(), "token-b");
        expectMember(fixture.server(), "token-b", """
                {"errcode":0,"errmsg":"ok","userid":"member-a","name":"Alice",
                 "email":"alice@example.test","mobile":"13800000000","department":[3]}
                """);

        WeComRawMemberProfile profile = fixture.gateway().fetchMemberProfile("member-a");

        assertThat(profile.email().state()).isEqualTo(DirectoryOptionalField.State.PRESENT);
        fixture.server().verify();
    }

    @Test
    void rejectsInvisibleRequiredFieldsWithoutRetainingProviderData() {
        Fixture fixture = fixture();
        expectToken(fixture.server(), "sensitive-token");
        expectMember(fixture.server(), "sensitive-token", """
                {"errcode":0,"errmsg":"sensitive-provider-body",
                 "userid":"sensitive-member","department":[3]}
                """);

        DirectorySyncException exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> fixture.gateway().fetchMemberProfile("member-a"),
                DirectorySyncException.class
        );
        StringWriter trace = new StringWriter();
        exception.printStackTrace(new PrintWriter(trace));

        assertThat(exception.errorCode()).isEqualTo("DIRECTORY_PROFILE_NAME_UNAVAILABLE");
        assertThat(trace.toString()).doesNotContain(
                PROFILE_SECRET,
                "sensitive-token",
                "sensitive-member",
                "sensitive-provider-body"
        );
        assertThat(exception).hasNoCause();
        fixture.server().verify();
    }

    @Test
    void classifiesPermissionFailureWithStableCode() {
        Fixture fixture = fixture();
        expectToken(fixture.server(), "token-a");
        expectMember(fixture.server(), "token-a", "{\"errcode\":48002,\"errmsg\":\"forbidden\"}");

        assertThatThrownBy(() -> fixture.gateway().fetchMemberProfile("member-a"))
                .isInstanceOfSatisfying(DirectorySyncException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo("DIRECTORY_PROFILE_PERMISSION_DENIED"));
        fixture.server().verify();
    }

    private static Fixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(
                new RestClientWeComDirectoryProfileGateway(
                        builder,
                        CORP_ID,
                        PROFILE_SECRET,
                        Clock.systemUTC()
                ),
                server
        );
    }

    private static void expectToken(MockRestServiceServer server, String token) {
        server.expect(requestTo(BASE + "/cgi-bin/gettoken?corpid=" + CORP_ID
                        + "&corpsecret=" + PROFILE_SECRET))
                .andRespond(withSuccess("{\"errcode\":0,\"errmsg\":\"ok\","
                                + "\"access_token\":\"" + token + "\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON));
    }

    private static void expectMember(
            MockRestServiceServer server,
            String token,
            String response
    ) {
        server.expect(requestTo(BASE + "/cgi-bin/user/get?access_token=" + token
                        + "&userid=member-a"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private record Fixture(
            RestClientWeComDirectoryProfileGateway gateway,
            MockRestServiceServer server
    ) {
    }
}
