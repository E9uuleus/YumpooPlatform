package com.yumpoo.platform.reporting.api;

import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.reporting.application.TeamDashboardModels.*;
import com.yumpoo.platform.reporting.application.TeamDashboardService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@ApiV1Controller
public class TeamDashboardController {
    private final TeamDashboardService service;
    private final CurrentActorProvider actors;
    public TeamDashboardController(TeamDashboardService service, CurrentActorProvider actors) { this.service = service; this.actors = actors; }

    @GetMapping("/company/team-dashboard/options")
    ResponseEntity<Options> options() { return response(service.options(actors.requiredActive())); }
    @PostMapping("/company/team-dashboard/timesheet/query")
    ResponseEntity<Timesheet> timesheet(@RequestBody TimesheetQuery body) { return response(service.timesheet(actors.requiredActive(), body)); }
    @PostMapping("/company/team-dashboard/workload/query")
    ResponseEntity<Workload> workload(@RequestBody WorkloadQuery body) { return response(service.workload(actors.requiredActive(), body)); }
    @PostMapping("/company/team-dashboard/workload/tasks/query")
    ResponseEntity<TaskPage> tasks(@RequestBody TasksQuery body) { return response(service.tasks(actors.requiredActive(), body)); }

    private static <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
