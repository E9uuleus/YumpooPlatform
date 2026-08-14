package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleQueryService;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
