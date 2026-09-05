package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.audit.api.ActivityPage;
import com.yumpoo.platform.audit.api.ActivityQuery;
import com.yumpoo.platform.audit.api.ActivityQueryPort;
import com.yumpoo.platform.audit.api.WorkItemCellActivityColumn;
import com.yumpoo.platform.audit.api.WorkItemCellActivityPage;
import com.yumpoo.platform.audit.api.WorkItemCellActivityQuery;
import com.yumpoo.platform.audit.api.WorkItemCellActivityQueryPort;
import com.yumpoo.platform.audit.api.WorkItemCellActivityTimeRange;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.workitem.api.WorkItemActivitySourceQuery;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApiV1Controller
public final class ActivityController {
    private final CurrentActorProvider actors;
    private final ProjectAccessSnapshotQuery access;
    private final WorkItemActivitySourceQuery workItems;
    private final ActivityQueryPort activity;
    private final WorkItemCellActivityQueryPort cellActivity;
    private final CompanyConfigurationQuery company;

    public ActivityController(CurrentActorProvider actors, ProjectAccessSnapshotQuery access,
            WorkItemActivitySourceQuery workItems, ActivityQueryPort activity,
            WorkItemCellActivityQueryPort cellActivity, CompanyConfigurationQuery company) {
        this.actors = actors;
        this.access = access;
        this.workItems = workItems;
        this.activity = activity;
        this.cellActivity = cellActivity;
        this.company = company;
    }

    @GetMapping("/projects/{projectId}/activity")
    ResponseEntity<ActivityPage> project(@PathVariable UUID projectId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size,
            @RequestParam(name = "eventType", required = false) List<String> eventTypes,
            @RequestParam(name = "entityType", required = false) List<String> entityTypes,
            @RequestParam(required = false) Instant occurredFrom,
            @RequestParam(required = false) Instant occurredTo) {
        CurrentActor actor = actors.requiredActive();
        ProjectAccessSnapshot project = visible(actor, projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(activity.findProject(project.companyId(), projectId,
                        new ActivityQuery(cursor, size, eventTypes, entityTypes,
                                occurredFrom, occurredTo)));
    }

    @GetMapping("/work-items/{workItemId}/activity")
    ResponseEntity<ActivityPage> workItem(@PathVariable UUID workItemId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size,
            @RequestParam(name = "eventType", required = false) List<String> eventTypes,
            @RequestParam(name = "entityType", required = false) List<String> entityTypes,
            @RequestParam(required = false) Instant occurredFrom,
            @RequestParam(required = false) Instant occurredTo) {
        CurrentActor actor = actors.requiredActive();
        var item = workItems.findIncludingDeleted(actor.companyId(), workItemId)
                .orElseThrow(ActivityController::notFound);
        ProjectAccessSnapshot project = visible(actor, item.projectId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(activity.findWorkItem(project.companyId(), project.projectId(), workItemId,
                        new ActivityQuery(cursor, size, eventTypes, entityTypes,
                                occurredFrom, occurredTo)));
    }

    @GetMapping("/work-items/{workItemId}/cell-activity")
    ResponseEntity<WorkItemCellActivityPage> cellActivity(@PathVariable UUID workItemId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) WorkItemCellActivityTimeRange timeRange,
            @RequestParam(name = "actorUserId", required = false) List<UUID> actorUserIds,
            @RequestParam(name = "column", required = false)
                    List<WorkItemCellActivityColumn> columns) {
        CurrentActor actor = actors.requiredActive();
        var item = workItems.findIncludingDeleted(actor.companyId(), workItemId)
                .orElseThrow(ActivityController::notFound);
        ProjectAccessSnapshot project = visible(actor, item.projectId());
        var configuration = company.current();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cellActivity.find(
                project.companyId(), project.projectId(), workItemId, configuration.timezone(),
                configuration.weekStartDay(), new WorkItemCellActivityQuery(cursor, size,
                        timeRange, actorUserIds, columns)));
    }

    private ProjectAccessSnapshot visible(CurrentActor actor, UUID projectId) {
        return access.findVisible(actor, projectId).orElseThrow(ActivityController::notFound);
    }

    private static ApplicationException notFound() {
        return new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
    }
}
