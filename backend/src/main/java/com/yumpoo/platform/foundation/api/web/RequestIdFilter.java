package com.yumpoo.platform.foundation.api.web;

import com.yumpoo.platform.foundation.api.error.ApiErrorWriter;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.logging.StructuredLoggingContext;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.foundation.application.request.RequestIdContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 最先建立 requestId，并为 DispatcherServlet 之前的未提交失败提供统一安全错误体。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestIdFilter.class);

    private final ApiErrorWriter apiErrorWriter;
    private final io.micrometer.core.instrument.Timer businessRequests;

    public RequestIdFilter(ApiErrorWriter apiErrorWriter) {
        this.apiErrorWriter = apiErrorWriter;
        this.businessRequests = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RequestIdFilter(
        ApiErrorWriter apiErrorWriter,
        org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries
    ) {
        this.apiErrorWriter = apiErrorWriter;
        var registry = registries.getIfAvailable();
        this.businessRequests =
            registry == null
                ? null
                : io.micrometer.core.instrument.Timer.builder("yumpoo.http.business")
                      .description("Business HTTP latency without route tags")
                      .publishPercentiles(.95)
                      .distributionStatisticExpiry(java.time.Duration.ofMinutes(5))
                      .distributionStatisticBufferLength(1)
                      .register(registry);
    }

    /** Streaming downloads finish in an async dispatch; its failures need the same requestId and error handling. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        long started = System.nanoTime();
        // 异步与错误派发沿用首次派发的 requestId，响应头已在首次派发写出。
        Object dispatched = request.getAttribute(RequestIdContext.ATTRIBUTE_NAME);
        boolean initial = dispatched == null;
        String requestId = initial ? trustedRequestId(request) : dispatched.toString();
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        if (initial) {
            request.setAttribute(RequestIdContext.ATTRIBUTE_NAME, requestId);
            response.setHeader(RequestIdContext.HEADER_NAME, requestId);
        }
        preventOperationsCaching(request, response);
        RequestCorrelation correlation = RequestCorrelation.root(requestId);
        Map<String, Object> logging = new LinkedHashMap<>();
        logging.put(StructuredLoggingContext.REQUEST_ID, requestId);
        logging.put(StructuredLoggingContext.CORRELATION_ID, requestId);
        // 后续派发不再经过会话认证，用户与客户端类型取自首次派发留下的请求属性。
        logging.put(StructuredLoggingContext.USER_ID, request.getAttribute(HttpAccessLog.USER_ID));
        logging.put(StructuredLoggingContext.CLIENT_TYPE, request.getAttribute(HttpAccessLog.CLIENT_TYPE));
        try (
            RequestCorrelationContext.Scope ignoredCorrelation = RequestCorrelationContext.open(correlation);
            StructuredLoggingContext.Scope ignoredLogging = StructuredLoggingContext.open(logging)
        ) {
            try {
                filterChain.doFilter(request, response);
            } catch (ApplicationException exception) {
                if (response.isCommitted()) {
                    throw exception;
                }
                resetAndWrite(request, response, exception, requestId);
            } catch (Exception exception) {
                if (!Boolean.TRUE.equals(request.getAttribute(HttpAccessLog.ERROR_LOGGED))) {
                    LOGGER.atError()
                        .setMessage("unexpected request failure")
                        .setCause(exception)
                        .addKeyValue("event", "http.request.unhandled")
                        .addKeyValue("method", request.getMethod())
                        .addKeyValue("route", HttpAccessLog.route(request))
                        .log();
                    request.setAttribute(HttpAccessLog.ERROR_LOGGED, true);
                }
                if (response.isCommitted()) {
                    rethrow(exception);
                }
                resetAndWrite(request, response, new ApplicationException(StandardErrorCode.INTERNAL_ERROR), requestId);
            } finally {
                preventOperationsCaching(request, response);
                // 访问日志与业务耗时只记首次派发，异步收尾不重复计数。
                if (initial) {
                    HttpAccessLog.completed(request, response, started);
                    String path = request.getRequestURI();
                    if (
                        businessRequests != null &&
                        !path.startsWith("/api/v1/admin/operations") &&
                        !path.startsWith("/actuator")
                    ) {
                        businessRequests.record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
                    }
                }
            }
        }
    }

    private void resetAndWrite(
        HttpServletRequest request,
        HttpServletResponse response,
        ApplicationException exception,
        String requestId
    ) throws IOException {
        response.reset();
        HttpAccessLog.applicationError(request, exception);
        preventOperationsCaching(request, response);
        apiErrorWriter.write(response, exception, requestId);
    }

    private static void preventOperationsCaching(HttpServletRequest request, HttpServletResponse response) {
        String path = request.getRequestURI();
        if (path.equals("/api/v1/admin/operations") || path.startsWith("/api/v1/admin/operations/")) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        }
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
        if (values.hasMoreElements() || value == null || !RequestIdContext.isValid(value)) {
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
