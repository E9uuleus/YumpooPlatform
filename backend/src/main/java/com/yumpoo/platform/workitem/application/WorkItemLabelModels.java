package com.yumpoo.platform.workitem.application;

import java.util.List;

public final class WorkItemLabelModels {
    private WorkItemLabelModels() {}

    public record StatusLabel(String code, String displayName, String colorToken,
            String statusCategory, int sortOrder, boolean active, boolean protectedLabel,
            boolean inUse) {}

    public record PriorityLabel(String code, String displayName, String colorToken,
            int sortOrder, boolean active, boolean inUse) {}

    public record LabelCatalog(List<StatusLabel> statuses, List<PriorityLabel> priorities,
            long rowVersion, String etag, boolean canManage) {
        public LabelCatalog {
            statuses = List.copyOf(statuses);
            priorities = List.copyOf(priorities);
        }
    }
}
