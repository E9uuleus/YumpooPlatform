package com.yumpoo.platform.workitem.application;

import java.util.UUID;

public record ContentInitializationCommand(UUID companyId, UUID projectId, UUID actorUserId) {
}
