package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.project.MemberProjectService;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

@Component
public class CompanyProjectAdapter implements CompanyProjectQuery {
    private final MemberProjectService service;
    public CompanyProjectAdapter(MemberProjectService service) { this.service = service; }
    public List<MemberProjectQuery.Project> listForAdministrator(CurrentActor actor) {
        return service.listCompany(actor).stream().map(p -> new MemberProjectQuery.Project(p.id(), p.name(), p.code(), p.lifecycle())).toList();
    }
    public List<MemberProjectQuery.Project> listForScheduler(UUID companyId) {
        return service.listCompanyForScheduler(companyId).stream().map(p -> new MemberProjectQuery.Project(p.id(), p.name(), p.code(), p.lifecycle())).toList();
    }
}
