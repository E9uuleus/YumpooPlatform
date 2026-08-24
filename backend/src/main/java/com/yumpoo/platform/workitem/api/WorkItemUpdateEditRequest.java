package com.yumpoo.platform.workitem.api;

import jakarta.validation.constraints.NotNull;

public record WorkItemUpdateEditRequest(@NotNull String bodyHtml) {}
