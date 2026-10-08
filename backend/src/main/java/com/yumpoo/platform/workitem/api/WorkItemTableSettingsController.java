package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.workitem.application.WorkItemTableSettingsModels.View;
import com.yumpoo.platform.workitem.application.WorkItemTableSettingsModels.Write;
import com.yumpoo.platform.workitem.application.WorkItemTableSettingsService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.UUID;

@ApiV1Controller
public final class WorkItemTableSettingsController {
    private final CurrentActorProvider actors;
    private final WorkItemTableSettingsService service;

    public WorkItemTableSettingsController(CurrentActorProvider actors, WorkItemTableSettingsService service) {
        this.actors = actors;
        this.service = service;
    }

    @GetMapping("/me/projects/{projectId}/work-item-table-settings")
    ResponseEntity<View> get(@PathVariable UUID projectId) {
        return response(service.get(actors.requiredActive(), projectId));
    }

    @PutMapping("/me/projects/{projectId}/work-item-table-settings")
    ResponseEntity<View> update(@PathVariable UUID projectId, @RequestBody Write body) {
        return response(service.update(actors.requiredActive(), projectId, body));
    }

    private static <T> ResponseEntity<T> response(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
