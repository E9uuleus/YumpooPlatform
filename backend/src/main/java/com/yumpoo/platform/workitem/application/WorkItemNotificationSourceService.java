package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.application.WorkItemNotificationModels.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@Transactional(readOnly=true)
public class WorkItemNotificationSourceService {
    private final WorkItemNotificationRepository repository;
    private final ProjectAccessSnapshotQuery access;
    public WorkItemNotificationSourceService(WorkItemNotificationRepository repository,ProjectAccessSnapshotQuery access) {
        this.repository=repository;this.access=access;
    }
    public Optional<Participants> participants(UUID company,UUID id) { return repository.participants(company,id); }
    public Optional<Update> update(UUID company,UUID id) { return repository.update(company,id); }
    public Map<UUID,String> excerpts(CurrentActor actor,Collection<UUID> ids) {
        if(ids.isEmpty()) return Map.of();
        var projects=repository.projects(actor.companyId(),ids,true);
        var visible=access.findVisible(actor,projects.values()).keySet();
        return visible.isEmpty()?Map.of():repository.excerpts(actor.companyId(),ids,visible);
    }
    public Map<UUID,Reference> references(CurrentActor actor,Collection<UUID> ids) {
        if(ids.isEmpty()) return Map.of();
        var projects=repository.projects(actor.companyId(),ids,false);
        var visible=access.findVisible(actor,projects.values()).keySet();
        return visible.isEmpty()?Map.of():repository.references(actor.companyId(),ids,visible);
    }
}
