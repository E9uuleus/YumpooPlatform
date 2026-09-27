package com.yumpoo.platform.foundation.infrastructure.logging;

import java.util.List;
import java.util.regex.Pattern;

public final class LogSanitizer {
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|secret|token|cookie|credential|session.?key|csrf|authorization|payload|body|description|title).*" );
    private static final List<Replacement> RULES = List.of(
            new Replacement("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@]+@", "$1***@"),
            new Replacement("(?i)(https?://[^\\s?#]+)[?#][^\\s]*", "$1?[redacted]"),
            new Replacement("(?im)(\\b(?:set-cookie|cookie|authorization)\\s*[:=]\\s*)[^\\r\\n]+", "$1***"),
            new Replacement("(?i)(\\b(?:token|sessionId|session_id|payload)\\s*[:=]\\s*)[^\\s,;]+", "$1***"),
            new Replacement("(?i)Bearer\\s+[^\\s,;\\\"']+", "Bearer ***"),
            new Replacement("(?i)((?:password|passwd|(?:app[-_]?)?secret|(?:access|refresh)[-_]?token|session[-_]?key|cookie|set-cookie|x-xsrf-token|csrf|authorization)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)", "$1***"),
            new Replacement("(?i)([?&](?:code|state|ticket)=)[^&#\\s]*", "$1***"),
            new Replacement("[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})", "***@$1"),
            new Replacement("(?<![0-9])1[3-9][0-9]{9}(?![0-9])", "[phone]"),
            new Replacement("(?is)SQL \\[.*?\\]", "SQL [redacted]"),
            new Replacement("(?im)^\\s*(?:Detail|Where):.*$", "[database detail removed]"),
            new Replacement("(?is)bad SQL grammar \\[.*?\\]", "SQL [redacted]"),
            new Replacement("(?<![A-Za-z0-9:])/(?:home|Users|var|opt|tmp|etc)/[^\\s\\\"<>]*", "[path]"),
            new Replacement("(?i)(?<![A-Za-z])[A-Z]:[\\\\/][^\\s\\\"<>]*", "[path]"),
            new Replacement("(?<![A-Za-z0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![A-Za-z0-9])", "[address]")
    );
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    private LogSanitizer() { }

    public static String field(String key, String value) {
        return SENSITIVE_KEY.matcher(key).matches() ? "***" : text(value, 2048);
    }

    public static boolean sensitive(String key) { return SENSITIVE_KEY.matcher(key).matches(); }

    public static String text(String value, int maximum) {
        if (value == null) return "";
        try {
            // Bound regex work before processing untrusted library messages.
            String safe = value.length() > 32768 ? value.substring(0, 32768) + "…[truncated]" : value;
            for (Replacement rule : RULES) safe = rule.pattern.matcher(safe).replaceAll(rule.replacement);
            safe = CONTROL_CHARACTERS.matcher(safe).replaceAll("?");
            return safe.length() > maximum ? safe.substring(0, maximum) + "…[truncated]" : safe;
        } catch (RuntimeException exception) {
            return "[unsanitizable:" + value.length() + " chars]";
        }
    }

    private record Replacement(Pattern pattern, String replacement) {
        Replacement(String pattern, String replacement) { this(Pattern.compile(pattern), replacement); }
    }
}
