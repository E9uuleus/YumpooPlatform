package com.yumpoo.platform.identityaccess.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class SessionHttpCookies {

    static final String SESSION_COOKIE = "__Host-yumpoo-session";
    static final String CSRF_COOKIE = "__Host-yumpoo-csrf";

    private SessionHttpCookies() {
    }

    static Optional<String> single(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        List<String> values = new ArrayList<>();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                values.add(cookie.getValue());
            }
        }
        if (values.size() > 1) {
            throw new IllegalArgumentException("duplicate security cookie");
        }
        return values.stream().findFirst();
    }

    /**
     * 勾选「7 天内免登录」时 Cookie 存活到会话绝对到期；不勾选时不带 Max-Age，关闭浏览器即失效。
     */
    static Duration maxAge(boolean rememberMe, Instant absoluteExpiresAt, Instant now) {
        if (!rememberMe) {
            return Duration.ofSeconds(-1);
        }
        Duration remaining = Duration.between(now, absoluteExpiresAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    static ResponseCookie session(String value, Duration maxAge) {
        return base(SESSION_COOKIE, value, maxAge).httpOnly(true).build();
    }

    static ResponseCookie csrf(String value, Duration maxAge) {
        return base(CSRF_COOKIE, value, maxAge).httpOnly(false).build();
    }

    static ResponseCookie clearSession() {
        return base(SESSION_COOKIE, "", Duration.ZERO).httpOnly(true).build();
    }

    static ResponseCookie clearCsrf() {
        return base(CSRF_COOKIE, "", Duration.ZERO).httpOnly(false).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(
            String name,
            String value,
            Duration maxAge
    ) {
        return ResponseCookie.from(name, value)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge);
    }
}
