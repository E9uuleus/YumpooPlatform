package com.yumpoo.platform.workitem.application;

import java.util.UUID;

public final class WorkItemNotificationModels {
    private WorkItemNotificationModels() {}
    public record Participants(UUID projectId, UUID assigneeUserId, UUID reporterUserId) {}
    public record Update(UUID projectId, UUID workItemId, UUID authorUserId, UUID parentAuthorUserId) {}
    public record Reference(UUID workItemId, UUID projectId, UUID contentId, String contentName,
            String contentColorToken, String itemNo, String title, String statusCode, String statusCategory,boolean deleted) {}
}
