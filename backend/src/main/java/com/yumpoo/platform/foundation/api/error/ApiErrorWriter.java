package com.yumpoo.platform.foundation.api.error;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 供 MVC 之外的过滤器或认证入口复用同一错误 JSON 形状。
 */
@Component
public final class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(
            HttpServletResponse response,
            HttpStatusCode status,
            ApiErrorResponse body
    ) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiErrorHeaders.apply(response, body);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    public void write(
            HttpServletResponse response,
            ApplicationException exception,
            String requestId
    ) throws IOException {
        write(
                response,
                ApiErrorStatus.forCode(exception.errorCode()),
                ApiErrorResponses.from(exception, requestId)
        );
    }
}
