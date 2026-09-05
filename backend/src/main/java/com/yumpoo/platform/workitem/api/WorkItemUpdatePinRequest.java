package com.yumpoo.platform.workitem.api;

import jakarta.validation.constraints.NotNull;

public record WorkItemUpdatePinRequest(@NotNull Boolean pinned) {}
