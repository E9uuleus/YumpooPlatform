package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.workitem.application.WorkItemCommands.Create;
import com.yumpoo.platform.workitem.application.WorkItemCommands.Update;
import com.yumpoo.platform.workitem.application.WorkItemModels.WorkItemDetail;
import com.yumpoo.platform.workitem.application.WorkItemModels.WorkItemPage;
import com.yumpoo.platform.workitem.application.WorkItemService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApiV1Controller
public final class WorkItemController {
    private final CurrentActorProvider actors;
    private final WorkItemService service;
    private final IfMatchParser ifMatch;
    private final IdempotencyKeyParser keys;
    private final IdempotencyRequestHasher hasher;
    private final ObjectMapper objectMapper;

    public WorkItemController(CurrentActorProvider actors, WorkItemService service,
            IfMatchParser ifMatch, IdempotencyKeyParser keys,
            IdempotencyRequestHasher hasher, ObjectMapper objectMapper) {
        this.actors = actors; this.service = service; this.ifMatch = ifMatch; this.keys = keys;
        this.hasher = hasher; this.objectMapper = objectMapper;
    }

    @GetMapping("/contents/{contentId}/work-items")
    ResponseEntity<WorkItemPage> list(@PathVariable UUID contentId,
            @RequestParam(name = "status", required = false) List<String> statuses,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(actors.requiredActive(), contentId, statuses,
                        OffsetPageRequest.of(page, size)));
    }

    @PostMapping("/contents/{contentId}/work-items")
    ResponseEntity<String> create(@PathVariable UUID contentId,
            @Valid @RequestBody WorkItemCreateRequest body,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        CurrentActor actor = actors.requiredActive();
        UUID key = keys.parseRequired(idempotencyHeader);
        StoredCommandResult stored = service.create(new Create(actor, contentId, body.title(),
                body.priority(), body.assigneeUserId(), body.description(), body.notes(),
                body.timelineStartDate(), body.timelineEndDate(), body.dueDate(), key,
                hasher.hash("createWorkItem", Map.of("contentId", contentId.toString()),
                        objectMapper.valueToTree(body)))).result();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore());
        headers.setETag(stored.etag());
        headers.setLocation(URI.create("/api/v1/work-items/" + stored.resourceId()));
        return new ResponseEntity<>(stored.responseJson(), headers, HttpStatus.CREATED);
    }

    @GetMapping("/work-items/{workItemId}")
    ResponseEntity<WorkItemDetail> detail(@PathVariable UUID workItemId) {
        WorkItemDetail detail = service.find(actors.requiredActive(), workItemId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(Long.toString(detail.rowVersion())).body(detail);
    }

    @PatchMapping("/work-items/{workItemId}")
    ResponseEntity<WorkItemDetail> update(@PathVariable UUID workItemId,
            @Valid @RequestBody WorkItemUpdateRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false)
            String ifMatchHeader) {
        CurrentActor actor = actors.requiredActive();
        service.find(actor, workItemId);
        long expectedVersion = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        WorkItemDetail detail = service.update(new Update(actor, workItemId, expectedVersion,
                body.title(), body.priority(), body.assigneeUserId(), body.description(),
                body.notes(), body.timelineStartDate(), body.timelineEndDate(), body.dueDate()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(Long.toString(detail.rowVersion())).body(detail);
    }
}
