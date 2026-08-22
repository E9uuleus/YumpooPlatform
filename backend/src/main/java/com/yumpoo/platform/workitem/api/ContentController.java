package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.workitem.application.ContentCommands.Create;
import com.yumpoo.platform.workitem.application.ContentCommands.Transition;
import com.yumpoo.platform.workitem.application.ContentCommands.Update;
import com.yumpoo.platform.workitem.application.ContentModels.ContentView;
import com.yumpoo.platform.workitem.application.ContentModels.ProjectContentCatalog;
import com.yumpoo.platform.workitem.application.ContentService;
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
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@ApiV1Controller
public final class ContentController {
    private final CurrentActorProvider actors;
    private final ContentService service;
    private final IfMatchParser ifMatch;
    private final IdempotencyKeyParser keys;
    private final IdempotencyRequestHasher hasher;
    private final ObjectMapper objectMapper;

    public ContentController(CurrentActorProvider actors, ContentService service, IfMatchParser ifMatch,
            IdempotencyKeyParser keys, IdempotencyRequestHasher hasher, ObjectMapper objectMapper) {
        this.actors = actors; this.service = service; this.ifMatch = ifMatch; this.keys = keys;
        this.hasher = hasher; this.objectMapper = objectMapper;
    }

    @GetMapping("/projects/{projectId}/contents")
    ResponseEntity<ProjectContentCatalog> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.catalog(actors.requiredActive(), projectId));
    }

    @PostMapping("/projects/{projectId}/contents")
    ResponseEntity<String> create(@PathVariable UUID projectId,
            @Valid @RequestBody ContentCreateRequest body,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String keyHeader) {
        CurrentActor actor = actors.requiredActive();
        UUID key = keys.parseRequired(keyHeader);
        StoredCommandResult result = service.create(new Create(actor, projectId, body.code(), body.name(),
                body.description(), body.blueprintCode(), key, hasher.hash("createContent",
                Map.of("projectId", projectId.toString()), objectMapper.valueToTree(body)))).result();
        return stored(result, true);
    }

    @GetMapping("/contents/{contentId}")
    ResponseEntity<ContentView> detail(@PathVariable UUID contentId) {
        ContentView content = service.find(actors.requiredActive(), contentId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(Long.toString(content.rowVersion())).body(content);
    }

    @PatchMapping("/contents/{contentId}")
    ResponseEntity<ContentView> update(@PathVariable UUID contentId,
            @Valid @RequestBody ContentUpdateRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatchHeader) {
        CurrentActor actor = actors.requiredActive();
        service.find(actor, contentId);
        long version = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        ContentView content = service.update(new Update(actor, contentId, version, body.name(),
                body.description(), body.defaultViewType(), body.viewConfig()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(Long.toString(content.rowVersion())).body(content);
    }

    @PostMapping("/contents/{contentId}/archive")
    ResponseEntity<String> archive(@PathVariable UUID contentId,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String keyHeader) {
        return transition(contentId, ifMatchHeader, keyHeader, true);
    }

    @PostMapping("/contents/{contentId}/restore")
    ResponseEntity<String> restore(@PathVariable UUID contentId,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String keyHeader) {
        return transition(contentId, ifMatchHeader, keyHeader, false);
    }

    private ResponseEntity<String> transition(UUID contentId, String ifMatchHeader,
            String keyHeader, boolean archive) {
        CurrentActor actor = actors.requiredActive();
        service.find(actor, contentId);
        long version = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        UUID key = keys.parseRequired(keyHeader);
        String route = archive ? "archiveContent" : "restoreContent";
        Transition command = new Transition(actor, contentId, version, key, hasher.hash(route,
                Map.of("contentId", contentId.toString(), "ifMatch", Long.toString(version)),
                objectMapper.createObjectNode()));
        StoredCommandResult result = (archive ? service.archive(command) : service.restore(command)).result();
        return stored(result, false);
    }

    private static ResponseEntity<String> stored(StoredCommandResult result, boolean location) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON); headers.setCacheControl(CacheControl.noStore());
        headers.setETag(result.etag());
        if (location) headers.setLocation(URI.create("/api/v1/contents/" + result.resourceId()));
        return new ResponseEntity<>(result.responseJson(), headers, HttpStatus.valueOf(result.httpStatus()));
    }
}
