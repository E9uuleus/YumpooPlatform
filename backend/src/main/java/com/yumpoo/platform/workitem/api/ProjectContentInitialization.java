package com.yumpoo.platform.workitem.api;

import java.util.UUID;

public record ProjectContentInitialization(UUID companyId, UUID projectId, UUID actorUserId) {
}
