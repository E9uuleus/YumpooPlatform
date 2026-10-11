package com.yumpoo.platform.reporting.api;

import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.reporting.application.MyWorkService;
import com.yumpoo.platform.reporting.application.TeamDashboardModels.TaskPage;
import com.yumpoo.platform.reporting.application.TeamDashboardModels.Timesheet;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.time.LocalDate;

@ApiV1Controller
public class MyWorkController {
    private final MyWorkService service;
    private final CurrentActorProvider actors;
    public MyWorkController(MyWorkService service, CurrentActorProvider actors) { this.service = service; this.actors = actors; }

    @GetMapping("/me/timesheet")
    ResponseEntity<Timesheet> timesheet(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return response(service.timesheet(actors.requiredActive(), from, to));
    }
    @GetMapping("/me/current-tasks")
    ResponseEntity<TaskPage> currentTasks(@RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        return response(service.currentTasks(actors.requiredActive(), offset, limit));
    }

    private static <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
