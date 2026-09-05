package com.yumpoo.platform.workitem.api;

import java.util.Objects;
import java.util.UUID;

public record WorkItemReferenceSnapshot(
        UUID workItemId,
        UUID projectId,
        UUID contentId,
        String contentName,
        String contentColorToken,
        String itemNo,
        String title,
        String statusCode,
        String statusCategory,
        boolean deleted
) {
    public WorkItemReferenceSnapshot {
        Objects.requireNonNull(workItemId, "workItemId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(contentId, "contentId must not be null");
        Objects.requireNonNull(itemNo, "itemNo must not be null");
        Objects.requireNonNull(contentName, "contentName must not be null");
        Objects.requireNonNull(contentColorToken, "contentColorToken must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(statusCode, "statusCode must not be null");
        Objects.requireNonNull(statusCategory, "statusCategory must not be null");
    }
}
