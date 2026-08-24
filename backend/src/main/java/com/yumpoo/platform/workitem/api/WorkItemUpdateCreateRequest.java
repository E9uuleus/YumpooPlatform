package com.yumpoo.platform.workitem.api;

import jakarta.validation.constraints.NotNull;

public record WorkItemUpdateCreateRequest(@NotNull String bodyHtml) {}
