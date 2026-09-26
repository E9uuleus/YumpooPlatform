package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.application.WorkItemNotificationSourceService;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class WorkItemNotificationSourceAdapter implements WorkItemNotificationSourceQuery {
    private final WorkItemNotificationSourceService service;
    public WorkItemNotificationSourceAdapter(WorkItemNotificationSourceService service) { this.service=service; }
    public Optional<Participants> findParticipants(UUID company,UUID item) {
        return service.participants(company,item).map(p->new Participants(p.projectId(),p.assigneeUserId(),p.reporterUserId()));
    }
    public Optional<Update> findUpdate(UUID company,UUID update) {
        return service.update(company,update).map(p->new Update(p.projectId(),p.workItemId(),p.authorUserId(),p.parentAuthorUserId()));
    }
    public Map<UUID,String> findVisibleUpdateExcerpts(CurrentActor actor,Collection<UUID> ids) { return service.excerpts(actor,ids); }
}
