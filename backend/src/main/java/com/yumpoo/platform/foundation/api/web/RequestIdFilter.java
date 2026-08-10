package com.yumpoo.platform.foundation.api.web;

import com.yumpoo.platform.foundation.api.error.ApiErrorWriter;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.request.RequestIdContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 最先建立 requestId，并为 DispatcherServlet 之前的未提交失败提供统一安全错误体。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestIdFilter extends OncePerRequestFilter {

    private static final int MAX_REQUEST_ID_LENGTH = 64;
    private static final Pattern SAFE_REQUEST_ID =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$");

    private final ApiErrorWriter apiErrorWriter;

    public RequestIdFilter(ApiErrorWriter apiErrorWriter) {
        this.apiErrorWriter = apiErrorWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = trustedRequestId(request);
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }

        request.setAttribute(RequestIdContext.ATTRIBUTE_NAME, requestId);
        response.setHeader(RequestIdContext.HEADER_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } catch (ApplicationException exception) {
            if (response.isCommitted()) {
                throw exception;
            }
            resetAndWrite(response, exception, requestId);
        } catch (Exception exception) {
            if (response.isCommitted()) {
                rethrow(exception);
            }
            resetAndWrite(
                    response,
                    new ApplicationException(StandardErrorCode.INTERNAL_ERROR),
                    requestId
            );
        }
    }

    private void resetAndWrite(
            HttpServletResponse response,
            ApplicationException exception,
            String requestId
    ) throws IOException {
        response.reset();
        apiErrorWriter.write(response, exception, requestId);
    }

    private static void rethrow(Exception exception) throws ServletException, IOException {
        if (exception instanceof IOException ioException) {
            throw ioException;
        }
        if (exception instanceof ServletException servletException) {
            throw servletException;
        }
        if (exception instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new ServletException(exception);
    }

    private static String trustedRequestId(HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())) {
            return null;
        }

        Enumeration<String> values = request.getHeaders(RequestIdContext.HEADER_NAME);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String value = values.nextElement();
        if (values.hasMoreElements()
                || value == null
                || value.length() > MAX_REQUEST_ID_LENGTH
                || !SAFE_REQUEST_ID.matcher(value).matches()) {
            return null;
        }
        return value;
    }

    private static boolean isLoopback(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(remoteAddress).isLoopbackAddress();
        } catch (UnknownHostException ignored) {
            return false;
        }
    }
}
