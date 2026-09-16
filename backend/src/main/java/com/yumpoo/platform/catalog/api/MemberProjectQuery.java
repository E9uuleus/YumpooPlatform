package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Personal reporting scope requires an active membership, including for company administrators. */
public interface MemberProjectQuery {
    List<Project> find(CurrentActor actor, Collection<UUID> ids);
    Page search(CurrentActor actor, String query, boolean includeArchived, int offset, int limit);

    record Project(UUID id, String name, String code, String lifecycle) {}
    record Page(List<Project> items, long totalElements) {}
}
