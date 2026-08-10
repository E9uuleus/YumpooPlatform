package com.yumpoo.platform.foundation.testing;

import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/**
 * 仅存在于测试 classpath 的 M0-10 HTTP 闭环，不进入生产 JAR 或 OpenAPI。
 */
@ApiV1Controller
public final class M010ProbeController {

    private static final String PROBE_PATH = "/__test/m0-10/probes";

    public static final UUID FIXED_ACTOR_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000010"
    );

    private final M010ProbeApplicationService applicationService;
    private final IdempotencyKeyParser idempotencyKeyParser;
    private final IdempotencyRequestHasher requestHasher;
    private final IfMatchParser ifMatchParser;
    private final ObjectMapper objectMapper;

    public M010ProbeController(
            M010ProbeApplicationService applicationService,
            IdempotencyKeyParser idempotencyKeyParser,
            IdempotencyRequestHasher requestHasher,
            IfMatchParser ifMatchParser,
            ObjectMapper objectMapper
    ) {
        this.applicationService = applicationService;
        this.idempotencyKeyParser = idempotencyKeyParser;
        this.requestHasher = requestHasher;
        this.ifMatchParser = ifMatchParser;
        this.objectMapper = objectMapper;
    }

    @PostMapping(PROBE_PATH)
    ResponseEntity<String> create(
            @RequestHeader(value = IdempotencyKeyParser.HEADER_NAME, required = false) String key,
            @Valid @RequestBody ProbeRequest request
    ) {
        UUID idempotencyKey = idempotencyKeyParser.parseRequired(key);
        IdempotencyExecutionResult execution = applicationService.create(
                FIXED_ACTOR_ID,
                idempotencyKey,
                requestHasher.hash(
                        M010ProbeApplicationService.CREATE_ROUTE_KEY,
                        Map.of(),
                        objectMapper.valueToTree(request)
                ),
                request.name()
        );
        return storedResponse(execution.result());
    }

    @PatchMapping(PROBE_PATH + "/{probeId}")
    ResponseEntity<String> update(
            @PathVariable UUID probeId,
            @RequestHeader(value = IfMatchParser.HEADER_NAME, required = false) String ifMatch,
            @Valid @RequestBody ProbeRequest request
    ) {
        boolean visible = applicationService.isVisible(FIXED_ACTOR_ID, probeId);
        long expectedVersion = ifMatchParser.parseForVisibleResource(visible, ifMatch);
        M010ProbeApplicationService.ProbeResponse response = applicationService.update(
                FIXED_ACTOR_ID,
                probeId,
                expectedVersion,
                request.name()
        );

        HttpHeaders headers = jsonHeaders();
        headers.setETag(IfMatchParser.format(response.rowVersion()));
        return new ResponseEntity<>(writeJson(response), headers, HttpStatus.OK);
    }

    private ResponseEntity<String> storedResponse(StoredCommandResult result) {
        HttpHeaders headers = jsonHeaders();
        if (result.etag() != null) {
            headers.setETag(result.etag());
        }
        return new ResponseEntity<>(
                result.responseJson(),
                headers,
                HttpStatusCode.valueOf(result.httpStatus())
        );
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("M0-10 probe response serialization failed", exception);
        }
    }

    record ProbeRequest(
            @NotBlank(message = "名称不能为空")
            @Size(max = 120, message = "名称最多 120 个字符")
            String name
    ) {
    }
}
