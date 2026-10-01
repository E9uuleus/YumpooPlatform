package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.catalog.api.ProjectConnectionTargetQuery;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.workitem.application.ConnectColumnService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.ConnectColumnCommands.*;
import static com.yumpoo.platform.workitem.application.ConnectColumnModels.*;

@ApiV1Controller
public final class ConnectColumnController {
    private final CurrentActorProvider actors;
    private final ConnectColumnService service;
    private final ProjectConnectionTargetQuery targets;
    private final IfMatchParser ifMatch;
    private final IdempotencyKeyParser keys;
    private final IdempotencyRequestHasher hasher;
    private final ObjectMapper json;

    public ConnectColumnController(CurrentActorProvider actors, ConnectColumnService service,
            ProjectConnectionTargetQuery targets, IfMatchParser ifMatch, IdempotencyKeyParser keys,
            IdempotencyRequestHasher hasher, ObjectMapper json) {
        this.actors = actors;
        this.service = service;
        this.targets = targets;
        this.ifMatch = ifMatch;
        this.keys = keys;
        this.hasher = hasher;
        this.json = json;
    }

    @GetMapping("/projects/{projectId}/connect-columns")
    ResponseEntity<Catalog> catalog(@PathVariable UUID projectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.catalog(actors.requiredActive(), projectId));
    }

    @GetMapping("/projects/connect-targets")
    ResponseEntity<ProjectConnectionTargetQuery.ConnectTargetProjectPage> targets(
            @RequestParam(required = false) String query, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        var actor = actors.requiredActive();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(targets.searchActive(actor.companyId(), query, OffsetPageRequest.of(page, size)));
    }

    @PostMapping("/projects/{projectId}/connect-columns")
    ResponseEntity<String> create(@PathVariable UUID projectId, @Valid @RequestBody ConnectColumnRequest body,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String key) {
        var actor = actors.requiredActive();
        var result = service.create(new Create(actor, projectId, body.name(), body.targetProjectIds(),
                keys.parseRequired(key), hasher.hash("createConnectColumn", Map.of("projectId", projectId.toString()),
                json.valueToTree(body)))).result();
        return stored(result, URI.create("/api/v1/projects/" + projectId + "/connect-columns/" + result.resourceId()));
    }

    @PatchMapping("/projects/{projectId}/connect-columns/{columnId}")
    ResponseEntity<Column> update(@PathVariable UUID projectId, @PathVariable UUID columnId,
            @Valid @RequestBody ConnectColumnRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String version) {
        var actor = actors.requiredActive();
        service.requireVisibleColumn(actor, projectId, columnId);
        var result = service.update(new Update(actor, projectId, columnId,
                ifMatch.parseForVisibleResource(true, version), body.name(), body.targetProjectIds()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.etag()).body(result);
    }

    @DeleteMapping("/projects/{projectId}/connect-columns/{columnId}")
    ResponseEntity<String> delete(@PathVariable UUID projectId, @PathVariable UUID columnId,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String version,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String key) {
        var actor = actors.requiredActive();
        service.requireVisibleColumn(actor, projectId, columnId);
        long expected = ifMatch.parseForVisibleResource(true, version);
        var result = service.delete(new Delete(actor, projectId, columnId, expected, keys.parseRequired(key),
                hasher.hash("deleteConnectColumn", Map.of("projectId", projectId.toString(),
                        "columnId", columnId.toString(), "ifMatch", Long.toString(expected)), json.createObjectNode()))).result();
        return stored(result, null);
    }

    private static ResponseEntity<String> stored(StoredCommandResult result, URI location) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore());
        headers.setETag(result.etag());
        if (location != null) headers.setLocation(location);
        return new ResponseEntity<>(result.responseJson(), headers, HttpStatus.valueOf(result.httpStatus()));
    }
}
