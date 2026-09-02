package com.yumpoo.platform.workitem.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record WorkItemContentPatchRequest(@NotNull UUID contentId) {}
