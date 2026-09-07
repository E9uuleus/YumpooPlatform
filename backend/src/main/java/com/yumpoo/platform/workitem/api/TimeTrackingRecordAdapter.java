package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.application.TimeTrackingService;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class TimeTrackingRecordAdapter implements TimeTrackingRecordQuery {
    private final TimeTrackingService service;
    public TimeTrackingRecordAdapter(TimeTrackingService service) { this.service = service; }

    public Page findVisible(CurrentActor actor, UUID workItemId, String cursor) {
        var page = service.history(actor, workItemId, cursor);
        return new Page(page.items().stream().map(s -> new Record(s.id(), s.projectId(), s.workItemId(),
                s.userId(), s.startedAt(), s.stoppedAt(), s.source(), s.durationMs(), s.rowVersion())).toList(),
                page.nextCursor(), page.serverNow());
    }
}
