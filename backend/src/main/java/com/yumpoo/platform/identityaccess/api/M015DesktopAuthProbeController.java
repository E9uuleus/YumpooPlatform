package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.api.error.ApiErrorWriter;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.request.RequestIdContext;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthToken;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthenticationService;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthorization;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopHandoffAuthorization;
import com.yumpoo.platform.identityaccess.application.desktopauth.M015VerificationReceipt;
import com.yumpoo.platform.identityaccess.application.desktopauth.PkceS256Challenge;
import com.yumpoo.platform.identityaccess.application.desktopauth.PkceVerifier;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptToken;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Default-absent M0-15 system-browser to Electron handoff probe. */
@RestController
@Profile("m0-15-live")
@ConditionalOnProperty(prefix = "yumpoo.m015.wecom", name = "enabled", havingValue = "true")
public final class M015DesktopAuthProbeController {

    public static final String AUTHORIZE_PATH = "/_m0/m0-15/electron/auth/authorize";
    public static final String CALLBACK_PATH = "/_m0/m0-15/wecom/callback";
    public static final String EXCHANGE_PATH = "/_m0/m0-15/electron/auth/exchange";
    public static final String OAUTH_NONCE_COOKIE = "__Host-yumpoo-m015-oauth-nonce";
    public static final String DESKTOP_STATE_COOKIE = "__Host-yumpoo-m015-desktop-state";

    private static final String NO_STORE = "no-store, no-cache, must-revalidate";
    private static final String S256 = "S256";
    private static final int MAX_AUTHORIZATION_CODE_LENGTH = 512;

    private final DesktopAuthenticationService authenticationService;
    private final ApiErrorWriter apiErrorWriter;
    private final ObjectMapper objectMapper;

    public M015DesktopAuthProbeController(
            DesktopAuthenticationService authenticationService,
            ApiErrorWriter apiErrorWriter,
            ObjectMapper objectMapper
    ) {
        this.authenticationService = authenticationService;
        this.apiErrorWriter = apiErrorWriter;
        this.objectMapper = objectMapper;
    }

    @GetMapping(AUTHORIZE_PATH)
    void authorize(HttpServletRequest request, HttpServletResponse response) throws IOException {
        applySecurityHeaders(response);
        String requestId = requestId(request);
        try {
            DesktopAuthToken desktopState = parseDesktopToken(singleParameter(request, "state"));
            PkceS256Challenge challenge = parseChallenge(singleParameter(request, "codeChallenge"));
            if (!S256.equals(singleParameter(request, "codeChallengeMethod"))) {
                throw malformedRequest();
            }
            DesktopAuthorization authorization = authenticationService.begin(
                    desktopState,
                    challenge,
                    requestId
            );
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader(HttpHeaders.LOCATION, authorization.authorizationUri().toASCIIString());
            response.addHeader(
                    HttpHeaders.SET_COOKIE,
                    credentialCookie(
                            OAUTH_NONCE_COOKIE,
                            authorization.oauthNonce().value()
                    ).toString()
            );
            response.addHeader(
                    HttpHeaders.SET_COOKIE,
                    credentialCookie(DESKTOP_STATE_COOKIE, desktopState.value()).toString()
            );
        } catch (ApplicationException exception) {
            apiErrorWriter.write(response, exception, requestId);
        }
    }

    @GetMapping(CALLBACK_PATH)
    void callback(HttpServletRequest request, HttpServletResponse response) throws IOException {
        applySecurityHeaders(response);
        clearCredentialCookies(response);
        String requestId = requestId(request);
        try {
            String authorizationCode = parseAuthorizationCode(singleParameter(request, "code"));
            OAuthAttemptToken oauthState = parseOAuthToken(singleParameter(request, "state"));
            OAuthAttemptToken oauthNonce = parseOAuthToken(singleCookie(request, OAUTH_NONCE_COOKIE));
            DesktopAuthToken desktopState = parseDesktopToken(
                    singleCookie(request, DESKTOP_STATE_COOKIE)
            );
            DesktopHandoffAuthorization handoff = authenticationService.completeAuthorization(
                    authorizationCode,
                    oauthState,
                    oauthNonce,
                    desktopState
            );
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader(HttpHeaders.LOCATION, deepLink(handoff).toASCIIString());
        } catch (ApplicationException exception) {
            apiErrorWriter.write(response, exception, requestId);
        }
    }

    @PostMapping(
            path = EXCHANGE_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    void exchange(
            @RequestBody(required = false) M015DesktopAuthExchangeRequest exchangeRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        applySecurityHeaders(response);
        String requestId = requestId(request);
        try {
            if (exchangeRequest == null) {
                throw malformedRequest();
            }
            M015VerificationReceipt receipt = authenticationService.exchange(
                    parseDesktopToken(exchangeRequest.code()),
                    parseDesktopToken(exchangeRequest.state()),
                    parseVerifier(exchangeRequest.codeVerifier()),
                    requestId
            );
            response.setStatus(HttpStatus.OK.value());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), receipt);
        } catch (ApplicationException exception) {
            apiErrorWriter.write(response, exception, requestId);
        }
    }

    private static URI deepLink(DesktopHandoffAuthorization handoff) {
        return URI.create(
                "yumpoo://auth/callback?code=" + handoff.handoffCode().value()
                        + "&state=" + handoff.desktopState().value()
        );
    }

    private static DesktopAuthToken parseDesktopToken(String value) {
        try {
            return DesktopAuthToken.of(value);
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw malformedRequest();
        }
    }

    private static OAuthAttemptToken parseOAuthToken(String value) {
        try {
            return OAuthAttemptToken.of(value);
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw malformedRequest();
        }
    }

    private static PkceS256Challenge parseChallenge(String value) {
        try {
            return new PkceS256Challenge(value);
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw malformedRequest();
        }
    }

    private static PkceVerifier parseVerifier(String value) {
        try {
            return PkceVerifier.of(value);
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw malformedRequest();
        }
    }

    private static String parseAuthorizationCode(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > MAX_AUTHORIZATION_CODE_LENGTH
                || value.chars().anyMatch(character -> Character.isISOControl(character)
                || Character.isWhitespace(character))) {
            throw malformedRequest();
        }
        return value;
    }

    private static String singleParameter(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length != 1) {
            throw malformedRequest();
        }
        return values[0];
    }

    private static String singleCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            throw malformedRequest();
        }
        List<String> values = new ArrayList<>();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                values.add(cookie.getValue());
            }
        }
        if (values.size() != 1) {
            throw malformedRequest();
        }
        return values.getFirst();
    }

    private static ApplicationException malformedRequest() {
        return new ApplicationException(StandardErrorCode.MALFORMED_REQUEST);
    }

    private static ResponseCookie credentialCookie(String name, String value) {
        return ResponseCookie.from(name, value)
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(DesktopAuthenticationService.AUTHORIZE_TTL)
                .build();
    }

    private static ResponseCookie clearCookie(String name) {
        return ResponseCookie.from(name, "")
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }

    private static void clearCredentialCookies(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, clearCookie(OAUTH_NONCE_COOKIE).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, clearCookie(DESKTOP_STATE_COOKIE).toString());
    }

    private static HttpHeaders securityHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, NO_STORE);
        headers.set(HttpHeaders.PRAGMA, "no-cache");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        return headers;
    }

    private static void applySecurityHeaders(HttpServletResponse response) {
        securityHeaders().forEach((name, values) ->
                values.forEach(value -> response.addHeader(name, value))
        );
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdContext.ATTRIBUTE_NAME);
        if (value instanceof String requestId && RequestIdContext.isValid(requestId)) {
            return requestId;
        }
        throw new IllegalStateException("requestId filter did not initialize the request");
    }
}
