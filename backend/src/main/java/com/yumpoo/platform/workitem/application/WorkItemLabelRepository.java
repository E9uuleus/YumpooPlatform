package com.yumpoo.platform.workitem.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemLabelModels.PriorityLabel;
import static com.yumpoo.platform.workitem.application.WorkItemLabelModels.StatusLabel;

public interface WorkItemLabelRepository {
    void initialize(UUID companyId, UUID projectId, String templateKey, int templateVersion,
            Instant now);

    OptionalLong version(UUID companyId, UUID projectId, boolean lock);

    List<StatusLabel> statuses(UUID companyId, UUID projectId);

    List<PriorityLabel> priorities(UUID companyId, UUID projectId);

    boolean insertStatus(UUID companyId, UUID projectId, String code, String displayName,
            String colorToken, int sortOrder, Instant now);

    boolean insertPriority(UUID companyId, UUID projectId, String code, String displayName,
            String colorToken, int sortOrder, Instant now);

    boolean updateStatus(UUID companyId, UUID projectId, String code, String displayName,
            String colorToken, boolean active, Instant now);

    boolean updatePriority(UUID companyId, UUID projectId, String code, String displayName,
            String colorToken, boolean active, Instant now);

    void rewriteStatusOrders(UUID companyId, UUID projectId, Map<String, Integer> orders,
            Instant now);

    void rewritePriorityOrders(UUID companyId, UUID projectId, Map<String, Integer> orders,
            Instant now);

    boolean deleteStatus(UUID companyId, UUID projectId, String code, Instant now);

    boolean deletePriority(UUID companyId, UUID projectId, String code, Instant now);

    void incrementVersion(UUID companyId, UUID projectId, long expectedVersion, Instant now);
}
