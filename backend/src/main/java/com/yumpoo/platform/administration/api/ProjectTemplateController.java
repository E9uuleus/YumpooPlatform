package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.administration.application.ProjectTemplateGovernanceCommand;
import com.yumpoo.platform.administration.application.ProjectTemplateGovernanceService;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateSnapshot;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

@ApiV1Controller
public final class ProjectTemplateController {

    private final CurrentActorProvider currentActorProvider;
    private final ProjectTemplateGovernanceService service;
    private final IfMatchParser ifMatchParser;
    private final IdempotencyKeyParser idempotencyKeyParser;
    private final IdempotencyRequestHasher requestHasher;
    private final ObjectMapper objectMapper;

    public ProjectTemplateController(
            CurrentActorProvider currentActorProvider,
            ProjectTemplateGovernanceService service,
            IfMatchParser ifMatchParser,
            IdempotencyKeyParser idempotencyKeyParser,
            IdempotencyRequestHasher requestHasher,
            ObjectMapper objectMapper
    ) {
        this.currentActorProvider = currentActorProvider;
        this.service = service;
        this.ifMatchParser = ifMatchParser;
        this.idempotencyKeyParser = idempotencyKeyParser;
        this.requestHasher = requestHasher;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/project-templates")
    ResponseEntity<ProjectTemplateListResponse> published() {
        CurrentActor actor = currentActorProvider.requiredActive();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ProjectTemplateListResponse(service.findPublished(actor)));
    }

    @GetMapping("/admin/project-templates/{templateKey}/versions/{version}")
    ResponseEntity<ProjectTemplateSnapshot> detail(
            @PathVariable String templateKey,
            @PathVariable int version
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        ProjectTemplateSnapshot snapshot = service.findAnyForAdministration(actor, templateKey, version);
        return ResponseEntity.ok()
                .eTag(Long.toString(snapshot.rowVersion()))
                .cacheControl(CacheControl.noStore())
                .body(snapshot);
    }

    @PostMapping("/admin/project-templates/{templateKey}/versions/{version}/publish")
    ResponseEntity<String> publish(
            @PathVariable String templateKey,
            @PathVariable int version,
            @Valid @RequestBody ProjectTemplateReasonRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String idempotencyKey
    ) {
        return mutate(true, templateKey, version, body, ifMatch, idempotencyKey);
    }

    @PostMapping("/admin/project-templates/{templateKey}/versions/{version}/retire")
    ResponseEntity<String> retire(
            @PathVariable String templateKey,
            @PathVariable int version,
            @Valid @RequestBody ProjectTemplateReasonRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKeyParser.HEADER_NAME, required = false) String idempotencyKey
    ) {
        return mutate(false, templateKey, version, body, ifMatch, idempotencyKey);
    }

    private ResponseEntity<String> mutate(
            boolean publish,
            String templateKey,
            int version,
            ProjectTemplateReasonRequest body,
            String ifMatch,
            String idempotencyHeader
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        service.findAnyForAdministration(actor, templateKey, version);
        long expectedVersion = ifMatchParser.parseForVisibleResource(true, ifMatch);
        UUID idempotencyKey = idempotencyKeyParser.parseRequired(idempotencyHeader);
        String operation = publish ? "publishProjectTemplate" : "retireProjectTemplate";
        ProjectTemplateGovernanceCommand command = new ProjectTemplateGovernanceCommand(
                actor, templateKey, version, expectedVersion, body.reason(), idempotencyKey,
                requestHasher.hash(
                        operation,
                        Map.of(
                                "templateKey", templateKey,
                                "version", Integer.toString(version),
                                "ifMatch", Long.toString(expectedVersion)),
                        objectMapper.valueToTree(body)),
                null,
                null
        );
        StoredCommandResult stored = (publish ? service.publish(command) : service.retire(command)).result();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setETag(stored.etag());
        headers.setCacheControl(CacheControl.noStore());
        return new ResponseEntity<>(stored.responseJson(), headers, HttpStatus.valueOf(stored.httpStatus()));
    }
}
