package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.administration.application.ProjectCreationCommand;
import com.yumpoo.platform.administration.application.ProjectCreationOrchestrator;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@ApiV1Controller
public final class ProjectCreationController {

    private static final String PROJECT_PATH = "/api/v1/projects/";

    private final CurrentActorProvider currentActorProvider;
    private final ProjectCreationOrchestrator orchestrator;
    private final IdempotencyKeyParser idempotencyKeyParser;
    private final IdempotencyRequestHasher requestHasher;
    private final ObjectMapper objectMapper;

    public ProjectCreationController(
            CurrentActorProvider currentActorProvider,
            ProjectCreationOrchestrator orchestrator,
            IdempotencyKeyParser idempotencyKeyParser,
            IdempotencyRequestHasher requestHasher,
            ObjectMapper objectMapper
    ) {
        this.currentActorProvider = currentActorProvider;
        this.orchestrator = orchestrator;
        this.idempotencyKeyParser = idempotencyKeyParser;
        this.requestHasher = requestHasher;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/projects")
    ResponseEntity<String> create(
            @Valid @RequestBody ProjectCreateRequest body,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        UUID idempotencyKey = idempotencyKeyParser.parseRequired(idempotencyHeader);
        ProjectCreationCommand command = new ProjectCreationCommand(
                actor, body.code(), body.name(), body.description(),
                body.projectType(), body.ownerUserId(), body.templateKey(), body.templateVersion(),
                body.customerName(), body.customerReference(), body.deliverySite(),
                body.contactNote(), idempotencyKey,
                requestHasher.hash("createProject", Map.of(), objectMapper.valueToTree(body)),
                null, null);
        return stored(orchestrator.create(command).result());
    }

    private static ResponseEntity<String> stored(StoredCommandResult stored) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore());
        headers.setETag(stored.etag());
        headers.setLocation(URI.create(PROJECT_PATH + stored.resourceId()));
        return new ResponseEntity<>(stored.responseJson(), headers,
                HttpStatus.valueOf(stored.httpStatus()));
    }
}
