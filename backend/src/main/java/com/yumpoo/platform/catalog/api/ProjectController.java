package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.project.ProjectDetail;
import com.yumpoo.platform.catalog.application.project.ProjectLifecycleFilter;
import com.yumpoo.platform.catalog.application.project.ProjectService;
import com.yumpoo.platform.catalog.application.project.ProjectSummary;
import com.yumpoo.platform.catalog.application.project.ProjectTypeFilter;
import com.yumpoo.platform.catalog.application.project.ProjectUpdateCommand;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageResponse;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@ApiV1Controller
public final class ProjectController {

    private final CurrentActorProvider actorProvider;
    private final ProjectService service;
    private final IfMatchParser ifMatchParser;

    public ProjectController(CurrentActorProvider actorProvider, ProjectService service,
                             IfMatchParser ifMatchParser) {
        this.actorProvider = actorProvider;
        this.service = service;
        this.ifMatchParser = ifMatchParser;
    }

    @GetMapping("/projects")
    ResponseEntity<OffsetPageResponse<ProjectSummary>> list(
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) ProjectTypeFilter projectType,
            @RequestParam(required = false) ProjectLifecycleFilter lifecycle,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        CurrentActor actor = actorProvider.requiredActive();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.findAll(actor, workspaceId, projectType, lifecycle,
                        OffsetPageRequest.of(page, size)));
    }

    @GetMapping("/projects/{projectId}")
    ResponseEntity<ProjectDetail> detail(@PathVariable UUID projectId) {
        ProjectDetail project = service.findVisible(actorProvider.requiredActive(), projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(Long.toString(project.rowVersion())).body(project);
    }

    @PatchMapping("/projects/{projectId}")
    ResponseEntity<ProjectDetail> update(@PathVariable UUID projectId,
            @Valid @RequestBody ProjectUpdateRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch) {
        CurrentActor actor = actorProvider.requiredActive();
        service.findVisible(actor, projectId);
        long version = ifMatchParser.parseForVisibleResource(true, ifMatch);
        ProjectDetail project = service.update(new ProjectUpdateCommand(actor, projectId, version,
                body.name(), body.description(), body.customerName(), body.customerReference(),
                body.deliverySite(), body.contactNote()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(Long.toString(project.rowVersion())).body(project);
    }
}
