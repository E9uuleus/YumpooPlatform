package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.workspace.WorkspaceListStatus;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceService;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceUpdateCommand;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceView;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
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
import java.util.UUID;

@ApiV1Controller
public final class WorkspaceController {

    private final CurrentActorProvider currentActorProvider;
    private final WorkspaceService service;
    private final IfMatchParser ifMatchParser;

    public WorkspaceController(
            CurrentActorProvider currentActorProvider,
            WorkspaceService service,
            IfMatchParser ifMatchParser
    ) {
        this.currentActorProvider = currentActorProvider;
        this.service = service;
        this.ifMatchParser = ifMatchParser;
    }

    @GetMapping("/workspaces")
    ResponseEntity<WorkspaceListResponse> list() {
        CurrentActor actor = currentActorProvider.requiredActive();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new WorkspaceListResponse(service.findAll(actor, WorkspaceListStatus.ACTIVE)));
    }

    @GetMapping("/workspaces/{workspaceId}")
    ResponseEntity<WorkspaceView> detail(@PathVariable UUID workspaceId) {
        CurrentActor actor = currentActorProvider.requiredActive();
        WorkspaceView workspace = service.findVisible(actor, workspaceId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(Long.toString(workspace.rowVersion()))
                .body(workspace);
    }

    @PatchMapping("/workspaces/{workspaceId}")
    ResponseEntity<WorkspaceView> update(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody WorkspaceUpdateRequest body,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch
    ) {
        CurrentActor actor = currentActorProvider.requiredActive();
        service.findForAdministration(actor, workspaceId);
        long expectedVersion = ifMatchParser.parseForVisibleResource(true, ifMatch);
        WorkspaceView workspace = service.update(new WorkspaceUpdateCommand(
                actor, workspaceId, expectedVersion,
                body.name(), body.description()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(Long.toString(workspace.rowVersion()))
                .body(workspace);
    }

}
