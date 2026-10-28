package com.yumpoo.platform.workitem.application;

import java.util.Map;
import java.util.UUID;

public record ContentInitializationCommand(UUID companyId, UUID projectId, UUID actorUserId,
        Map<String, String> categoryColors, Map<String, String> statusColors, Map<String, String> priorityColors) {
}
