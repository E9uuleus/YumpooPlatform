package com.yumpoo.platform.foundation.testing;

import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

@ApiV1Controller
public final class M011ProbeController {

    private static final String PROBE_PATH = "/__test/m0-11/probes";

    public static final UUID FIXED_ACTOR_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000110"
    );

    private final M011ProbeApplicationService applicationService;
    private final IdempotencyKeyParser idempotencyKeyParser;
    private final IdempotencyRequestHasher requestHasher;
    private final ObjectMapper objectMapper;

    public M011ProbeController(
            M011ProbeApplicationService applicationService,
            IdempotencyKeyParser idempotencyKeyParser,
            IdempotencyRequestHasher requestHasher,
            ObjectMapper objectMapper
    ) {
        this.applicationService = applicationService;
        this.idempotencyKeyParser = idempotencyKeyParser;
        this.requestHasher = requestHasher;
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
                        M011ProbeApplicationService.CREATE_ROUTE_KEY,
                        Map.of(),
                        objectMapper.valueToTree(request)
                ),
                request.name()
        );
        return storedResponse(execution.result());
    }

    private static ResponseEntity<String> storedResponse(StoredCommandResult result) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (result.etag() != null) {
            headers.setETag(result.etag());
        }
        return new ResponseEntity<>(
                result.responseJson(),
                headers,
                HttpStatusCode.valueOf(result.httpStatus())
        );
    }

    record ProbeRequest(
            @NotBlank(message = "名称不能为空")
            @Size(max = 120, message = "名称最多 120 个字符")
            String name
    ) {
    }
}
