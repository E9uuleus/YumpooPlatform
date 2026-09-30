package com.yumpoo.platform.foundation.infrastructure.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import com.yumpoo.platform.foundation.application.logging.LogRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.event.KeyValuePair;

public final class LogRecordMapper {

    private static final java.util.regex.Pattern INVALID_KEY = java.util.regex.Pattern.compile("[^a-zA-Z0-9_]");
    static final java.util.regex.Pattern IDENTIFIER = java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Set<String> RESERVED = Set.of(
        "schema",
        "time",
        "level",
        "module",
        "msg",
        "logger",
        "thread",
        "error"
    );

    public LogRecord map(ILoggingEvent source) {
        Map<String, Object> context = new LinkedHashMap<>();
        source.getMDCPropertyMap().forEach((key, value) -> put(context, key, value));
        if (source.getKeyValuePairs() != null) for (KeyValuePair pair : source.getKeyValuePairs())
            put(context, pair.key, pair.value);
        String event = identifier(take(context, "event"));
        String requestId = identifier(take(context, "requestId"));
        String correlationId = identifier(take(context, "correlationId"));
        String userId = identifier(take(context, "userId"));
        if (Objects.equals(requestId, correlationId)) correlationId = null;
        return new LogRecord(
            source.getInstant().truncatedTo(ChronoUnit.MILLIS),
            source.getLevel().toString(),
            module(source.getLoggerName()),
            event,
            LogSanitizer.text(source.getFormattedMessage(), 2048),
            LogSanitizer.text(source.getLoggerName(), 256),
            LogSanitizer.text(source.getThreadName(), 128),
            requestId,
            correlationId,
            userId,
            context,
            error(source.getThrowableProxy())
        );
    }

    public static Map<String, Object> json(LogRecord record) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema", "yumpoo-log/1");
        value.put("time", TIME.format(record.time()));
        value.put("level", record.level());
        value.put("module", record.module());
        optional(value, "event", record.event());
        value.put("msg", record.msg());
        value.put("logger", record.logger());
        value.put("thread", record.thread());
        optional(value, "requestId", record.requestId());
        optional(value, "correlationId", record.correlationId());
        optional(value, "userId", record.userId());
        value.putAll(record.fields());
        if (record.error() != null) value.put(
            "error",
            Map.of(
                "type",
                record.error().type(),
                "msg",
                record.error().msg(),
                "stack",
                record.error().stack(),
                "hash",
                record.error().hash()
            )
        );
        return value;
    }

    public static String module(String logger) {
        String prefix = "com.yumpoo.platform.";
        if (logger.startsWith(prefix)) {
            int end = logger.indexOf('.', prefix.length());
            return logger.substring(prefix.length(), end < 0 ? logger.length() : end);
        }
        if (logger.startsWith("org.springframework")) return "spring";
        if (logger.startsWith("com.zaxxer.hikari")) return "hikari";
        if (logger.startsWith("org.flywaydb")) return "flyway";
        if (logger.startsWith("org.postgresql")) return "postgres";
        if (
            logger.startsWith("org.apache.catalina") ||
            logger.startsWith("org.apache.tomcat") ||
            logger.startsWith("org.apache.coyote")
        ) return "tomcat";
        return "other";
    }

    static void put(Map<String, Object> fields, String key, Object value) {
        if (value == null || key == null || fields.size() >= 32) return;
        String name = INVALID_KEY.matcher(key).replaceAll("_");
        if (name.length() > 64) name = name.substring(0, 64);
        if (RESERVED.contains(name)) name = "ctx_" + name;
        if (!name.equals(key)) {
            String base = name;
            for (int suffix = 2; fields.containsKey(name); suffix++) name = base + "_" + suffix;
        }
        Object safe = LogSanitizer.sensitive(name)
            ? "***"
            : value instanceof Number number && Double.isFinite(number.doubleValue())
              ? number
              : value instanceof Boolean
                ? value
                : value instanceof Enum<?> enumeration
                  ? enumeration.name()
                  : value instanceof CharSequence || value instanceof UUID
                    ? LogSanitizer.field(name, value.toString())
                    : "[unsupported value]";
        int used = fields
            .entrySet()
            .stream()
            .mapToInt(e -> e.getKey().length() + e.getValue().toString().length())
            .sum();
        if (used + name.length() + safe.toString().length() <= 8192) fields.put(name, safe);
    }

    private static String take(Map<String, Object> fields, String key) {
        Object value = fields.remove(key);
        return value == null ? null : value.toString();
    }

    private static String identifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches() ? value : null;
    }

    private static void optional(Map<String, Object> value, String key, String entry) {
        if (entry != null) value.put(key, entry);
    }

    private static LogRecord.LogError error(IThrowableProxy throwable) {
        if (throwable == null) return null;
        List<IThrowableProxy> chain = new ArrayList<>();
        Set<IThrowableProxy> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (
            IThrowableProxy part = throwable;
            part != null && visited.add(part) && chain.size() < 12;
            part = part.getCause()
        ) chain.add(part);
        Collections.reverse(chain);
        StringBuilder stack = new StringBuilder();
        StringBuilder frames = new StringBuilder();
        for (IThrowableProxy part : chain) {
            if (!stack.isEmpty()) stack.append("Wrapped by: ");
            stack
                .append(part.getClassName())
                .append(": ")
                .append(LogSanitizer.text(part.getMessage(), 2048))
                .append('\n');
            frames.append(part.getClassName()).append('\n');
            int count = 0;
            if (part.getStackTraceElementProxyArray() != null) for (var proxy : part.getStackTraceElementProxyArray()) {
                var frame = proxy.getStackTraceElement();
                frames.append(frame).append('\n');
                if (count >= 40) continue;
                String name = frame.getClassName();
                if (
                    name.startsWith("org.apache.") ||
                    name.startsWith("org.springframework.web.filter.") ||
                    name.startsWith("org.springframework.security.web.") ||
                    name.startsWith("jdk.internal.reflect.")
                ) continue;
                stack.append("  at ").append(frame).append('\n');
                count++;
            }
        }
        String hash;
        try {
            hash = HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256").digest(frames.toString().getBytes(StandardCharsets.UTF_8))
                )
                .substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        return new LogRecord.LogError(
            throwable.getClassName(),
            LogSanitizer.text(throwable.getMessage(), 2048),
            LogSanitizer.text(stack.toString(), 8192),
            hash
        );
    }
}
