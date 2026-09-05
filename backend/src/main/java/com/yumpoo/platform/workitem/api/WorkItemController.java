package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.api.pagination.CursorPageRequest;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.workitem.application.DueTimeChange;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.workitem.application.WorkItemCommands.Create;
import com.yumpoo.platform.workitem.application.WorkItemCommands.CreateSubitem;
import com.yumpoo.platform.workitem.application.WorkItemCommands.ChangeContent;
import com.yumpoo.platform.workitem.application.WorkItemCommands.Delete;
import com.yumpoo.platform.workitem.application.WorkItemCommands.RankMove;
import com.yumpoo.platform.workitem.application.WorkItemCommands.ProjectOrderMove;
import com.yumpoo.platform.workitem.application.WorkItemCommands.SubitemOrderMove;
import com.yumpoo.platform.workitem.application.WorkItemCommands.InlineUpdate;
import com.yumpoo.platform.workitem.application.WorkItemCommands.Restore;
import com.yumpoo.platform.workitem.application.WorkItemCommands.Transition;
import com.yumpoo.platform.workitem.application.WorkItemCommands.Update;
import com.yumpoo.platform.workitem.application.WorkItemModels.WorkItemDetail;
import com.yumpoo.platform.workitem.application.WorkItemModels.WorkItemPage;
import com.yumpoo.platform.workitem.application.WorkItemModels.ProjectWorkItemCursorPage;
import com.yumpoo.platform.workitem.application.WorkItemModels.ProjectWorkItemFilterOptionCursorPage;
import com.yumpoo.platform.workitem.application.WorkItemModels.WorkItemSubitemList;
import com.yumpoo.platform.workitem.application.WorkItemQuery;
import com.yumpoo.platform.workitem.application.WorkItemService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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

    @GetMapping("/projects/{projectId}/work-items")
    ResponseEntity<ProjectWorkItemCursorPage> listProject(@PathVariable UUID projectId,
            @RequestParam(required = false) String q,
            @RequestParam(name = "status", required = false) List<String> statuses,
            @RequestParam(name = "priority", required = false) List<String> priorities,
            @RequestParam(name = "assigneeUserId", required = false) List<UUID> assigneeUserIds,
            @RequestParam(name = "contentId", required = false) List<UUID> contentIds,
            @RequestParam(required = false) LocalDate dueFrom,
            @RequestParam(required = false) LocalDate dueTo,
            @RequestParam(required = false) Instant updatedAfter,
            @RequestParam(required = false) String view,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest httpRequest) {
        String[] sorts = httpRequest.getParameterValues("sort");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.listProject(actors.requiredActive(), projectId,
                        new WorkItemQuery.Request(q, statuses, priorities, assigneeUserIds, contentIds,
                                dueFrom, dueTo, updatedAfter, sorts == null ? null : List.of(sorts)),
                        view, CursorPageRequest.of(cursor, limit)));
    }

    @GetMapping("/projects/{projectId}/work-items/filter-options")
    ResponseEntity<ProjectWorkItemFilterOptionCursorPage> listProjectFilterOptions(
            @PathVariable UUID projectId, @RequestParam String field,
            @RequestParam(required = false) String q,
            @RequestParam(name = "status", required = false) List<String> statuses,
            @RequestParam(name = "priority", required = false) List<String> priorities,
            @RequestParam(name = "assigneeUserId", required = false) List<UUID> assigneeUserIds,
            @RequestParam(name = "contentId", required = false) List<UUID> contentIds,
            @RequestParam(required = false) LocalDate dueFrom,
            @RequestParam(required = false) LocalDate dueTo,
            @RequestParam(required = false) Instant updatedAfter,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest httpRequest) {
        String[] sorts = httpRequest.getParameterValues("sort");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.listProjectFilterOptions(actors.requiredActive(), projectId, field,
                        new WorkItemQuery.Request(q, statuses, priorities, assigneeUserIds,
                                contentIds, dueFrom, dueTo, updatedAfter,
                                sorts == null ? null : List.of(sorts)),
                        CursorPageRequest.of(cursor, limit)));
    }

    @PostMapping("/projects/{projectId}/work-items")
    ResponseEntity<String> create(@PathVariable UUID projectId,
            @Valid @RequestBody WorkItemCreateRequest body,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        CurrentActor actor = actors.requiredActive();
        UUID key = keys.parseRequired(idempotencyHeader);
        StoredCommandResult stored = service.create(new Create(actor, projectId, body.contentId(), body.title(),
                body.priority(), body.assigneeUserId(), body.description(), body.notes(),
                body.timelineStartDate(), body.timelineEndDate(), body.dueDate(), key,
                hasher.hash("createWorkItem", Map.of("projectId", projectId.toString(),
                                "contentId", body.contentId().toString()),
                        objectMapper.valueToTree(body)), dueTimeChange(body.dueTime()))).result();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore());
        headers.setETag(stored.etag());
        headers.setLocation(URI.create("/api/v1/work-items/" + stored.resourceId()));
        return new ResponseEntity<>(stored.responseJson(), headers, HttpStatus.CREATED);
    }

    @GetMapping("/work-items/{parentWorkItemId}/subitems")
    ResponseEntity<WorkItemSubitemList> listSubitems(@PathVariable UUID parentWorkItemId,
            HttpServletRequest httpRequest) {
        String[] sorts = httpRequest.getParameterValues("sort");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.listSubitems(actors.requiredActive(), parentWorkItemId,
                        new WorkItemQuery.Request(null, null, null, null, null,
                                null, null, null, sorts == null ? null : List.of(sorts))));
    }

    @PostMapping("/work-items/{parentWorkItemId}/subitems")
    ResponseEntity<String> createSubitem(@PathVariable UUID parentWorkItemId,
            @Valid @RequestBody WorkItemSubitemCreateRequest body,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        CurrentActor actor = actors.requiredActive();
        UUID key = keys.parseRequired(idempotencyHeader);
        StoredCommandResult stored = service.createSubitem(new CreateSubitem(actor,
                parentWorkItemId, body.contentId(), body.title(), body.priority(),
                body.assigneeUserId(), body.description(), body.notes(), body.timelineStartDate(),
                body.timelineEndDate(), body.dueDate(), key,
                hasher.hash("createWorkItemSubitem", Map.of(
                                "parentWorkItemId", parentWorkItemId.toString()),
                        objectMapper.valueToTree(body)), dueTimeChange(body.dueTime()))).result();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore());
        headers.setETag(stored.etag());
        headers.setLocation(URI.create("/api/v1/work-items/" + stored.resourceId()));
        return new ResponseEntity<>(stored.responseJson(), headers, HttpStatus.CREATED);
    }

    @PostMapping("/work-items/{parentWorkItemId}/subitems/{subitemId}/order-moves")
    ResponseEntity<String> subitemOrderMove(@PathVariable UUID parentWorkItemId,
            @PathVariable UUID subitemId,
            @RequestBody ProjectWorkItemOrderMoveRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false)
            String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        CurrentActor actor = actors.requiredActive();
        service.find(actor, subitemId);
        long expectedVersion = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        UUID key = keys.parseRequired(idempotencyHeader);
        StoredCommandResult stored = service.subitemOrderMove(new SubitemOrderMove(actor,
                parentWorkItemId, subitemId, expectedVersion,
                body.previousVisibleWorkItemId(), body.nextVisibleWorkItemId(), key,
                hasher.hash("moveWorkItemSubitemOrder", Map.of(
                                "parentWorkItemId", parentWorkItemId.toString(),
                                "subitemId", subitemId.toString(),
                                "ifMatch", Long.toString(expectedVersion)),
                        objectMapper.valueToTree(body)))).result();
        return storedResponse(stored);
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
                body.notes(), body.timelineStartDate(), body.timelineEndDate(), body.dueDate(),
                dueTimeChange(body.dueTime())));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(Long.toString(detail.rowVersion())).body(detail);
    }

    @PostMapping("/work-items/{workItemId}/transitions")
    ResponseEntity<String> transition(@PathVariable UUID workItemId,
            @Valid @RequestBody WorkItemTransitionRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false)
            String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        CurrentActor actor = actors.requiredActive();
        service.find(actor, workItemId);
        long expectedVersion = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        UUID key = keys.parseRequired(idempotencyHeader);
        StoredCommandResult stored = service.transition(new Transition(actor, workItemId,
                expectedVersion, body.toStatus(), body.resolution(), key,
                hasher.hash("transitionWorkItem", Map.of(
                                "workItemId", workItemId.toString(),
                                "ifMatch", Long.toString(expectedVersion)),
                        objectMapper.valueToTree(body)))).result();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore());
        headers.setETag(stored.etag());
        return new ResponseEntity<>(stored.responseJson(), headers,
                HttpStatus.valueOf(stored.httpStatus()));
    }

    @PostMapping("/work-items/{workItemId}/rank-moves")
    ResponseEntity<String> rankMove(@PathVariable UUID workItemId,
            @Valid @RequestBody WorkItemRankMoveRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false)
            String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        CurrentActor actor = actors.requiredActive();
        service.find(actor, workItemId);
        long expectedVersion = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        UUID key = keys.parseRequired(idempotencyHeader);
        StoredCommandResult stored = service.rankMove(new RankMove(actor, workItemId,
                expectedVersion, body.toStatus(), body.placement(), body.anchorWorkItemId(),
                body.resolution(), key, hasher.hash("rankMoveWorkItem", Map.of(
                                "workItemId", workItemId.toString(),
                                "ifMatch", Long.toString(expectedVersion)),
                        objectMapper.valueToTree(body)))).result();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore());
        headers.setETag(stored.etag());
        return new ResponseEntity<>(stored.responseJson(), headers,
                HttpStatus.valueOf(stored.httpStatus()));
    }

    @PostMapping("/projects/{projectId}/work-items/{workItemId}/order-moves")
    ResponseEntity<String> projectOrderMove(@PathVariable UUID projectId,
            @PathVariable UUID workItemId,
            @RequestBody ProjectWorkItemOrderMoveRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false)
            String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        CurrentActor actor = actors.requiredActive();
        service.find(actor, workItemId);
        long expectedVersion = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        UUID key = keys.parseRequired(idempotencyHeader);
        StoredCommandResult stored = service.projectOrderMove(new ProjectOrderMove(actor,
                projectId, workItemId, expectedVersion, body.previousVisibleWorkItemId(),
                body.nextVisibleWorkItemId(), key,
                hasher.hash("moveProjectWorkItemOrder", Map.of(
                                "projectId", projectId.toString(),
                                "workItemId", workItemId.toString(),
                                "ifMatch", Long.toString(expectedVersion)),
                        objectMapper.valueToTree(body)))).result();
        return storedResponse(stored);
    }

    @PatchMapping("/work-items/{workItemId}/assignee")
    ResponseEntity<String> patchAssignee(@PathVariable UUID workItemId,
            @RequestBody WorkItemAssigneePatchRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        return inlineUpdate(workItemId, ifMatchHeader, idempotencyHeader, "ASSIGNEE",
                null, body.assigneeUserId(), null, body);
    }

    @PatchMapping("/work-items/{workItemId}/priority")
    ResponseEntity<String> patchPriority(@PathVariable UUID workItemId,
            @RequestBody WorkItemPriorityPatchRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        return inlineUpdate(workItemId, ifMatchHeader, idempotencyHeader, "PRIORITY",
                body.priority(), null, null, body);
    }

    @PatchMapping("/work-items/{workItemId}/due-date")
    ResponseEntity<String> patchDueDate(@PathVariable UUID workItemId,
            @RequestBody WorkItemDueDatePatchRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        return inlineUpdate(workItemId, ifMatchHeader, idempotencyHeader, "DUE_DATE",
                null, null, body.dueDate(), body);
    }

    @PatchMapping("/work-items/{workItemId}/content")
    ResponseEntity<String> patchContent(@PathVariable UUID workItemId,
            @Valid @RequestBody WorkItemContentPatchRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        CurrentActor actor = actors.requiredActive();
        service.find(actor, workItemId);
        long expectedVersion = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        UUID key = keys.parseRequired(idempotencyHeader);
        StoredCommandResult stored = service.changeContent(new ChangeContent(actor, workItemId,
                expectedVersion, body.contentId(), key, hasher.hash("changeWorkItemContent",
                        Map.of("workItemId", workItemId.toString(),
                                "ifMatch", Long.toString(expectedVersion)),
                        objectMapper.valueToTree(body)))).result();
        return storedResponse(stored);
    }

    private ResponseEntity<String> inlineUpdate(UUID workItemId, String ifMatchHeader,
            String idempotencyHeader, String field, String priority, UUID assigneeUserId,
            LocalDate dueDate, Object body) {
        CurrentActor actor = actors.requiredActive();
        service.find(actor, workItemId);
        long expectedVersion = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        UUID key = keys.parseRequired(idempotencyHeader);
        StoredCommandResult stored = service.inlineUpdate(new InlineUpdate(actor, workItemId,
                expectedVersion, field, priority, assigneeUserId, dueDate, key,
                hasher.hash("inlineUpdateWorkItem:" + field, Map.of(
                                "workItemId", workItemId.toString(),
                                "ifMatch", Long.toString(expectedVersion)),
                        objectMapper.valueToTree(body)), body instanceof WorkItemDueDatePatchRequest deadline
                        ? dueTimeChange(deadline.dueTime()) : DueTimeChange.unchanged())).result();
        return storedResponse(stored);
    }

    private static DueTimeChange dueTimeChange(JsonNode value) {
        if (value == null) return DueTimeChange.unchanged();
        if (value.isNull()) return new DueTimeChange(true, null);
        if (!value.isTextual() || !value.textValue().matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))
            throw ApplicationException.validation(new FieldViolation("dueTime", "INVALID_TIME",
                    "截止时间必须使用 HH:mm 格式"));
        return new DueTimeChange(true, LocalTime.parse(value.textValue()));
    }

    @DeleteMapping("/work-items/{workItemId}")
    ResponseEntity<String> delete(@PathVariable UUID workItemId,
            @Valid @RequestBody WorkItemDeleteRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false)
            String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        CurrentActor actor = actors.requiredActive();
        service.findForLifecycle(actor, workItemId);
        long expectedVersion = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        UUID key = keys.parseRequired(idempotencyHeader);
        StoredCommandResult stored = service.delete(new Delete(actor, workItemId,
                expectedVersion, body.reason(), key, hasher.hash("deleteWorkItem", Map.of(
                                "workItemId", workItemId.toString(),
                                "ifMatch", Long.toString(expectedVersion)),
                        objectMapper.valueToTree(body)))).result();
        return storedResponse(stored);
    }

    @PostMapping("/work-items/{workItemId}/restore")
    ResponseEntity<String> restore(@PathVariable UUID workItemId,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false)
            String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        CurrentActor actor = actors.requiredActive();
        service.findForLifecycle(actor, workItemId);
        long expectedVersion = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        UUID key = keys.parseRequired(idempotencyHeader);
        StoredCommandResult stored = service.restore(new Restore(actor, workItemId,
                expectedVersion, key, hasher.hash("restoreWorkItem", Map.of(
                        "workItemId", workItemId.toString(),
                        "ifMatch", Long.toString(expectedVersion)), objectMapper.nullNode()))).result();
        return storedResponse(stored);
    }

    private static ResponseEntity<String> storedResponse(StoredCommandResult stored) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore());
        headers.setETag(stored.etag());
        return new ResponseEntity<>(stored.responseJson(), headers,
                HttpStatus.valueOf(stored.httpStatus()));
    }
}
