package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.workitem.application.ConnectCandidateSearch;
import com.yumpoo.platform.workitem.application.WorkItemConnectionCommands;
import com.yumpoo.platform.workitem.application.WorkItemConnectionService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemConnectionModels.*;

@ApiV1Controller
public final class WorkItemConnectionController {
    private final CurrentActorProvider actors;
    private final WorkItemConnectionService service;
    private final IfMatchParser ifMatch;
    private final IdempotencyKeyParser keys;
    private final IdempotencyRequestHasher hasher;
    private final ObjectMapper json;

    public WorkItemConnectionController(CurrentActorProvider actors, WorkItemConnectionService service,
            IfMatchParser ifMatch, IdempotencyKeyParser keys, IdempotencyRequestHasher hasher, ObjectMapper json) {
        this.actors = actors;
        this.service = service;
        this.ifMatch = ifMatch;
        this.keys = keys;
        this.hasher = hasher;
        this.json = json;
    }

    @GetMapping("/projects/{projectId}/work-item-connections")
    ResponseEntity<CellList> cells(@PathVariable UUID projectId, @RequestParam List<UUID> workItemIds) {
        return read(service.cells(actors.requiredActive(), projectId, workItemIds));
    }

    @GetMapping("/work-items/{workItemId}/incoming-connections")
    ResponseEntity<ConnectionPage> incoming(@PathVariable UUID workItemId, @RequestParam(required = false) UUID columnId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return read(service.incoming(actors.requiredActive(), workItemId, columnId, OffsetPageRequest.of(page, size)));
    }

    @GetMapping("/work-item-connections/{connectionId}")
    ResponseEntity<ConnectionView> find(@PathVariable UUID connectionId) {
        var result = service.find(actors.requiredActive(), connectionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.etag()).body(result);
    }

    @GetMapping("/projects/{projectId}/connect-columns/{columnId}/create-options")
    ResponseEntity<CreateOptions> createOptions(@PathVariable UUID projectId, @PathVariable UUID columnId,
            @RequestParam UUID targetProjectId) {
        return read(service.createOptions(actors.requiredActive(), projectId, columnId, targetProjectId));
    }

    @GetMapping("/projects/{projectId}/connect-columns/{columnId}/candidates")
    ResponseEntity<CandidatePage> candidates(@PathVariable UUID projectId, @PathVariable UUID columnId,
            @RequestParam UUID targetProjectId, @RequestParam UUID sourceWorkItemId,
            @RequestParam(required = false) String q, @RequestParam(required = false) List<String> fields,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return read(service.candidates(actors.requiredActive(), projectId, columnId, targetProjectId,
                sourceWorkItemId, ConnectCandidateSearch.of(q, fields, sort), OffsetPageRequest.of(page, size)));
    }

    @GetMapping("/work-items/{workItemId}/reverse-connect-candidates")
    ResponseEntity<CandidatePage> reverseCandidates(@PathVariable UUID workItemId, @RequestParam UUID columnId,
            @RequestParam(required = false) String q, @RequestParam(required = false) List<String> fields,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return read(service.reverseCandidates(actors.requiredActive(), workItemId, columnId,
                ConnectCandidateSearch.of(q, fields, sort), OffsetPageRequest.of(page, size)));
    }

    @GetMapping("/work-items/{workItemId}/reverse-connect-create-options")
    ResponseEntity<CreateOptions> reverseCreateOptions(@PathVariable UUID workItemId, @RequestParam UUID columnId) {
        return read(service.reverseCreateOptions(actors.requiredActive(), workItemId, columnId));
    }

    @PostMapping("/work-items/{workItemId}/connections")
    ResponseEntity<String> link(@PathVariable UUID workItemId, @Valid @RequestBody WorkItemConnectionRequests.Link body,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String key) {
        var result = service.link(new WorkItemConnectionCommands.Link(actors.requiredActive(), workItemId,
                body.columnId(), body.targetWorkItemId(), keys.parseRequired(key), hasher.hash("linkWorkItemConnection",
                Map.of("workItemId", workItemId.toString()), json.valueToTree(body)))).result();
        return stored(result, true);
    }

    @PostMapping("/work-items/{workItemId}/connected-work-items")
    ResponseEntity<String> createConnected(@PathVariable UUID workItemId,
            @Valid @RequestBody WorkItemConnectionRequests.CreateConnected body,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String key) {
        var result = service.createConnected(new WorkItemConnectionCommands.CreateConnected(actors.requiredActive(), workItemId,
                body.columnId(), body.targetProjectId(), body.title(), body.contentId(), keys.parseRequired(key),
                hasher.hash("createConnectedWorkItem", Map.of("workItemId", workItemId.toString()), json.valueToTree(body)))).result();
        return stored(result, true);
    }

    @PostMapping("/work-items/{workItemId}/reverse-connected-work-items")
    ResponseEntity<String> reverseCreateConnected(@PathVariable UUID workItemId,
            @Valid @RequestBody WorkItemConnectionRequests.CreateReverseConnected body,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String key) {
        var result = service.reverseCreateConnected(new WorkItemConnectionCommands.CreateReverseConnected(actors.requiredActive(),
                workItemId, body.columnId(), body.title(), body.contentId(), keys.parseRequired(key),
                hasher.hash("createReverseConnectedWorkItem", Map.of("workItemId", workItemId.toString()),
                        json.valueToTree(body)))).result();
        return stored(result, true);
    }

    @DeleteMapping("/work-item-connections/{connectionId}")
    ResponseEntity<String> unlink(@PathVariable UUID connectionId,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String version,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String key) {
        var actor = actors.requiredActive();
        service.requireVisibleConnection(actor, connectionId);
        long expected = ifMatch.parseForVisibleResource(true, version);
        var result = service.unlink(new WorkItemConnectionCommands.Unlink(actor, connectionId, expected, keys.parseRequired(key),
                hasher.hash("unlinkWorkItemConnection", Map.of("connectionId", connectionId.toString(),
                        "ifMatch", Long.toString(expected)), json.createObjectNode()))).result();
        return stored(result, false);
    }

    private static <T> ResponseEntity<T> read(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    private static ResponseEntity<String> stored(StoredCommandResult result, boolean location) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore());
        headers.setETag(result.etag());
        if (location) headers.setLocation(URI.create("/api/v1/work-item-connections/" + result.resourceId()));
        return new ResponseEntity<>(result.responseJson(), headers, HttpStatus.valueOf(result.httpStatus()));
    }
}
