package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.application.WorkItemNotificationModels.*;

import java.util.*;

public interface WorkItemNotificationRepository {
    Optional<Participants> participants(UUID company,UUID id);
    Optional<Update> update(UUID company,UUID id);
    Map<UUID,UUID> projects(UUID company,Collection<UUID> ids,boolean updates);
    Map<UUID,String> excerpts(UUID company,Collection<UUID> ids,Collection<UUID> projects);
    Map<UUID,Reference> references(UUID company,Collection<UUID> ids,Collection<UUID> projects);
}
