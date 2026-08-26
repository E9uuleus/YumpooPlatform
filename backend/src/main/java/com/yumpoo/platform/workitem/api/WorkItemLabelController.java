package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.workitem.application.WorkItemLabelModels.LabelCatalog;
import com.yumpoo.platform.workitem.application.WorkItemLabelService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

@com.yumpoo.platform.foundation.api.web.ApiV1Controller
public final class WorkItemLabelController {
    private final CurrentActorProvider actors;
    private final WorkItemLabelService service;
    private final IfMatchParser ifMatch;

    public WorkItemLabelController(CurrentActorProvider actors, WorkItemLabelService service,
            IfMatchParser ifMatch) {
        this.actors = actors;
        this.service = service;
        this.ifMatch = ifMatch;
    }

    @GetMapping("/projects/{projectId}/work-item-labels")
    ResponseEntity<LabelCatalog> catalog(@PathVariable UUID projectId) {
        return response(service.catalog(actors.requiredActive(), projectId));
    }

    @PostMapping("/projects/{projectId}/work-item-labels/statuses")
    ResponseEntity<LabelCatalog> createStatus(@PathVariable UUID projectId,
            @RequestBody WorkItemLabelCreateRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String header) {
        return response(service.createStatus(actors.requiredActive(), projectId,
                ifMatch.parseForVisibleResource(true, header), body.displayName(), body.colorToken()));
    }

    @PatchMapping("/projects/{projectId}/work-item-labels/statuses/{code}")
    ResponseEntity<LabelCatalog> updateStatus(@PathVariable UUID projectId,
            @PathVariable String code, @RequestBody WorkItemLabelUpdateRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String header) {
        return response(service.updateStatus(actors.requiredActive(), projectId, code,
                ifMatch.parseForVisibleResource(true, header), body.displayName(),
                body.colorToken(), body.active(), body.sortOrder()));
    }

    @DeleteMapping("/projects/{projectId}/work-item-labels/statuses/{code}")
    ResponseEntity<LabelCatalog> deleteStatus(@PathVariable UUID projectId,
            @PathVariable String code,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String header) {
        return response(service.deleteStatus(actors.requiredActive(), projectId, code,
                ifMatch.parseForVisibleResource(true, header)));
    }

    @PostMapping("/projects/{projectId}/work-item-labels/priorities")
    ResponseEntity<LabelCatalog> createPriority(@PathVariable UUID projectId,
            @RequestBody WorkItemLabelCreateRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String header) {
        return response(service.createPriority(actors.requiredActive(), projectId,
                ifMatch.parseForVisibleResource(true, header), body.displayName(), body.colorToken()));
    }

    @PatchMapping("/projects/{projectId}/work-item-labels/priorities/{code}")
    ResponseEntity<LabelCatalog> updatePriority(@PathVariable UUID projectId,
            @PathVariable String code, @RequestBody WorkItemLabelUpdateRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String header) {
        return response(service.updatePriority(actors.requiredActive(), projectId, code,
                ifMatch.parseForVisibleResource(true, header), body.displayName(),
                body.colorToken(), body.active(), body.sortOrder()));
    }

    @DeleteMapping("/projects/{projectId}/work-item-labels/priorities/{code}")
    ResponseEntity<LabelCatalog> deletePriority(@PathVariable UUID projectId,
            @PathVariable String code,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String header) {
        return response(service.deletePriority(actors.requiredActive(), projectId, code,
                ifMatch.parseForVisibleResource(true, header)));
    }

    private static ResponseEntity<LabelCatalog> response(LabelCatalog catalog) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(catalog.etag()).body(catalog);
    }
}
