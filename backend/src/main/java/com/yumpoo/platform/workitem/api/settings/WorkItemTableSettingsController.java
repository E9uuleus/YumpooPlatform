package com.yumpoo.platform.workitem.api.settings;

import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.workitem.application.settings.WorkItemTableSettingsModels.View;
import com.yumpoo.platform.workitem.application.settings.WorkItemTableSettingsModels.Write;
import com.yumpoo.platform.workitem.application.settings.WorkItemTableSettingsService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@ApiV1Controller
public final class WorkItemTableSettingsController {
    private final CurrentActorProvider actors;
    private final WorkItemTableSettingsService service;
    private final IfMatchParser ifMatch;

    public WorkItemTableSettingsController(
            CurrentActorProvider actors, WorkItemTableSettingsService service, IfMatchParser ifMatch) {
        this.actors = actors;
        this.service = service;
        this.ifMatch = ifMatch;
    }

    @GetMapping("/me/projects/{projectId}/work-item-table-settings")
    ResponseEntity<View> get(@PathVariable UUID projectId) {
        return response(service.get(actors.requiredActive(), projectId));
    }

    @PutMapping("/me/projects/{projectId}/work-item-table-settings")
    ResponseEntity<View> update(
            @PathVariable UUID projectId,
            @RequestBody Write body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatchHeader) {
        var actor = actors.requiredActive();
        service.get(actor, projectId);
        return response(service.update(actor, projectId, body, ifMatch.parseForVisibleResource(true, ifMatchHeader)));
    }

    private static ResponseEntity<View> response(View value) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(value.etag())
                .body(value);
    }
}
