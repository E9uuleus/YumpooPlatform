package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.application.WorkItemTableSettingsModels.Write;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WorkItemTableSettingsRepository {
    Optional<Stored> find(UUID companyId, UUID projectId, UUID userId);
    Stored save(UUID companyId, UUID projectId, UUID userId, Write settings);

    record Stored(Write settings, Instant updatedAt) {}
}
