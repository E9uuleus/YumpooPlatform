package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.api.error.ApiErrorWriter;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.request.RequestIdContext;
import com.yumpoo.platform.identityaccess.application.oauth.M012VerificationReceipt;
import com.yumpoo.platform.identityaccess.application.oauth.M012VerificationReceiptSigner;
import com.yumpoo.platform.identityaccess.application.oauth.VerifiedWeComIdentity;
import com.yumpoo.platform.identityaccess.application.oauth.WeComOAuthAuthorization;
import com.yumpoo.platform.identityaccess.application.oauth.WeComOAuthVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 默认不存在、仅供 M0-12 真实测试企业验收使用的诊断入口。
 */
@RestController
@Profile("m0-12-live")
@ConditionalOnProperty(prefix = "yumpoo.m012.wecom", name = "enabled", havingValue = "true")
public final class M012WeComOAuthProbeController {

    public static final String AUTHORIZE_PATH = "/_m0/m0-12/wecom/authorize";
    public static final String CALLBACK_PATH = "/_m0/m0-12/wecom/callback";
    public static final String NONCE_COOKIE = "__Host-yumpoo-m012-oauth-nonce";

    private static final String NO_STORE = "no-store, no-cache, must-revalidate";

    private final WeComOAuthVerificationService verificationService;
    private final M012VerificationReceiptSigner receiptSigner;
    private final ApiErrorWriter apiErrorWriter;
    private final ObjectMapper objectMapper;

    public M012WeComOAuthProbeController(
            WeComOAuthVerificationService verificationService,
            M012VerificationReceiptSigner receiptSigner,
            ApiErrorWriter apiErrorWriter,
            ObjectMapper objectMapper
    ) {
        this.verificationService = verificationService;
        this.receiptSigner = receiptSigner;
        this.apiErrorWriter = apiErrorWriter;
        this.objectMapper = objectMapper;
    }

    @GetMapping(AUTHORIZE_PATH)
    void authorize(HttpServletRequest request, HttpServletResponse response) throws IOException {
        applySecurityHeaders(response);
        String requestId = requestId(request);
        try {
            WeComOAuthAuthorization authorization = verificationService.begin(requestId);
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader(HttpHeaders.LOCATION, authorization.authorizationUri().toASCIIString());
            response.addHeader(
                    HttpHeaders.SET_COOKIE,
                    nonceCookie(authorization.nonce().value()).toString()
            );
        } catch (ApplicationException exception) {
            apiErrorWriter.write(response, exception, requestId);
        }
    }

    @GetMapping(CALLBACK_PATH)
    void callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @CookieValue(name = NONCE_COOKIE, required = false) String nonce,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        applySecurityHeaders(response);
        response.addHeader(HttpHeaders.SET_COOKIE, clearNonceCookie().toString());
        String requestId = requestId(request);

        try {
            VerifiedWeComIdentity identity = verificationService.verify(code, state, nonce);
            M012VerificationReceipt receipt = receiptSigner.sign(
                    identity.corpId(),
                    identity.memberId(),
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
        securityHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
    }

    private static ResponseCookie nonceCookie(String nonce) {
        return ResponseCookie.from(NONCE_COOKIE, nonce)
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(WeComOAuthVerificationService.DEFAULT_ATTEMPT_TTL)
                .build();
    }

    private static ResponseCookie clearNonceCookie() {
        return ResponseCookie.from(NONCE_COOKIE, "")
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdContext.ATTRIBUTE_NAME);
        if (value instanceof String requestId && RequestIdContext.isValid(requestId)) {
            return requestId;
        }
        throw new IllegalStateException("requestId filter did not initialize the request");
    }
}
