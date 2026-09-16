package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MemberProjectRepository {
    record Project(UUID id, String name, String code, String lifecycle) {}
    List<Project> find(CurrentActor actor, Collection<UUID> ids);
    List<Project> search(CurrentActor actor, String query, boolean includeArchived, int offset, int limit);
    long count(CurrentActor actor, String query, boolean includeArchived);
}
