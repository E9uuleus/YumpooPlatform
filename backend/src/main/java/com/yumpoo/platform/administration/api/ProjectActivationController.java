package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.administration.application.ProjectActivationCommand;
import com.yumpoo.platform.administration.application.ProjectActivationOrchestrator;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

@ApiV1Controller
public final class ProjectActivationController {

    private final CurrentActorProvider actorProvider;
    private final ProjectAccessSnapshotQuery accessQuery;
    private final ProjectActivationOrchestrator orchestrator;
    private final IfMatchParser ifMatchParser;
    private final IdempotencyKeyParser idempotencyKeyParser;
    private final IdempotencyRequestHasher requestHasher;
    private final ObjectMapper objectMapper;

    public ProjectActivationController(CurrentActorProvider actorProvider,
            ProjectAccessSnapshotQuery accessQuery, ProjectActivationOrchestrator orchestrator,
            IfMatchParser ifMatchParser, IdempotencyKeyParser idempotencyKeyParser,
            IdempotencyRequestHasher requestHasher, ObjectMapper objectMapper) {
        this.actorProvider = actorProvider;
        this.accessQuery = accessQuery;
        this.orchestrator = orchestrator;
        this.ifMatchParser = ifMatchParser;
        this.idempotencyKeyParser = idempotencyKeyParser;
        this.requestHasher = requestHasher;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/projects/{projectId}/activate")
    ResponseEntity<String> activate(@PathVariable UUID projectId,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String keyHeader) {
        CurrentActor actor = actorProvider.requiredActive();
        accessQuery.findVisible(actor, projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        long version = ifMatchParser.parseForVisibleResource(true, ifMatch);
        UUID key = idempotencyKeyParser.parseRequired(keyHeader);
        ProjectActivationCommand command = new ProjectActivationCommand(actor, projectId, version,
                key, requestHasher.hash("activateProject",
                Map.of("projectId", projectId.toString(), "ifMatch", Long.toString(version)),
                objectMapper.createObjectNode()), null, null);
        StoredCommandResult result = orchestrator.activate(command).result();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore());
        headers.setETag(result.etag());
        return new ResponseEntity<>(result.responseJson(), headers,
                HttpStatus.valueOf(result.httpStatus()));
    }
}
