package com.yumpoo.platform.foundation.api.web;

import com.yumpoo.platform.foundation.application.logging.LogFields;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.web.servlet.HandlerMapping;

public final class HttpAccessLog {

    public static final String USER_ID = HttpAccessLog.class.getName() + ".userId";
    public static final String CLIENT_TYPE = HttpAccessLog.class.getName() + ".clientType";
    public static final String ERROR_LOGGED = HttpAccessLog.class.getName() + ".errorLogged";

    private HttpAccessLog() {}

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
            status >= 500 || millis >= 1000
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
        log.log();
    }
}
