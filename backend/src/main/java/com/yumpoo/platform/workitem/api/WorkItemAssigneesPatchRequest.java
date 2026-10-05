package com.yumpoo.platform.workitem.api;

import java.util.List;
import java.util.UUID;

public record WorkItemAssigneesPatchRequest(List<UUID> assigneeUserIds) {}
