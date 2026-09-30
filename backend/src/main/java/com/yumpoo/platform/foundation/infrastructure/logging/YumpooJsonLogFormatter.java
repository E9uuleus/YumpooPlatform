package com.yumpoo.platform.foundation.infrastructure.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.Map;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

public final class YumpooJsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private final LogRecordMapper mapper = new LogRecordMapper();
    private final JsonWriter<Map<String, Object>> writer = JsonWriter.<
        Map<String, Object>
    >standard().withNewLineAtEnd();

    @Override
    public String format(ILoggingEvent event) {
        return writer.writeToString(LogRecordMapper.json(mapper.map(event)));
    }
}
