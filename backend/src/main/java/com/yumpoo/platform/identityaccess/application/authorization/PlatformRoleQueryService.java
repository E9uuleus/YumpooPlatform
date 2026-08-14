package com.yumpoo.platform.identityaccess.application.authorization;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class PlatformRoleQueryService {

    private final PlatformRoleRepository repository;

    public PlatformRoleQueryService(PlatformRoleRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    public Set<String> findActiveRoleCodes(UUID companyId, UUID userId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        return Set.copyOf(repository.findActiveRoleCodes(companyId, userId));
    }
}
