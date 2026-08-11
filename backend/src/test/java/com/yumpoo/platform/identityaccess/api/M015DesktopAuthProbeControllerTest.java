package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.api.error.ApiErrorWriter;
import com.yumpoo.platform.foundation.api.web.RequestIdFilter;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthToken;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthenticationService;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthorization;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopHandoffAuthorization;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopIdentityFingerprint;
import com.yumpoo.platform.identityaccess.application.desktopauth.M015VerificationReceipt;
import com.yumpoo.platform.identityaccess.application.desktopauth.M015VerificationReceiptSigner;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptToken;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class M015DesktopAuthProbeControllerTest {

    private static final String REQUEST_ID = "m015.request-1";
    private static final String DESKTOP_STATE = "D".repeat(43);
    private static final String OAUTH_STATE = "O".repeat(43);
    private static final String NONCE = "N".repeat(43);
    private static final String HANDOFF_CODE = "H".repeat(43);
    private static final String CHALLENGE = "C".repeat(43);
    private static final String VERIFIER = "V".repeat(43);

    private DesktopAuthenticationService authenticationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authenticationService = mock(DesktopAuthenticationService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ApiErrorWriter errorWriter = new ApiErrorWriter(objectMapper);
        M015DesktopAuthProbeController controller = new M015DesktopAuthProbeController(
                authenticationService,
                errorWriter,
                objectMapper
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new RequestIdFilter(errorWriter))
                .build();
    }

    @Test
    void authorizeRequiresS256AndSetsTwoSecureHostCookies() throws Exception {
        URI provider = URI.create(
                "https://open.weixin.qq.com/connect/oauth2/authorize?state=" + OAUTH_STATE
        );
        when(authenticationService.begin(any(), any(), anyString()))
                .thenReturn(new DesktopAuthorization(
                        provider,
                        OAuthAttemptToken.of(NONCE),
                        Instant.parse("2026-08-11T04:05:00Z")
                ));

        mockMvc.perform(get(M015DesktopAuthProbeController.AUTHORIZE_PATH)
                        .header("X-Request-Id", REQUEST_ID)
                        .queryParam("state", DESKTOP_STATE)
                        .queryParam("codeChallenge", CHALLENGE)
                        .queryParam("codeChallengeMethod", "S256"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, provider.toString()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(result -> {
                    List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
                    assertThat(cookies).hasSize(2);
                    assertThat(cookies).anySatisfy(cookie -> assertThat(cookie).startsWith(
                            M015DesktopAuthProbeController.OAUTH_NONCE_COOKIE + "=" + NONCE
                    ));
                    assertThat(cookies).anySatisfy(cookie -> assertThat(cookie).startsWith(
                            M015DesktopAuthProbeController.DESKTOP_STATE_COOKIE + "=" + DESKTOP_STATE
                    ));
                })
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=300")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, not(containsString("Domain="))));
    }

    @Test
    void malformedAuthorizeInputReturnsStable400BeforeStartingOAuth() throws Exception {
        mockMvc.perform(get(M015DesktopAuthProbeController.AUTHORIZE_PATH)
                        .header("X-Request-Id", REQUEST_ID)
                        .queryParam("state", DESKTOP_STATE)
                        .queryParam("codeChallenge", CHALLENGE)
                        .queryParam("codeChallengeMethod", "plain"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void callbackClearsCookiesAndRedirectsOnlyToTheFixedDeepLink() throws Exception {
        when(authenticationService.completeAuthorization(anyString(), any(), any(), any()))
                .thenReturn(new DesktopHandoffAuthorization(
                        DesktopAuthToken.of(HANDOFF_CODE),
                        DesktopAuthToken.of(DESKTOP_STATE),
                        Instant.parse("2026-08-11T04:01:00Z")
                ));

        mockMvc.perform(get(M015DesktopAuthProbeController.CALLBACK_PATH)
                        .header("X-Request-Id", REQUEST_ID)
                        .queryParam("code", "valid-code")
                        .queryParam("state", OAUTH_STATE)
                        .cookie(
                                new Cookie(M015DesktopAuthProbeController.OAUTH_NONCE_COOKIE, NONCE),
                                new Cookie(
                                        M015DesktopAuthProbeController.DESKTOP_STATE_COOKIE,
                                        DESKTOP_STATE
                                )
                        ))
                .andExpect(status().isFound())
                .andExpect(header().string(
                        HttpHeaders.LOCATION,
                        "yumpoo://auth/callback?code=" + HANDOFF_CODE + "&state=" + DESKTOP_STATE
                ))
                .andExpect(result -> {
                    List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
                    assertThat(cookies).hasSize(2);
                    assertThat(cookies).anySatisfy(cookie -> assertThat(cookie).startsWith(
                            M015DesktopAuthProbeController.OAUTH_NONCE_COOKIE + "="
                    ));
                    assertThat(cookies).anySatisfy(cookie -> assertThat(cookie).startsWith(
                            M015DesktopAuthProbeController.DESKTOP_STATE_COOKIE + "="
                    ));
                })
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }

    @Test
    void exchangeReturnsSignedRedactedReceiptAndSecurityFailureIsUniform401() throws Exception {
        M015VerificationReceiptSigner signer = new M015VerificationReceiptSigner(
                "m015-controller-test-key-0123456789-abcdef!",
                Clock.fixed(Instant.parse("2026-08-11T04:00:00Z"), ZoneOffset.UTC)
        );
        M015VerificationReceipt receipt = signer.sign(
                new DesktopIdentityFingerprint("a".repeat(64), "b".repeat(64)),
                REQUEST_ID
        );
        when(authenticationService.exchange(any(), any(), any(), anyString()))
                .thenReturn(receipt)
                .thenThrow(new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED));
        String body = """
                {"code":"%s","state":"%s","codeVerifier":"%s"}
                """.formatted(HANDOFF_CODE, DESKTOP_STATE, VERIFIER);

        mockMvc.perform(post(M015DesktopAuthProbeController.EXCHANGE_PATH)
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASS"))
                .andExpect(jsonPath("$.corpFingerprint").value("a".repeat(64)))
                .andExpect(jsonPath("$.memberFingerprint").value("b".repeat(64)))
                .andExpect(jsonPath("$.rawCorpId").doesNotExist())
                .andExpect(jsonPath("$.rawMemberId").doesNotExist());

        mockMvc.perform(post(M015DesktopAuthProbeController.EXCHANGE_PATH)
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void malformedExchangeTokenReturns400() throws Exception {
        mockMvc.perform(post(M015DesktopAuthProbeController.EXCHANGE_PATH)
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType("application/json")
                        .content("{\"code\":\"short\",\"state\":\""
                                + DESKTOP_STATE
                                + "\",\"codeVerifier\":\""
                                + VERIFIER
                                + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
}
