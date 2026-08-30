package com.yumpoo.platform.workitem.api;

import java.util.UUID;

public record ProjectWorkItemOrderMoveRequest(
        UUID previousVisibleWorkItemId,
        UUID nextVisibleWorkItemId
) {}
