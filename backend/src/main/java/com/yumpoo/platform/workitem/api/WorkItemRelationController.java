package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.workitem.application.WorkItemRelationCommands.ChangeParent;
import com.yumpoo.platform.workitem.application.WorkItemRelationCommands.Create;
import com.yumpoo.platform.workitem.application.WorkItemRelationCommands.Delete;
import com.yumpoo.platform.workitem.application.WorkItemRelationModels.CandidatePage;
import com.yumpoo.platform.workitem.application.WorkItemRelationModels.RelationPage;
import com.yumpoo.platform.workitem.application.WorkItemRelationService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
public final class WorkItemRelationController {
    private final CurrentActorProvider actors;
    private final WorkItemRelationService service;
    private final IfMatchParser ifMatch;
    private final IdempotencyKeyParser keys;
    private final IdempotencyRequestHasher hasher;
    private final ObjectMapper objectMapper;

    public WorkItemRelationController(CurrentActorProvider actors,
            WorkItemRelationService service, IfMatchParser ifMatch,
            IdempotencyKeyParser keys, IdempotencyRequestHasher hasher,
            ObjectMapper objectMapper) {
        this.actors = actors;
        this.service = service;
        this.ifMatch = ifMatch;
        this.keys = keys;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/work-items/{workItemId}/relations")
    ResponseEntity<RelationPage> list(@PathVariable UUID workItemId,
            @RequestParam(required = false) String relationType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(actors.requiredActive(), workItemId, relationType,
                        OffsetPageRequest.of(page, size)));
    }

    @GetMapping("/work-items/{workItemId}/relation-candidates")
    ResponseEntity<CandidatePage> candidates(@PathVariable UUID workItemId,
            @RequestParam String relationType,
            @RequestParam String currentRole,
            @RequestParam(required = false) UUID targetProjectId,
            @RequestParam String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.candidates(actors.requiredActive(), workItemId,
                        relationType, currentRole, targetProjectId, q,
                        OffsetPageRequest.of(page, size)));
    }

    @PostMapping("/work-items/{workItemId}/relations")
    ResponseEntity<String> create(@PathVariable UUID workItemId,
            @Valid @RequestBody WorkItemRelationCreateRequest body,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        CurrentActor actor = actors.requiredActive();
        UUID key = keys.parseRequired(idempotencyHeader);
        StoredCommandResult result = service.create(new Create(actor, workItemId,
                body.relationType(), body.currentRole(),
                body.targetProjectId(), body.targetWorkItemId(), key,
                hasher.hash("createWorkItemRelation", Map.of(
                                "workItemId", workItemId.toString()),
                        objectMapper.valueToTree(body)))).result();
        return stored(result, URI.create("/api/v1/work-item-relations/" + result.resourceId()));
    }

    @PostMapping("/work-item-relations/{relationId}/parent-changes")
    ResponseEntity<String> changeParent(@PathVariable UUID relationId,
            @Valid @RequestBody WorkItemParentChangeRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false)
            String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        CurrentActor actor = actors.requiredActive();
        service.find(actor, relationId);
        long expectedVersion = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        UUID key = keys.parseRequired(idempotencyHeader);
        StoredCommandResult result = service.changeParent(new ChangeParent(actor, relationId,
                expectedVersion, body.newParentWorkItemId(), body.reason().strip(), key,
                hasher.hash("changeWorkItemParent", Map.of(
                                "relationId", relationId.toString(),
                                "ifMatch", Long.toString(expectedVersion)),
                        objectMapper.valueToTree(body)))).result();
        return stored(result, URI.create("/api/v1/work-item-relations/" + result.resourceId()));
    }

    @DeleteMapping("/work-item-relations/{relationId}")
    ResponseEntity<String> delete(@PathVariable UUID relationId,
            @Valid @RequestBody WorkItemRelationDeleteRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false)
            String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        CurrentActor actor = actors.requiredActive();
        service.find(actor, relationId);
        long expectedVersion = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        UUID key = keys.parseRequired(idempotencyHeader);
        StoredCommandResult result = service.delete(new Delete(actor, relationId,
                expectedVersion, body.reason().strip(), key,
                hasher.hash("deleteWorkItemRelation", Map.of(
                                "relationId", relationId.toString(),
                                "ifMatch", Long.toString(expectedVersion)),
                        objectMapper.valueToTree(body)))).result();
        return stored(result, null);
    }

    private static ResponseEntity<String> stored(StoredCommandResult result, URI location) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore());
        headers.setETag(result.etag());
        if (location != null) headers.setLocation(location);
        return new ResponseEntity<>(result.responseJson(), headers,
                HttpStatus.valueOf(result.httpStatus()));
    }
}
