package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.administration.application.ProjectDeletionOperation;
import com.yumpoo.platform.administration.application.ProjectDeletionOperations;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

@ApiV1Controller
public class ProjectDeletionController {
    record Request(@NotBlank @Size(min = 2, max = 32) String confirmationCode) {}
    private final CurrentActorProvider actors;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectDeletionOperations operations;
    private final IfMatchParser ifMatch;
    private final IdempotencyKeyParser keys;
    private final IdempotencyRequestHasher hasher;
    private final ObjectMapper json;
    public ProjectDeletionController(CurrentActorProvider actors, ProjectAccessSnapshotQuery access,
            ProjectDeletionOperations operations, IfMatchParser ifMatch, IdempotencyKeyParser keys,
            IdempotencyRequestHasher hasher, ObjectMapper json) {
        this.actors = actors; this.access = access; this.operations = operations; this.ifMatch = ifMatch;
        this.keys = keys; this.hasher = hasher; this.json = json;
    }
    @PostMapping("/projects/{projectId}/deletion")
    ResponseEntity<String> schedule(@PathVariable UUID projectId, @Valid @RequestBody Request request,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String match,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String key) {
        return ProjectLifecycleGovernanceController.stored(operations.schedule(command(projectId, match, key, request)));
    }
    @DeleteMapping("/projects/{projectId}/deletion")
    ResponseEntity<String> cancel(@PathVariable UUID projectId,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String match,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String key) {
        return ProjectLifecycleGovernanceController.stored(operations.cancel(command(projectId, match, key, null)));
    }
    private ProjectDeletionOperation command(UUID projectId, String match, String key, Request body) {
        CurrentActor actor = actors.requiredActive();
        access.findVisible(actor, projectId).orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        long version = ifMatch.parseForVisibleResource(true, match);
        UUID idempotencyKey = keys.parseRequired(key);
        return new ProjectDeletionOperation(actor, projectId, version, idempotencyKey,
                hasher.hash(body == null ? "cancelProjectDeletion" : "scheduleProjectDeletion",
                    Map.of("projectId", projectId.toString(), "ifMatch", Long.toString(version)),
                    body == null ? json.createObjectNode() : json.valueToTree(body)),
                body == null ? null : body.confirmationCode());
    }
}
