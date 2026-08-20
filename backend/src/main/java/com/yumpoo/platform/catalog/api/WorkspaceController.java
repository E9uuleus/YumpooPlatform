package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.workspace.WorkspaceCreateCommand;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceLifecycleCommand;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceListStatus;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceService;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceUpdateCommand;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceView;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@ApiV1Controller
public final class WorkspaceController {

    private static final String WORKSPACE_PATH = "/api/v1/workspaces/";

    private final CurrentActorProvider currentActorProvider;
    private final WorkspaceService service;
    private final IfMatchParser ifMatchParser;
    private final IdempotencyKeyParser idempotencyKeyParser;
    private final IdempotencyRequestHasher requestHasher;
    private final ObjectMapper objectMapper;

    public WorkspaceController(
            CurrentActorProvider currentActorProvider,
            WorkspaceService service,
            IfMatchParser ifMatchParser,
            IdempotencyKeyParser idempotencyKeyParser,
            IdempotencyRequestHasher requestHasher,
            ObjectMapper objectMapper
    ) {
        this.currentActorProvider = currentActorProvider;
        this.service = service;
        this.ifMatchParser = ifMatchParser;
        this.idempotencyKeyParser = idempotencyKeyParser;
        this.requestHasher = requestHasher;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/workspaces")
    ResponseEntity<WorkspaceListResponse> list(
            @RequestParam(defaultValue = "ACTIVE") WorkspaceListStatus status
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new WorkspaceListResponse(service.findAll(actor, status)));
    }

    @GetMapping("/workspaces/{workspaceId}")
    ResponseEntity<WorkspaceView> detail(@PathVariable UUID workspaceId) {
        CurrentActor actor = currentActorProvider.requiredActive();
        WorkspaceView workspace = service.findVisible(actor, workspaceId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(Long.toString(workspace.rowVersion()))
                .body(workspace);
    }

    @PostMapping("/workspaces")
    ResponseEntity<String> create(
            @Valid @RequestBody WorkspaceCreateRequest body,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        UUID idempotencyKey = idempotencyKeyParser.parseRequired(idempotencyHeader);
        WorkspaceCreateCommand command = new WorkspaceCreateCommand(
                actor,
                body.code(),
                body.name(),
                body.description(),
                body.sortOrder(),
                idempotencyKey,
                requestHasher.hash("createWorkspace", Map.of(), objectMapper.valueToTree(body))
        );
        return stored(service.create(command).result(), true);
    }

    @PatchMapping("/workspaces/{workspaceId}")
    ResponseEntity<WorkspaceView> update(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody WorkspaceUpdateRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        service.findForAdministration(actor, workspaceId);
        long expectedVersion = ifMatchParser.parseForVisibleResource(true, ifMatch);
        WorkspaceView workspace = service.update(new WorkspaceUpdateCommand(
                actor, workspaceId, expectedVersion,
                body.name(), body.description(), body.sortOrder()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(Long.toString(workspace.rowVersion()))
                .body(workspace);
    }

    @PostMapping("/workspaces/{workspaceId}/archive")
    ResponseEntity<String> archive(
            @PathVariable UUID workspaceId,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader
    ) {
        return lifecycle(true, workspaceId, ifMatch, idempotencyHeader);
    }

    @PostMapping("/workspaces/{workspaceId}/restore")
    ResponseEntity<String> restore(
            @PathVariable UUID workspaceId,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader
    ) {
        return lifecycle(false, workspaceId, ifMatch, idempotencyHeader);
    }

    private ResponseEntity<String> lifecycle(
            boolean archive,
            UUID workspaceId,
            String ifMatch,
            String idempotencyHeader
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        service.findForAdministration(actor, workspaceId);
        long expectedVersion = ifMatchParser.parseForVisibleResource(true, ifMatch);
        UUID idempotencyKey = idempotencyKeyParser.parseRequired(idempotencyHeader);
        String operation = archive ? "archiveWorkspace" : "restoreWorkspace";
        WorkspaceLifecycleCommand command = new WorkspaceLifecycleCommand(
                actor,
                workspaceId,
                expectedVersion,
                idempotencyKey,
                requestHasher.hash(
                        operation,
                        Map.of(
                                "workspaceId", workspaceId.toString(),
                                "ifMatch", Long.toString(expectedVersion)),
                        objectMapper.createObjectNode())
        );
        StoredCommandResult result = (archive ? service.archive(command) : service.restore(command)).result();
        return stored(result, false);
    }

    private static ResponseEntity<String> stored(StoredCommandResult stored, boolean includeLocation) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore());
        headers.setETag(stored.etag());
        if (includeLocation) {
            headers.setLocation(URI.create(WORKSPACE_PATH + stored.resourceId()));
        }
        return new ResponseEntity<>(
                stored.responseJson(), headers, HttpStatus.valueOf(stored.httpStatus()));
    }
}
