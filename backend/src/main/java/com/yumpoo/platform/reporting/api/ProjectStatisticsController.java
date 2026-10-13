package com.yumpoo.platform.reporting.api;

import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.reporting.application.DashboardModels;
import com.yumpoo.platform.reporting.application.ProjectStatisticsModels.*;
import com.yumpoo.platform.reporting.application.ProjectStatisticsService;
import com.yumpoo.platform.workitem.api.WorkItemStatisticsQuery;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.UUID;

@ApiV1Controller
public class ProjectStatisticsController {
    private final ProjectStatisticsService service;
    private final CurrentActorProvider actors;
    public ProjectStatisticsController(ProjectStatisticsService service, CurrentActorProvider actors) { this.service = service; this.actors = actors; }

    @PostMapping("/projects/{projectId}/statistics/query")
    ResponseEntity<StatisticsResponse> query(@PathVariable UUID projectId, @RequestBody StatisticsQuery body) {
        return response(service.query(actors.requiredActive(), projectId, body));
    }
    @PostMapping("/projects/{projectId}/statistics/items/query")
    ResponseEntity<WorkItemStatisticsQuery.Page> items(@PathVariable UUID projectId, @RequestBody DashboardModels.ItemsQuery body) {
        return response(service.items(actors.requiredActive(), projectId, body));
    }
    @PostMapping("/projects/{projectId}/statistics/table/query")
    ResponseEntity<WorkItemStatisticsQuery.TablePage> table(@PathVariable UUID projectId, @RequestBody DashboardModels.TableQuery body) {
        return response(service.table(actors.requiredActive(), projectId, body));
    }
    @GetMapping("/projects/{projectId}/statistics/worklog")
    ResponseEntity<Worklog> worklog(@PathVariable UUID projectId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return response(service.worklog(actors.requiredActive(), projectId, from, to));
    }
    private static <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
