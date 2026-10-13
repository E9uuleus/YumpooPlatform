package com.yumpoo.platform.reporting.api;

import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.reporting.application.WorkHoursAlertService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@ApiV1Controller
public final class WorkHoursAlertController {
    private final CurrentActorProvider actors;
    private final WorkHoursAlertService service;
    private final IfMatchParser ifMatchParser;
    public WorkHoursAlertController(CurrentActorProvider actors, WorkHoursAlertService service, IfMatchParser ifMatchParser) {
        this.actors = actors; this.service = service; this.ifMatchParser = ifMatchParser;
    }

    @GetMapping("/admin/work-hours-alert")
    ResponseEntity<WorkHoursAlertService.View> get() { return respond(service.get(actors.requiredActive())); }

    @PutMapping("/admin/work-hours-alert")
    ResponseEntity<WorkHoursAlertService.View> update(@Valid @RequestBody UpdateRequest request,
            @RequestHeader(name = IfMatchParser.HEADER_NAME, required = false) String ifMatch) {
        return respond(service.update(actors.requiredActive(), request.dailyThresholdMinutes(), ifMatchParser.parseForVisibleResource(true, ifMatch)));
    }

    private static ResponseEntity<WorkHoursAlertService.View> respond(WorkHoursAlertService.View view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(view.etag()).body(view);
    }
    record UpdateRequest(@NotNull @Min(60) @Max(1440) Integer dailyThresholdMinutes) {}
}
