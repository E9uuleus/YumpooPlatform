package com.yumpoo.platform.foundation.api.web;

import com.yumpoo.platform.foundation.application.logging.LogFields;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;

public final class HttpAccessLog {

    public static final String USER_ID = HttpAccessLog.class.getName() + ".userId";
    public static final String CLIENT_TYPE = HttpAccessLog.class.getName() + ".clientType";
    public static final String ERROR_LOGGED = HttpAccessLog.class.getName() + ".errorLogged";
    private static final String ERROR_CODE = HttpAccessLog.class.getName() + ".errorCode";
    private static final String ERROR_REASON = HttpAccessLog.class.getName() + ".errorReason";
    private static final String FIELD_ERRORS = HttpAccessLog.class.getName() + ".fieldErrors";
    private static final String STAGE = HttpAccessLog.class.getName() + ".stage";

    private HttpAccessLog() {}

    public static void applicationError(HttpServletRequest request, ApplicationException exception) {
        rejected(request, exception.errorCode().name(), exception.reason(), exception.fieldViolations().stream()
                .map(violation -> violation.field() + ":" + violation.code()).toList());
    }

    /** 拒绝码、原因与字段错误写入同一条访问日志；字段只记路径与错误码，不记字段值或提示文本。 */
    public static void rejected(HttpServletRequest request, String errorCode, String reason, List<String> fieldErrors) {
        request.setAttribute(ERROR_CODE, errorCode);
        request.setAttribute(ERROR_REASON, reason);
        request.setAttribute(FIELD_ERRORS, fieldErrors.isEmpty() ? null
                : String.join(",", fieldErrors.subList(0, Math.min(10, fieldErrors.size()))));
    }

    /** 同一接口分阶段处理时（如导入的预览与提交）标出本次请求所处的阶段。 */
    public static void stage(HttpServletRequest request, String stage) {
        request.setAttribute(STAGE, stage);
    }

    public static String route(HttpServletRequest request) {
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return route == null ? "UNMATCHED" : route.toString();
    }

    public static void completed(HttpServletRequest request, HttpServletResponse response, long started) {
        if (request.getRequestURI().startsWith("/actuator/")) return;
        long millis = (System.nanoTime() - started) / 1_000_000;
        int status = response.getStatus();
        String route = route(request);
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        boolean polling =
            route.equals("/api/v1/admin/operations/logs/tail") ||
            route.equals("/api/v1/admin/operations/alerts/summary");
        Level level =
            status >= 500 || status == 403 || millis >= 1000
                ? Level.WARN
                : status >= 400 || !read || (route.startsWith("/api/v1/admin/") && !polling)
                  ? Level.INFO
                  : Level.DEBUG;
        String event =
            status >= 500
                ? "http.request.failed"
                : millis >= 1000
                  ? "http.request.slow"
                  : status >= 400
                    ? "http.request.rejected"
                    : "http.request.completed";
        var log = LoggerFactory.getLogger(HttpAccessLog.class)
            .atLevel(level)
            .setMessage(event.replace('.', ' '))
            .addKeyValue(LogFields.EVENT, event)
            .addKeyValue(LogFields.METHOD, request.getMethod())
            .addKeyValue(LogFields.ROUTE, route)
            .addKeyValue(LogFields.STATUS, status)
            .addKeyValue(LogFields.DURATION_MS, millis);
        if (request.getAttribute(USER_ID) != null) log.addKeyValue("userId", request.getAttribute(USER_ID));
        if (request.getAttribute(CLIENT_TYPE) != null) log.addKeyValue("clientType", request.getAttribute(CLIENT_TYPE));
        if (request.getAttribute(ERROR_CODE) != null) log.addKeyValue("errorCode", request.getAttribute(ERROR_CODE));
        if (request.getAttribute(ERROR_REASON) != null) log.addKeyValue("reason", request.getAttribute(ERROR_REASON));
        if (request.getAttribute(FIELD_ERRORS) != null) log.addKeyValue("fieldErrors", request.getAttribute(FIELD_ERRORS));
        if (request.getAttribute(STAGE) != null) log.addKeyValue("stage", request.getAttribute(STAGE));
        log.log();
    }
}
