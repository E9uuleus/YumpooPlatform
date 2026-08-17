package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.api.error.ApiErrorWriter;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.request.RequestIdContext;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthToken;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopHandoffAuthorization;
import com.yumpoo.platform.identityaccess.application.desktopauth.PkceS256Challenge;
import com.yumpoo.platform.identityaccess.application.desktopauth.PkceVerifier;
import com.yumpoo.platform.identityaccess.application.desktopauth.ProductDesktopAuthenticationService;
import com.yumpoo.platform.identityaccess.application.desktopauth.ProductDesktopAuthorization;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

@ApiV1Controller
public final class DesktopAuthenticationController {

    private static final String NO_STORE = "no-store, no-cache, must-revalidate";
    private final ProductDesktopAuthenticationService authenticationService;
    private final ApiErrorWriter errorWriter;
    private final ObjectMapper objectMapper;

    public DesktopAuthenticationController(
            ProductDesktopAuthenticationService authenticationService,
            ApiErrorWriter errorWriter,
            ObjectMapper objectMapper
    ) {
        this.authenticationService = authenticationService;
        this.errorWriter = errorWriter;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/electron/auth/attempts", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    void begin(
            @RequestBody(required = false) DesktopAuthAttemptRequest body,
            @RequestHeader(name = "X-Client-Type", required = false) String clientType,
            @RequestHeader(name = "X-Client-Version", required = false) String clientVersion,
            @RequestHeader(name = "X-Client-Protocol-Version", required = false) String protocolVersion,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        applySecurityHeaders(response);
        String requestId = requestId(request);
        try {
            if (body == null || !"ELECTRON".equals(clientType)
                    || !"S256".equals(body.codeChallengeMethod())) {
                throw malformedRequest();
            }
            ProductDesktopAuthorization authorization = authenticationService.begin(
                    parseToken(body.state()), new PkceS256Challenge(body.codeChallenge()),
                    requestId, clientVersion, protocolVersion
            );
            writeJson(response, HttpStatus.CREATED, new DesktopAuthAttemptResponse(
                    authorization.authorizationUri(), authorization.expiresAt()
            ));
        } catch (NullPointerException | IllegalArgumentException exception) {
            errorWriter.write(response, malformedRequest(), requestId);
        } catch (ApplicationException exception) {
            errorWriter.write(response, exception, requestId);
        }
    }

    @GetMapping("/electron/auth/wecom/callback")
    void callback(HttpServletRequest request, HttpServletResponse response) throws IOException {
        applySecurityHeaders(response);
        try {
            DesktopHandoffAuthorization handoff = authenticationService.completeAuthorization(
                    singleParameter(request, "code"),
                    parseToken(singleParameter(request, "state"))
            );
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader(HttpHeaders.LOCATION, deepLink(handoff).toASCIIString());
        } catch (IllegalArgumentException exception) {
            writeSafeCallbackFailure(response, HttpStatus.BAD_REQUEST);
        } catch (ApplicationException exception) {
            writeSafeCallbackFailure(
                    response,
                    exception.errorCode() == StandardErrorCode.DEPENDENCY_UNAVAILABLE
                            ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED
            );
        }
    }

    @PostMapping(path = "/electron/auth/exchange", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    void exchange(
            @RequestBody(required = false) DesktopAuthExchangeRequest body,
            @RequestHeader(name = "X-Client-Type", required = false) String clientType,
            @RequestHeader(name = "X-Client-Version", required = false) String clientVersion,
            @RequestHeader(name = "X-Client-Protocol-Version", required = false) String protocolVersion,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        applySecurityHeaders(response);
        String requestId = requestId(request);
        try {
            if (body == null || !"ELECTRON".equals(clientType)
                    || clientVersion == null || !"1".equals(protocolVersion)) {
                throw malformedRequest();
            }
            IssuedSession issued = authenticationService.exchange(
                    parseToken(body.handoffCode()), parseToken(body.state()),
                    PkceVerifier.of(body.codeVerifier())
            );
            writeJson(response, HttpStatus.OK, new DesktopAuthExchangeResponse(
                    issued.sessionCredential().value(), issued.csrfCredential().value(),
                    issued.absoluteExpiresAt()
            ));
        } catch (NullPointerException | IllegalArgumentException exception) {
            errorWriter.write(response, malformedRequest(), requestId);
        } catch (ApplicationException exception) {
            errorWriter.write(response, exception, requestId);
        }
    }

    private void writeJson(HttpServletResponse response, HttpStatus status, Object body)
            throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private static URI deepLink(DesktopHandoffAuthorization handoff) {
        return URI.create("yumpoo://auth/callback?code=" + handoff.handoffCode().value()
                + "&state=" + handoff.desktopState().value());
    }

    private static DesktopAuthToken parseToken(String value) {
        return DesktopAuthToken.of(value);
    }

    private static String singleParameter(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length != 1) {
            throw new IllegalArgumentException("invalid callback parameter");
        }
        return values[0];
    }

    private static void applySecurityHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy",
                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
    }

    private static void writeSafeCallbackFailure(HttpServletResponse response, HttpStatus status)
            throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.getWriter().write("<!doctype html><html lang=\"zh-CN\"><title>登录未完成</title>"
                + "<p>登录未完成，请关闭此页面并返回 Yumpoo 重新开始。</p></html>");
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdContext.ATTRIBUTE_NAME);
        if (value instanceof String requestId && RequestIdContext.isValid(requestId)) {
            return requestId;
        }
        throw new IllegalStateException("requestId filter did not initialize the request");
    }

    private static ApplicationException malformedRequest() {
        return new ApplicationException(StandardErrorCode.MALFORMED_REQUEST);
    }
}
