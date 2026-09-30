package com.yumpoo.platform.foundation.infrastructure.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

public final class YumpooConsoleLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(
        ZoneId.systemDefault()
    );
    private final LogRecordMapper mapper = new LogRecordMapper();

    @Override
    public String format(ILoggingEvent event) {
        var r = mapper.map(event);
        String logger = r.logger().substring(r.logger().lastIndexOf('.') + 1);
        StringBuilder text = new StringBuilder(
            String.format(
                "%s %-5s %-12.12s %-24.24s %s",
                TIME.format(r.time()),
                r.level(),
                r.module(),
                logger,
                r.msg().replace("\r", "\\r").replace("\n", "\\n")
            )
        );
        if (r.event() != null) text.append(" event=").append(r.event());
        r.fields().forEach((k, v) ->
            text.append(' ').append(k).append('=').append(v.toString().replace("\n", "\\n").replace("\r", "\\r"))
        );
        if (r.userId() != null) text.append(" user=").append(shortId(r.userId()));
        if (r.requestId() != null) text.append(" req=").append(shortId(r.requestId()));
        text.append('\n');
        if (r.error() != null) text.append(r.error().stack()).append('\n');
        return text.toString();
    }

    private static String shortId(String value) {
        return value.substring(0, Math.min(value.length(), 8));
    }
}
