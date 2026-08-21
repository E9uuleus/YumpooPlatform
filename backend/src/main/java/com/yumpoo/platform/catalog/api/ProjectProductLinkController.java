package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.project.ProjectProductLinkCommands.ChangePrimary;
import com.yumpoo.platform.catalog.application.project.ProjectProductLinkCommands.Create;
import com.yumpoo.platform.catalog.application.project.ProjectProductLinkCommands.Remove;
import com.yumpoo.platform.catalog.application.project.ProjectProductLinkModels.LinkList;
import com.yumpoo.platform.catalog.application.project.ProjectProductLinkModels.LinkView;
import com.yumpoo.platform.catalog.application.project.ProjectProductLinkModels.ProductCandidatePage;
import com.yumpoo.platform.catalog.application.project.ProjectProductLinkService;
import com.yumpoo.platform.catalog.application.project.ProjectProductRelation;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
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
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@ApiV1Controller
public final class ProjectProductLinkController {

    private final CurrentActorProvider actors;
    private final ProjectProductLinkService service;
    private final IfMatchParser ifMatch;
    private final IdempotencyKeyParser idempotencyKeys;
    private final IdempotencyRequestHasher hasher;
    private final ObjectMapper objectMapper;

    public ProjectProductLinkController(CurrentActorProvider actors,
            ProjectProductLinkService service, IfMatchParser ifMatch,
            IdempotencyKeyParser idempotencyKeys, IdempotencyRequestHasher hasher,
            ObjectMapper objectMapper) {
        this.actors = actors;
        this.service = service;
        this.ifMatch = ifMatch;
        this.idempotencyKeys = idempotencyKeys;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/projects/{projectId}/products")
    ResponseEntity<LinkList> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.findActive(actors.requiredActive(), projectId));
    }

    @GetMapping("/projects/{projectId}/product-candidates")
    ResponseEntity<ProductCandidatePage> candidates(@PathVariable UUID projectId,
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.findCandidates(actors.requiredActive(), projectId, query,
                        OffsetPageRequest.of(page, size)));
    }

    @PostMapping("/projects/{projectId}/products")
    ResponseEntity<String> create(@PathVariable UUID projectId,
            @Valid @RequestBody ProjectProductLinkCreateRequest body,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        CurrentActor actor = actors.requiredActive();
        service.requireVisible(actor, projectId);
        UUID key = idempotencyKeys.parseRequired(idempotencyHeader);
        Create command = new Create(actor, projectId, body.productId(), relationType(body.relationType()),
                body.isPrimary(), key,
                hasher.hash("createProjectProductLink", Map.of("projectId", projectId.toString()),
                        objectMapper.valueToTree(body)));
        return stored(service.create(command).result(), projectId, true);
    }

    @PatchMapping("/projects/{projectId}/products/{linkId}")
    ResponseEntity<LinkView> update(@PathVariable UUID projectId, @PathVariable UUID linkId,
            @Valid @RequestBody ProjectProductLinkUpdateRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatchHeader) {
        CurrentActor actor = actors.requiredActive();
        service.requireVisible(actor, projectId);
        long expected = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        LinkView link = service.changePrimary(new ChangePrimary(actor, projectId, linkId, expected,
                body.isPrimary()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(Long.toString(link.rowVersion())).body(link);
    }

    @DeleteMapping("/projects/{projectId}/products/{linkId}")
    ResponseEntity<String> remove(@PathVariable UUID projectId, @PathVariable UUID linkId,
            @Valid @RequestBody(required = false) ProjectProductLinkRemoveRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatchHeader,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false)
            String idempotencyHeader) {
        CurrentActor actor = actors.requiredActive();
        service.requireVisible(actor, projectId);
        long expected = ifMatch.parseForVisibleResource(true, ifMatchHeader);
        UUID key = idempotencyKeys.parseRequired(idempotencyHeader);
        ProjectProductLinkRemoveRequest resolved = body == null
                ? new ProjectProductLinkRemoveRequest(null) : body;
        Remove command = new Remove(actor, projectId, linkId, expected, resolved.reason(), key,
                hasher.hash("removeProjectProductLink", Map.of(
                        "projectId", projectId.toString(), "linkId", linkId.toString(),
                        "ifMatch", Long.toString(expected)), objectMapper.valueToTree(resolved)));
        return stored(service.remove(command).result(), projectId, false);
    }

    private static ProjectProductRelation relationType(String value) {
        try {
            return ProjectProductRelation.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw ApplicationException.validation(new FieldViolation("relationType", "INVALID_VALUE",
                    "关系类型必须为 DEVELOPMENT、DELIVERY、SUPPORT 或 USED_BY"));
        }
    }

    private static ResponseEntity<String> stored(StoredCommandResult stored, UUID projectId,
                                                   boolean includeLocation) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore());
        headers.setETag(stored.etag());
        if (includeLocation) {
            headers.setLocation(URI.create("/api/v1/projects/" + projectId + "/products/"
                    + stored.resourceId()));
        }
        return new ResponseEntity<>(stored.responseJson(), headers,
                HttpStatus.valueOf(stored.httpStatus()));
    }
}
