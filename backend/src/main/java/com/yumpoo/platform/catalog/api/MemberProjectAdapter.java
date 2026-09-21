package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.project.MemberProjectRepository;
import com.yumpoo.platform.catalog.application.project.MemberProjectService;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import org.springframework.stereotype.Component;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Component
public class MemberProjectAdapter implements MemberProjectQuery {
    private final MemberProjectService service;
    public MemberProjectAdapter(MemberProjectService service) { this.service = service; }
    public List<Project> find(CurrentActor actor, Collection<UUID> ids) {
        return service.find(actor, ids).stream().map(MemberProjectAdapter::project).toList();
    }
    public Page search(CurrentActor actor, String query, boolean archived, int offset, int limit) {
        var page = service.search(actor, query, archived, offset, limit);
        return new Page(page.items().stream().map(MemberProjectAdapter::project).toList(), page.totalElements());
    }
    private static Project project(MemberProjectRepository.Project p) {
        return new Project(p.id(), p.name(), p.code(), p.lifecycle());
    }
}
