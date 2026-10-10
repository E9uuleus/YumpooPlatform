package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleQueryService;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.UUID;

@Component
public class ActiveCompanyAdminAdapter implements ActiveCompanyAdminQuery {
    private final PlatformRoleQueryService service;
    public ActiveCompanyAdminAdapter(PlatformRoleQueryService service) { this.service = service; }
    public Set<UUID> findUserIds(UUID companyId) { return service.findActiveCompanyAdminIds(companyId); }
}
