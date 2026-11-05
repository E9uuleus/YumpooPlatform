package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleQueryService;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class PlatformRoleQueryAdapter implements PlatformRoleQuery {

    private final PlatformRoleQueryService service;

    public PlatformRoleQueryAdapter(PlatformRoleQueryService service) {
        this.service = service;
    }

    @Override
    public Set<PlatformRoleCode> findActiveRoleCodes(UUID companyId, UUID userId) {
        return service.findActiveRoleCodes(companyId, userId).stream()
                .map(PlatformRoleCode::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }
}
