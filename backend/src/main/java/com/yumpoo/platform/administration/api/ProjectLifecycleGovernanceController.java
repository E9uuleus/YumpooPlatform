package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.administration.application.ProjectArchiveOperationCommand;
import com.yumpoo.platform.administration.application.ProjectLifecycleGovernanceService;
import com.yumpoo.platform.administration.application.ProjectRestoreOperationCommand;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

@ApiV1Controller
public final class ProjectLifecycleGovernanceController {
    private final CurrentActorProvider actors;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectLifecycleGovernanceService service;
    private final IfMatchParser ifMatch;
    private final IdempotencyKeyParser keys;
    private final IdempotencyRequestHasher hasher;
    private final ObjectMapper objectMapper;

    public ProjectLifecycleGovernanceController(CurrentActorProvider actors,
            ProjectAccessSnapshotQuery access, ProjectLifecycleGovernanceService service,
            IfMatchParser ifMatch, IdempotencyKeyParser keys,
            IdempotencyRequestHasher hasher, ObjectMapper objectMapper) {
        this.actors = actors; this.access = access; this.service = service; this.ifMatch = ifMatch;
        this.keys = keys; this.hasher = hasher; this.objectMapper = objectMapper;
    }

    @PostMapping("/projects/{projectId}/archive")
    ResponseEntity<String> archive(@PathVariable UUID projectId,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String keyHeader) {
        CurrentActor actor = visible(projectId);
        long version = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        UUID key = keys.parseRequired(keyHeader);
        StoredCommandResult result = service.archive(new ProjectArchiveOperationCommand(actor,
                projectId, version, key, hasher.hash("archiveProject",
                Map.of("projectId", projectId.toString(), "ifMatch", Long.toString(version)),
                objectMapper.createObjectNode()))).result();
        return stored(result);
    }

    @PostMapping("/projects/{projectId}/restore")
    ResponseEntity<String> restore(@PathVariable UUID projectId,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String keyHeader) {
        CurrentActor actor = visible(projectId);
        long version = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        UUID key = keys.parseRequired(keyHeader);
        StoredCommandResult result = service.restore(new ProjectRestoreOperationCommand(actor,
                projectId, version, key, hasher.hash("restoreProject",
                Map.of("projectId", projectId.toString(), "ifMatch", Long.toString(version)),
                objectMapper.createObjectNode()))).result();
        return stored(result);
    }

    @Deprecated(forRemoval = false)
    @PostMapping("/projects/{projectId}/workspace-moves")
    ResponseEntity<Void> legacyMove(@PathVariable UUID projectId,
            @Valid @RequestBody ProjectWorkspaceMoveRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String keyHeader) {
        CurrentActor actor = visible(projectId);
        if (!actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        ifMatch.parseForVisibleResource(true, ifMatchHeader);
        keys.parseRequired(keyHeader);
        throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
    }

    private CurrentActor visible(UUID projectId) {
        CurrentActor actor = actors.requiredActive();
        access.findVisible(actor, projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        return actor;
    }

    static ResponseEntity<String> stored(StoredCommandResult result) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore());
        if (result.etag() != null) headers.setETag(result.etag());
        return new ResponseEntity<>(result.responseJson(), headers, HttpStatus.valueOf(result.httpStatus()));
    }
}
