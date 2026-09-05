package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.workitem.application.ContentCommands.Create;
import com.yumpoo.platform.workitem.application.ContentCommands.Delete;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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
        this.actors = actors;
        this.service = service;
        this.ifMatch = ifMatch;
        this.keys = keys;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/projects/{projectId}/contents")
    ResponseEntity<ProjectContentCatalog> list(@PathVariable UUID projectId) {
        ProjectContentCatalog catalog = service.catalog(actors.requiredActive(), projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(Long.toString(catalog.rowVersion())).body(catalog);
    }

    @PostMapping("/projects/{projectId}/contents")
    ResponseEntity<String> create(@PathVariable UUID projectId,
            @Valid @RequestBody ContentCreateRequest body,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String keyHeader) {
        CurrentActor actor = actors.requiredActive();
        UUID key = keys.parseRequired(keyHeader);
        StoredCommandResult result = service.create(new Create(actor, projectId, body.name(),
                body.colorToken(), key, hasher.hash("createContent",
                Map.of("projectId", projectId.toString()), objectMapper.valueToTree(body)))).result();
        return stored(result, projectId);
    }

    @PatchMapping("/projects/{projectId}/contents/{contentId}")
    ResponseEntity<ContentView> update(@PathVariable UUID projectId, @PathVariable UUID contentId,
            @Valid @RequestBody ContentUpdateRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatchHeader) {
        long version = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        ContentView content = service.update(new Update(actors.requiredActive(), projectId, contentId,
                version, body.name(), body.colorToken(), body.active(), body.sortOrder()));
        ProjectContentCatalog catalog = service.catalog(actors.requiredActive(), projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(Long.toString(catalog.rowVersion())).body(content);
    }

    @DeleteMapping("/projects/{projectId}/contents/{contentId}")
    ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID contentId,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatchHeader) {
        long version = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        service.delete(new Delete(actors.requiredActive(), projectId, contentId, version));
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<String> stored(StoredCommandResult result, UUID projectId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore());
        headers.setETag(result.etag());
        headers.setLocation(URI.create("/api/v1/projects/" + projectId + "/contents/" + result.resourceId()));
        return new ResponseEntity<>(result.responseJson(), headers, HttpStatus.valueOf(result.httpStatus()));
    }
}
