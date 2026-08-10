package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.api.error.ApiErrorWriter;
import com.yumpoo.platform.foundation.api.web.RequestIdFilter;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.application.oauth.M012VerificationReceiptSigner;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptToken;
import com.yumpoo.platform.identityaccess.application.oauth.VerifiedWeComIdentity;
import com.yumpoo.platform.identityaccess.application.oauth.WeComOAuthAuthorization;
import com.yumpoo.platform.identityaccess.application.oauth.WeComOAuthVerificationService;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class M012WeComOAuthProbeControllerTest {

    private static final String REQUEST_ID = "m012.request-1";
    private static final String STATE = "A".repeat(43);
    private static final String NONCE = "B".repeat(43);
    private static final String EVIDENCE_KEY = "m0-12-controller-test-evidence-key-32-bytes";

    private WeComOAuthVerificationService verificationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        verificationService = mock(WeComOAuthVerificationService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ApiErrorWriter apiErrorWriter = new ApiErrorWriter(objectMapper);
        M012WeComOAuthProbeController controller = new M012WeComOAuthProbeController(
                verificationService,
                new M012VerificationReceiptSigner(
                        EVIDENCE_KEY,
                        Clock.fixed(Instant.parse("2026-08-10T08:00:00Z"), ZoneOffset.UTC)
                ),
                apiErrorWriter,
                objectMapper
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new RequestIdFilter(apiErrorWriter))
                .build();
    }

    @Test
    void authorizeSetsASecureHostCookieAndRedirectsWithoutCaching() throws Exception {
        URI provider = URI.create("https://open.weixin.qq.com/connect/oauth2/authorize?state=" + STATE);
        when(verificationService.begin(anyString())).thenReturn(new WeComOAuthAuthorization(
                provider,
                OAuthAttemptToken.of(STATE),
                OAuthAttemptToken.of(NONCE),
                Instant.parse("2026-08-10T08:05:00Z")
        ));

        mockMvc.perform(get(M012WeComOAuthProbeController.AUTHORIZE_PATH)
                        .header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, provider.toString()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(
                        M012WeComOAuthProbeController.NONCE_COOKIE + "=" + NONCE
                )))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=300")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, not(containsString("Domain="))));
    }

    @Test
    void authorizationDependencyFailureRetainsSecurityHeadersAndStable503Body() throws Exception {
        when(verificationService.begin(anyString()))
                .thenThrow(new ApplicationException(StandardErrorCode.DEPENDENCY_UNAVAILABLE));

        mockMvc.perform(get(M012WeComOAuthProbeController.AUTHORIZE_PATH)
                        .header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void successfulCallbackReturnsOnlyTheSignedRedactedReceiptAndClearsNonce() throws Exception {
        when(verificationService.verify("valid-code", STATE, NONCE))
                .thenReturn(new VerifiedWeComIdentity("raw-corp-id", "raw-member-id"));

        mockMvc.perform(get(M012WeComOAuthProbeController.CALLBACK_PATH)
                        .header("X-Request-Id", REQUEST_ID)
                        .queryParam("code", "valid-code")
                        .queryParam("state", STATE)
                        .cookie(new Cookie(M012WeComOAuthProbeController.NONCE_COOKIE, NONCE)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.status").value("PASS"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.corpFingerprint").isString())
                .andExpect(jsonPath("$.memberFingerprint").isString())
                .andExpect(jsonPath("$.signature").isString())
                .andExpect(content().string(not(containsString("raw-corp-id"))))
                .andExpect(content().string(not(containsString("raw-member-id"))))
                .andExpect(content().string(not(containsString(EVIDENCE_KEY))));
    }

    @Test
    void rejectedCallbackUsesTheStable401BodyAndStillClearsNonce() throws Exception {
        when(verificationService.verify(anyString(), anyString(), anyString()))
                .thenThrow(new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED));

        mockMvc.perform(get(M012WeComOAuthProbeController.CALLBACK_PATH)
                        .header("X-Request-Id", REQUEST_ID)
                        .queryParam("code", "invalid-code")
                        .queryParam("state", STATE)
                        .cookie(new Cookie(M012WeComOAuthProbeController.NONCE_COOKIE, NONCE)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    void missingCallbackValuesUseTheSameStable401Body() throws Exception {
        when(verificationService.verify(
                nullable(String.class),
                nullable(String.class),
                nullable(String.class)
        )).thenThrow(new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED));

        mockMvc.perform(get(M012WeComOAuthProbeController.CALLBACK_PATH)
                        .header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    void dependencyFailureUsesTheStable503BodyWithoutLeakingProviderDetails() throws Exception {
        when(verificationService.verify(anyString(), anyString(), anyString()))
                .thenThrow(new ApplicationException(StandardErrorCode.DEPENDENCY_UNAVAILABLE));

        mockMvc.perform(get(M012WeComOAuthProbeController.CALLBACK_PATH)
                        .header("X-Request-Id", REQUEST_ID)
                        .queryParam("code", "provider-code")
                        .queryParam("state", STATE)
                        .cookie(new Cookie(M012WeComOAuthProbeController.NONCE_COOKIE, NONCE)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(content().string(not(containsString("provider-code"))));
    }
}
