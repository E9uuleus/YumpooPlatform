package com.yumpoo.platform.administration.application;

import java.util.List;
import java.util.UUID;

public interface GovernanceOverrideRepository {
    void insert(GovernanceOverrideRecord record);
    List<GovernanceOverrideRecord> findAll(UUID companyId, GovernanceOverrideAction action,
            String targetType, UUID targetId, GovernanceOverrideResult result, int offset, int size);
    long count(UUID companyId, GovernanceOverrideAction action, String targetType,
            UUID targetId, GovernanceOverrideResult result);
}
