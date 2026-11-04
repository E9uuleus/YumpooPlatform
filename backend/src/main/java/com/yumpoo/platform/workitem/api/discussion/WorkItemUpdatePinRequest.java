package com.yumpoo.platform.workitem.api.discussion;

import jakarta.validation.constraints.NotNull;

public record WorkItemUpdatePinRequest(@NotNull Boolean pinned) {}
