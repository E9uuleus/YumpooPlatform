package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.identityaccess.application.session.MinimalUserQueryRepository;
import com.yumpoo.platform.identityaccess.application.session.MinimalUserRecord;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class MinimalUserSnapshotAdapter implements MinimalUserSnapshotQuery {
    private final MinimalUserQueryRepository repository;

    public MinimalUserSnapshotAdapter(MinimalUserQueryRepository repository) {
        this.repository = repository;
    }

    @Override
    public Map<UUID, MinimalUserSnapshot> findByUserIds(UUID companyId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByUserIds(companyId, userIds).stream().map(MinimalUserSnapshotAdapter::snapshot)
                .collect(Collectors.toUnmodifiableMap(MinimalUserSnapshot::userId, Function.identity()));
    }

    @Override
    public Optional<MinimalUserSnapshot> findByUserId(UUID companyId, UUID userId) {
        return repository.findByUserIds(companyId, java.util.List.of(userId)).stream()
                .map(MinimalUserSnapshotAdapter::snapshot).findFirst();
    }

    @Override
    public MinimalUserPage findActiveEnabledByName(UUID companyId, String name, OffsetPageRequest page) {
        var result=repository.findActiveEnabledByName(companyId, name, page);
        return new MinimalUserPage(result.items().stream().map(MinimalUserSnapshotAdapter::snapshot).toList(),
                result.totalElements());
    }

    private static MinimalUserSnapshot snapshot(MinimalUserRecord user) {
        return new MinimalUserSnapshot(user.userId(),user.companyId(),user.displayName(),
                user.employmentStatus(),user.accountStatus());
    }
}
