package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.application.TimeTrackingService;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class ProjectTimerStopAdapter implements ProjectTimerStopPort {
    private final TimeTrackingService service;
    public ProjectTimerStopAdapter(TimeTrackingService service) { this.service = service; }

    public int stopRunningTimers(CurrentActor actor, UUID projectId) {
        return service.stopRunningInArchivedProject(actor, projectId);
    }
}
